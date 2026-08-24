package com.kk.storage.service;

import com.kk.config.MinioProperties;
import com.kk.storage.StorageKeys;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.entity.StoredFileUpload;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.repo.StoredFileUploadRepository;
import com.kk.storage.repo.MultipartInitLockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MinIO 大文件断点续传分片上传。
 * <p>
 * 客户端用 AWS SDK v2（MinIO Java SDK 不暴露 multipart）；进度状态全在 MinIO
 * （S3 multipart 服务端状态），续传时 {@code ListParts(uploadId)} 查已传 part；
 * uploadId 等元数据持久化在 {@link StoredFileUpload} 表（contentMd5 唯一幂等 key）。
 * 完整性由 CompleteMultipartUpload 的 part ETag 校验天然保证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "minio.enabled", havingValue = "true")
public class MultipartUploadService {

    private static final long SIGN_EXPIRE_SECONDS = 600L;
    private static final String SOURCE_MINIO = "minio";

    private final S3Client minioS3Client;
    private final S3Presigner minioS3Presigner;
    private final MinioProperties minioProperties;
    private final StoredFileUploadRepository uploadRepository;
    private final MultipartInitLockRepository initLockRepository;
    private final StoredFileRepository storedFileRepository;
    private final StoredFileService storedFileService;

    /**
     * 将浏览器计算的 MD5 转为主体命名空间内的 32 位幂等键，避免相同文件在不同应用/用户间碰撞。
     * UUID v3 基于 MD5 且结果稳定；这里不是密码用途，只用于数据库唯一键命名空间。
     */
    public static String scopedContentMd5(String ownerType, Long ownerId, String contentMd5) {
        if (ownerType == null || ownerType.isBlank() || ownerId == null || contentMd5 == null || contentMd5.isBlank()) {
            throw new IllegalArgumentException("上传主体与 contentMd5 不能为空");
        }
        String material = ownerType.trim() + ":" + ownerId + ":" + contentMd5.trim().toLowerCase();
        return java.util.UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    /**
     * 初始化分片上传（含续传判断）。
     * - 全新：createMultipartUpload + 写 StoredFileUpload/StoredFile(UPLOADING)
     * - 续传：按 contentMd5 查表，status=UPLOADED 直接返回成功；UPLOADING 则 ListParts 返回已传 part（含 etag）
     */
    @Transactional
    public InitResult init(Long parentId, String originalName, String contentType,
                           long fileSize, int totalChunks, String contentMd5, Long uploaderId) {
        return init(parentId, originalName, contentType, fileSize, totalChunks, contentMd5, uploaderId, null);
    }

    @Transactional
    public InitResult init(Long parentId, String originalName, String contentType,
                           long fileSize, int totalChunks, String contentMd5, Long uploaderId,
                           Long resumeFileId) {
        acquireInitLock(contentMd5);
        return initUnderLock(parentId, originalName, contentType, fileSize, totalChunks,
                contentMd5, uploaderId, resumeFileId);
    }

    private InitResult initUnderLock(Long parentId, String originalName, String contentType,
                                     long fileSize, int totalChunks, String contentMd5, Long uploaderId,
                                     Long resumeFileId) {
        Optional<StoredFileUpload> existing;
        if (resumeFileId != null) {
            StoredFile existingFile = storedFileRepository.findById(resumeFileId)
                    .orElseThrow(() -> new IllegalArgumentException("续传记录不存在: " + resumeFileId));
            validateResumeFile(existingFile, originalName, fileSize, uploaderId);
            storedFileService.validateParentExists(existingFile.getParentId(), uploaderId);
            existing = uploadRepository.findByStoredFileId(resumeFileId);
            if (existing.isEmpty()) {
                throw new IllegalArgumentException("未找到分片续传记录: " + resumeFileId);
            }
        } else {
            storedFileService.validateParentExists(parentId, uploaderId);
            existing = uploadRepository.findByContentMd5(contentMd5);
        }
        storedFileService.checkQuota(uploaderId, 0);
        if (existing.isPresent()) {
            StoredFileUpload u = existing.get();
            if (StoredFileUpload.STATUS_UPLOADED.equals(u.getStatus())) {
                // 已上传完成，直接成功
                return InitResult.alreadyDone(u.getStorageKey(), u.getStoredFileId());
            }
            if (!StoredFileUpload.STATUS_UPLOADING.equals(u.getStatus())) {
                throw new IllegalArgumentException("文件当前不支持续传: " + originalName);
            }
            // 续传：查 MinIO 已传 part（含 partNumber + etag，complete 时复用 etag 无需重传）
            List<UploadedPart> uploadedParts = listUploadedParts(u);
            return InitResult.resume(u, uploadedParts);
        }

        // 全新上传
        String timestampUuid = StorageKeys.timestampUuid();
        String folderPath = storedFileService.resolveFolderPath(parentId);
        String rootPrefix = minioProperties.getPrefix();
        String chunkKeyPrefix = StorageKeys.buildChunkKeyPrefix(rootPrefix, folderPath, timestampUuid, originalName);
        String storageKey = StorageKeys.buildMergedStorageKey(rootPrefix, folderPath, timestampUuid, originalName);
        String bucket = minioProperties.getBucket();

        String uploadId = minioS3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .contentType(contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream")
                .build()).uploadId();

        // 先写 StoredFile(UPLOADING)
        StoredFile f = new StoredFile();
        f.setParentId(parentId);
        f.setUploaderId(uploaderId);
        f.setName(StorageKeys.baseName(originalName));
        f.setType(StoredFile.TYPE_FILE);
        f.setStorageSource(SOURCE_MINIO);
        f.setStorageKey(storageKey);
        f.setOriginalName(originalName);
        f.setSize(fileSize);
        f.setContentType(contentType);
        f.setStatus(StoredFile.STATUS_UPLOADING);
        f = storedFileRepository.save(f);

        StoredFileUpload u = new StoredFileUpload();
        u.setContentMd5(contentMd5);
        u.setStoredFileId(f.getId());
        u.setUploadId(uploadId);
        u.setChunkKeyPrefix(chunkKeyPrefix);
        u.setStorageKey(storageKey);
        u.setBucket(bucket);
        u.setTotalChunks(totalChunks);
        u.setStatus(StoredFileUpload.STATUS_UPLOADING);
        uploadRepository.save(u);

        log.info("Multipart upload initiated: contentMd5={}, uploadId={}, storageKey={}, totalChunks={}",
                contentMd5, uploadId, storageKey, totalChunks);
        return InitResult.fresh(u);
    }

    /**
     * 升级兼容：旧版本直接保存浏览器原始 MD5。首次由原所属主体访问时原地迁移为命名空间键；
     * 已是新键时不重复哈希，其他主体的同 MD5 记录也不会被读取或改写。
     */
    @Transactional
    public void migrateLegacyContentKey(String rawMd5, String scopedKey, Long openAppId, Long uploaderId) {
        if (rawMd5 == null || rawMd5.isBlank() || rawMd5.equals(scopedKey)
                || uploadRepository.findByContentMd5(scopedKey).isPresent()) {
            return;
        }
        acquireInitLock(rawMd5);
        StoredFileUpload legacy = uploadRepository.findByContentMd5(rawMd5).orElse(null);
        if (legacy == null || legacy.getStoredFileId() == null) return;
        StoredFile file = storedFileRepository.findById(legacy.getStoredFileId()).orElse(null);
        boolean owned = file != null && (openAppId != null
                ? Objects.equals(openAppId, file.getOpenAppId())
                : Objects.equals(uploaderId, file.getUploaderId()));
        if (!owned) return;
        legacy.setContentMd5(scopedKey);
        uploadRepository.saveAndFlush(legacy);
        log.info("Multipart legacy idempotency key migrated: storedFileId={}, ownerType={}",
                legacy.getStoredFileId(), openAppId != null ? "open-app" : "user");
    }

    private void acquireInitLock(String contentKey) {
        initLockRepository.insertIgnore(contentKey);
        initLockRepository.findForUpdate(contentKey)
                .orElseThrow(() -> new IllegalStateException("无法获取分片初始化锁"));
    }

    /**
     * 签发某 chunk 的 presigned PUT 直链。
     * <p>
     * S3 multipart 规则：所有 part 必须上传到 createMultipartUpload 时的同一个 key（用 partNumber+uploadId 区分），
     * 合并后产物就在该 key。故 chunk 直传的 key = storageKey（最终路径），不另设 _chunks 路径。
     */
    public String sign(String contentMd5, int chunkId) {
        StoredFileUpload u = requireUpload(contentMd5);
        if (!StoredFileUpload.STATUS_UPLOADING.equals(u.getStatus())) {
            throw new IllegalArgumentException("分片上传已结束: " + contentMd5);
        }
        if (chunkId < 0 || chunkId >= u.getTotalChunks()) {
            throw new IllegalArgumentException("chunkId 超出范围: " + chunkId);
        }
        int partNumber = chunkId + 1; // S3 partNumber 从 1 开始
        PresignedUploadPartRequest presigned = minioS3Presigner.presignUploadPart(UploadPartPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(SIGN_EXPIRE_SECONDS))
                .uploadPartRequest(r -> r.bucket(u.getBucket()).key(u.getStorageKey())
                        .uploadId(u.getUploadId()).partNumber(partNumber))
                .build());
        return presigned.url().toString();
    }

    /**
     * 合并完成。前端提交全部 part 的 {chunkId, etag}；后端校验 part 数=totalChunks，
     * completeMultipartUpload（MinIO 自动校验每个 part ETag）。
     */
    @Transactional
    public CompleteResult complete(String contentMd5, List<PartETag> parts) {
        StoredFileUpload u = uploadRepository.findByContentMd5ForUpdate(contentMd5)
                .orElseThrow(() -> new IllegalArgumentException("未找到上传记录: " + contentMd5));
        if (StoredFileUpload.STATUS_UPLOADED.equals(u.getStatus())) {
            return new CompleteResult(u.getStorageKey(), u.getStoredFileId());
        }
        if (!StoredFileUpload.STATUS_UPLOADING.equals(u.getStatus())) {
            throw new IllegalArgumentException("分片上传已结束: " + contentMd5);
        }
        if (parts == null || parts.size() != u.getTotalChunks()) {
            throw new IllegalArgumentException("分片数量不匹配: 期望 " + u.getTotalChunks() + "，实际 " + (parts == null ? 0 : parts.size()));
        }
        long distinctChunks = parts.stream().map(PartETag::chunkId).distinct().count();
        boolean invalidPart = parts.stream().anyMatch(p -> p.chunkId() < 0
                || p.chunkId() >= u.getTotalChunks() || p.etag() == null || p.etag().isBlank());
        if (distinctChunks != u.getTotalChunks() || invalidPart) {
            throw new IllegalArgumentException("分片编号或 ETag 无效");
        }
        // 按 chunkId 排序，转 CompletedPart（partNumber = chunkId + 1）
        List<CompletedPart> completedParts = parts.stream()
                .sorted((a, b) -> Integer.compare(a.chunkId(), b.chunkId()))
                .map(p -> CompletedPart.builder().partNumber(p.chunkId() + 1).eTag(stripQuotes(p.etag())).build())
                .toList();
        try {
            minioS3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(u.getBucket())
                    .key(u.getStorageKey())
                    .uploadId(u.getUploadId())
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
        } catch (S3Exception e) {
            // MinIO 校验 part ETag 失败（分片损坏/篡改）会抛错
            log.warn("Multipart complete failed (etag mismatch?): contentMd5={}, msg={}", contentMd5, e.getMessage());
            // 客户端提交了错误/过期 ETag 时保留上传状态，允许重新 ListParts 后重试；不得删除成功分片和文件记录。
            throw new IllegalArgumentException("分片校验不一致，请刷新分片状态后重试");
        }

        // 成功：更新状态
        u.setStatus(StoredFileUpload.STATUS_UPLOADED);
        uploadRepository.save(u);
        StoredFile f = storedFileRepository.findById(u.getStoredFileId()).orElse(null);
        if (f == null) {
            // 兜底：StoredFile 记录缺失（历史数据/异常情况）但 MinIO 已合并成功，重建记录
            log.warn("StoredFile 缺失但 multipart 已完成，重建记录: storedFileId={}, storageKey={}", u.getStoredFileId(), u.getStorageKey());
            f = new StoredFile();
            // 无法精确还原 parentId（缺失记录），挂到根；如需正确归位需调用方补传
        }
        f.setStatus(StoredFile.STATUS_UPLOADED);
        storedFileRepository.save(f);
        log.info("Multipart upload completed: contentMd5={}, storageKey={}", contentMd5, u.getStorageKey());
        return new CompleteResult(u.getStorageKey(), u.getStoredFileId());
    }

    /** 删除分片对象（complete 失败时清理）+ abort multipart。 */
    public void abort(String contentMd5) {
        uploadRepository.findByContentMd5(contentMd5).ifPresent(this::abortInternal);
    }

    /** 定时清理调用：abort + 删 DB 记录 + 删 StoredFile(UPLOADING)。 */
    @Transactional
    public void cleanupRecord(StoredFileUpload u) {
        abortInternal(u);
    }

    private void abortInternal(StoredFileUpload u) {
        try {
            minioS3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(u.getBucket()).key(u.getStorageKey()).uploadId(u.getUploadId()).build());
        } catch (Exception e) {
            log.warn("Abort multipart failed (ignore): uploadId={}, msg={}", u.getUploadId(), e.getMessage());
        }
        uploadRepository.delete(u);
        if (u.getStoredFileId() != null) {
            storedFileRepository.findById(u.getStoredFileId()).ifPresent(storedFileRepository::delete);
        }
    }

    private List<UploadedPart> listUploadedParts(StoredFileUpload u) {
        try {
            List<Part> parts = new ArrayList<>();
            Integer next = null;
            do {
                final Integer marker = next;
                var resp = minioS3Client.listParts(ListPartsRequest.builder()
                        .bucket(u.getBucket()).key(u.getStorageKey()).uploadId(u.getUploadId())
                        .partNumberMarker(marker).build());
                parts.addAll(resp.parts());
                next = resp.isTruncated() ? resp.nextPartNumberMarker() : null;
            } while (next != null);
            return parts.stream()
                    .map(p -> new UploadedPart(p.partNumber(), p.eTag()))
                    .toList();
        } catch (Exception e) {
            log.warn("ListParts failed (treat as no parts, will re-upload): uploadId={}, msg={}", u.getUploadId(), e.getMessage());
            return List.of();
        }
    }

    private StoredFileUpload requireUpload(String contentMd5) {
        return uploadRepository.findByContentMd5(contentMd5)
                .orElseThrow(() -> new IllegalArgumentException("未找到上传记录: " + contentMd5));
    }

    /** 开放 API 在签名或合并前调用，确保 hash 对应上传确属当前应用。 */
    public void requireOpenAppOwner(String contentMd5, Long openAppId) {
        StoredFileUpload upload = requireUpload(contentMd5);
        StoredFile file = upload.getStoredFileId() == null ? null
                : storedFileRepository.findById(upload.getStoredFileId()).orElse(null);
        if (file == null || !Objects.equals(openAppId, file.getOpenAppId())) {
            throw new IllegalArgumentException("无权操作其他应用的上传");
        }
    }

    /** 管理端在签名或合并前调用，确保 hash 对应上传确属当前登录用户。 */
    public void requireUploaderOwner(String contentMd5, Long uploaderId) {
        StoredFileUpload upload = requireUpload(contentMd5);
        StoredFile file = upload.getStoredFileId() == null ? null
                : storedFileRepository.findById(upload.getStoredFileId()).orElse(null);
        if (file == null || !Objects.equals(uploaderId, file.getUploaderId())) {
            throw new IllegalArgumentException("无权操作其他用户的上传");
        }
    }

    /** MinIO/S3 返回的 ETag 带双引号，complete 时需去除 */
    private static String stripQuotes(String etag) {
        if (etag == null) return etag;
        return etag.replace("\"", "");
    }

    // ===== DTOs =====

    public record PartETag(int chunkId, String etag) {}

    /** 续传时返回的已上传 part（partNumber 从 1 开始，etag 来自 MinIO ListParts） */
    public record UploadedPart(int partNumber, String etag) {}

    public record CompleteResult(String storageKey, Long storedFileId) {}

    public static final class InitResult {
        public final String uploadId;
        public final String chunkKeyPrefix;
        public final String storageKey;
        public final int totalChunks;
        public final Long storedFileId;
        /** 已上传的 part（含 partNumber+etag）；空表示全新上传 */
        public final List<UploadedPart> uploadedParts;
        public final boolean alreadyDone;

        public InitResult(String uploadId, String chunkKeyPrefix, String storageKey, int totalChunks,
                          Long storedFileId, List<UploadedPart> uploadedParts, boolean alreadyDone) {
            this.uploadId = uploadId;
            this.chunkKeyPrefix = chunkKeyPrefix;
            this.storageKey = storageKey;
            this.totalChunks = totalChunks;
            this.storedFileId = storedFileId;
            this.uploadedParts = uploadedParts;
            this.alreadyDone = alreadyDone;
        }

        static InitResult fresh(StoredFileUpload u) {
            return new InitResult(u.getUploadId(), u.getChunkKeyPrefix(), u.getStorageKey(), u.getTotalChunks(),
                    u.getStoredFileId(), List.of(), false);
        }

        static InitResult resume(StoredFileUpload u, List<UploadedPart> uploadedParts) {
            return new InitResult(u.getUploadId(), u.getChunkKeyPrefix(), u.getStorageKey(), u.getTotalChunks(),
                    u.getStoredFileId(), uploadedParts, false);
        }

        static InitResult alreadyDone(String storageKey, Long storedFileId) {
            return new InitResult(null, null, storageKey, 0, storedFileId, List.of(), true);
        }
    }

    private void validateResumeFile(StoredFile file, String originalName, long fileSize, Long uploaderId) {
        if (!StoredFile.TYPE_FILE.equals(file.getType())) {
            throw new IllegalArgumentException("续传记录不是文件: " + file.getId());
        }
        if (!StoredFile.STATUS_UPLOADING.equals(file.getStatus())
                && !StoredFile.STATUS_UPLOADED.equals(file.getStatus())) {
            throw new IllegalArgumentException("文件当前不支持续传: " + file.getName());
        }
        if (uploaderId != null && file.getUploaderId() != null
                && !Objects.equals(uploaderId, file.getUploaderId())) {
            throw new IllegalArgumentException("无权续传该文件");
        }
        if (!SOURCE_MINIO.equals(file.getStorageSource())) {
            throw new IllegalArgumentException("分片续传仅支持 MinIO");
        }
        if (file.getOriginalName() != null && !Objects.equals(originalName, file.getOriginalName())) {
            throw new IllegalArgumentException("续传文件名与原记录不一致");
        }
        if (file.getSize() > 0 && file.getSize() != fileSize) {
            throw new IllegalArgumentException("续传文件大小与原记录不一致");
        }
    }
}
