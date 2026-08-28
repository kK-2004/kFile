package com.kk.openapi.service;

import com.kk.config.MinioProperties;
import com.kk.config.OssProperties;
import com.kk.openapi.entity.OpenApp;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.StorageKeys;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.service.MultipartUploadService;
import com.kk.storage.service.StoredFileService;
import com.kk.storage.service.CdnPreviewLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * 开放文件 API 业务：应用目录解析（rootPath/默认 + 可选 path）、数据源路由（默认取系统配置）、
 * 预签名简单直传、分片直传（复用 {@link MultipartUploadService}，仅 MinIO）、预签名下载链接。
 * 与 {@link StoredFileService}（AdminUser uploader/配额耦合）分离：应用上传跳过个人配额、归属写 openAppId。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenFileService {

    /** 直传 PUT 预签名有效期（秒） */
    private static final long DIRECT_EXPIRE_SECONDS = 600L;
    private static final long DOWNLOAD_EXPIRE_DEFAULT = 300L;
    private static final long DOWNLOAD_EXPIRE_MIN = 60L;
    private static final long DOWNLOAD_EXPIRE_MAX = 3600L;
    private static final String SOURCE_MINIO = "minio";

    private final StoredFileRepository storedFileRepository;
    private final StorageBrowserRegistry registry;
    private final OpenAppService openAppService;
    private final StoredFileService storedFileService;
    private final MinioProperties minioProperties;
    private final OssProperties ossProperties;
    private final ObjectProvider<MultipartUploadService> multipartUploadService;
    private final CdnPreviewLinkService cdnPreviewLinkService;

    // ===== 数据源路由 =====

    /** 解析请求 source：请求显式传入 > 应用配置的 defaultSource > 兜底 oss；均校验已启用（未知抛 IllegalArgumentException → 400） */
    public String resolveSource(String source, OpenApp app) {
        String s = StringUtils.hasText(source) ? source.trim()
                : (StringUtils.hasText(app.getDefaultSource()) ? app.getDefaultSource().trim() : "oss");
        return registry.get(s).sourceId();
    }

    // ===== 目录解析 =====

    /**
     * 解析（懒创建幂等）应用上传目录：rootPath（未配置默认「开放应用/<appName>」）+ 可选 path 子目录。
     * path 逐段 {@link StorageKeys#safeName} 校验，非法抛 IllegalArgumentException（400）。
     */
    public Long resolveAppFolder(OpenApp app, String path) {
        List<String> segments = new ArrayList<>();
        for (String seg : OpenAppService.effectiveRoot(app).split("/")) {
            if (!seg.isBlank()) segments.add(seg);
        }
        if (StringUtils.hasText(path)) {
            for (String seg : path.split("/")) {
                if (!seg.isBlank()) segments.add(OpenAppService.safeSegment(seg));
            }
        }
        return openAppService.ensureFolderChain(segments);
    }

    // ===== 简单上传（预签名直传） =====

    public record UploadInitResult(String storageKey, String source, String putUrl, long expiresIn, Long fileId) {}

    public UploadInitResult initUpload(OpenApp app, String originalName, String contentType, String path, String source) {
        if (!StringUtils.hasText(originalName)) {
            throw new IllegalArgumentException("originalName 不能为空");
        }
        String src = resolveSource(source, app);
        StorageBrowserService svc = registry.get(src);
        Long parentId = resolveAppFolder(app, path);
        String rootPrefix = rootPrefixFor(svc.sourceId());
        String folderPath = storedFileService.resolveFolderPath(parentId);
        String storageKey = StorageKeys.buildDirectUploadKey(rootPrefix, folderPath, originalName);
        String putUrl = svc.presignedPutUrl(storageKey, DIRECT_EXPIRE_SECONDS, contentType);

        StoredFile pre = new StoredFile();
        pre.setParentId(parentId);
        pre.setOpenAppId(app.getId());
        pre.setName(StorageKeys.baseName(originalName));
        pre.setType(StoredFile.TYPE_FILE);
        pre.setStorageSource(svc.sourceId());
        pre.setStorageKey(storageKey);
        pre.setOriginalName(originalName);
        pre.setContentType(contentType);
        pre.setSize(0);
        pre.setStatus(StoredFile.STATUS_UPLOADING);
        pre = storedFileRepository.save(pre);
        return new UploadInitResult(storageKey, svc.sourceId(), putUrl, DIRECT_EXPIRE_SECONDS, pre.getId());
    }

    public record UploadCompleteResult(Long fileId, String name, long size, String contentType) {}

    public UploadCompleteResult completeUpload(OpenApp app, String storageKey, String source) {
        if (!StringUtils.hasText(storageKey)) {
            throw new IllegalArgumentException("storageKey 不能为空");
        }
        String src = resolveSource(source, app);
        StorageBrowserService svc = registry.get(src);
        StoredFile f = storedFileRepository.findByStorageKeyAndStatus(storageKey, StoredFile.STATUS_UPLOADING)
                .orElseThrow(() -> new IllegalArgumentException("未找到上传初始化记录: " + storageKey));
        if (!app.getId().equals(f.getOpenAppId())) {
            throw new IllegalArgumentException("无权完成其他应用的上传");
        }
        StorageBrowserService.Entry entry = svc.stat(storageKey);
        if (entry == null) {
            throw new IllegalArgumentException("对象尚未上传或不存在，请先 PUT 到 putUrl 后再确认: " + storageKey);
        }
        f.setSize(entry.getSize());
        if (!StringUtils.hasText(f.getContentType()) && StringUtils.hasText(entry.getContentType())) {
            f.setContentType(entry.getContentType());
        }
        f.setStatus(StoredFile.STATUS_UPLOADED);
        storedFileRepository.save(f);
        return new UploadCompleteResult(f.getId(), f.getName(), f.getSize(), f.getContentType());
    }

    // ===== 分片上传（仅支持分片的数据源，首期 MinIO） =====

    @Transactional
    public MultipartUploadService.InitResult multipartInit(OpenApp app, String originalName, String contentType,
                                                            long fileSize, int totalChunks, String contentMd5,
                                                            String path, String source) {
        MultipartUploadService mp = multipartUploadService.getIfAvailable();
        String src = resolveSource(source, app);
        if (mp == null || !SOURCE_MINIO.equals(src)) {
            throw new IllegalArgumentException("数据源不支持分片上传: " + src);
        }
        if (!StringUtils.hasText(contentMd5)) {
            throw new IllegalArgumentException("contentMd5 不能为空");
        }
        Long parentId = resolveAppFolder(app, path);
        String scopedMd5 = MultipartUploadService.scopedContentMd5("open-app", app.getId(), contentMd5);
        mp.migrateLegacyContentKey(contentMd5, scopedMd5, app.getId(), null);
        MultipartUploadService.InitResult result =
                mp.init(parentId, originalName, contentType, fileSize, totalChunks, scopedMd5, null);
        // 新记录绑定本应用；全局 MD5 命中其他主体时必须拒绝，绝不能改写原归属。
        if (result.storedFileId != null) {
            StoredFile f = storedFileRepository.findById(result.storedFileId)
                    .orElseThrow(() -> new IllegalArgumentException("分片上传文件记录不存在"));
            if (f.getOpenAppId() == null && f.getUploaderId() == null) {
                    f.setOpenAppId(app.getId());
                    storedFileRepository.save(f);
            } else if (!app.getId().equals(f.getOpenAppId())) {
                throw new IllegalArgumentException("无权续传其他应用的上传");
            }
        }
        return result;
    }

    public String multipartSign(OpenApp app, String contentMd5, int chunkId) {
        MultipartUploadService mp = requireMultipart();
        String scopedMd5 = MultipartUploadService.scopedContentMd5("open-app", app.getId(), contentMd5);
        mp.requireOpenAppOwner(scopedMd5, app.getId());
        return mp.sign(scopedMd5, chunkId);
    }

    public record MultipartCompleteResult(String storageKey, Long fileId, long size) {}

    public MultipartCompleteResult multipartComplete(OpenApp app, String contentMd5,
                                                     List<MultipartUploadService.PartETag> parts) {
        MultipartUploadService mp = requireMultipart();
        String scopedMd5 = MultipartUploadService.scopedContentMd5("open-app", app.getId(), contentMd5);
        mp.requireOpenAppOwner(scopedMd5, app.getId());
        MultipartUploadService.CompleteResult result = mp.complete(scopedMd5, parts);
        StoredFile f = result.storedFileId() == null ? null
                : storedFileRepository.findById(result.storedFileId()).orElse(null);
        return new MultipartCompleteResult(result.storageKey(), result.storedFileId(),
                f != null ? f.getSize() : 0);
    }

    private MultipartUploadService requireMultipart() {
        MultipartUploadService mp = multipartUploadService.getIfAvailable();
        if (mp == null) {
            throw new IllegalArgumentException("数据源不支持分片上传");
        }
        return mp;
    }

    // ===== 下载链接 =====

    public record DownloadLinkResult(String url, long expiresIn) {}

    /** fileId 优先；或回传上传响应中的 storageKey + source。expiresIn clamp 到 [60, 3600]，默认 300。 */
    public DownloadLinkResult downloadLink(OpenApp app, Long fileId, String storageKey, String source,
                                           String filename, Long expiresIn) {
        long expire = expiresIn == null ? DOWNLOAD_EXPIRE_DEFAULT
                : Math.max(DOWNLOAD_EXPIRE_MIN, Math.min(DOWNLOAD_EXPIRE_MAX, expiresIn));

        StoredFile f;
        if (fileId != null) {
            f = storedFileRepository.findById(fileId).orElse(null);
        } else if (StringUtils.hasText(storageKey)) {
            String src = resolveSource(source, app);
            f = storedFileRepository.findFirstByStorageKeyOrderByIdDesc(storageKey)
                    .filter(x -> src.equals(x.getStorageSource()))
                    .orElse(null);
        } else {
            throw new IllegalArgumentException("fileId 与 storageKey 不能同时为空");
        }
        // 不属于本应用的文件一律按不存在处理（不泄露存在性）
        if (f == null || !StoredFile.TYPE_FILE.equals(f.getType())
                || !app.getId().equals(f.getOpenAppId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        String downloadName = StringUtils.hasText(filename) ? filename : f.getOriginalName();
        String url = registry.get(f.getStorageSource())
                .downloadUrl(f.getStorageKey(), true, expire, downloadName);
        return new DownloadLinkResult(url, expire);
    }

    // ===== CDN 媒体预览链接 =====

    public record CdnLinkResult(String token, long expiresIn, boolean permanent, String contentType) {}

    /** 为当前开放应用创建媒体 CDN 预览链接；expiresIn 省略或为 0 表示永久。 */
    public CdnLinkResult cdnLink(OpenApp app, Long fileId, Long expiresIn) {
        long seconds = expiresIn == null ? 0L : expiresIn;
        CdnPreviewLinkService.CreatedLink link = cdnPreviewLinkService.createForOpenApp(
                fileId, seconds, app.getId());
        return new CdnLinkResult(link.token(), seconds, seconds == 0L, link.contentType());
    }

    // ===== helpers =====

    private String rootPrefixFor(String sourceId) {
        return SOURCE_MINIO.equals(sourceId) ? minioProperties.getPrefix() : ossProperties.getPrefix();
    }
}
