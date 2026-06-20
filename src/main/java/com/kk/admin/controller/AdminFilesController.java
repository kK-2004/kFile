package com.kk.admin.controller;

import com.kk.share.service.ShareLinkService;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.service.MultipartUploadService;
import com.kk.storage.service.StoredFileService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 文件管理接口（DB 虚拟树 + 浏览器直传）。
 * SUPER + ADMIN 均可访问；ADMIN 只看/操作自己上传的，SUPER 可选 scope=all/mine。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/files")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER') or hasRole('ADMIN')")
public class AdminFilesController {

    private static final long DEFAULT_EXPIRE_SECONDS = 300L;

    private final StoredFileService storedFileService;
    private final com.kk.security.repo.AdminUserRepository userRepo;
    private final com.kk.storage.repo.StoredFileUploadRepository uploadRepository;
    /** MinIO 分片上传服务（条件装配，minio 未启用时为空） */
    private final ObjectProvider<MultipartUploadService> multipartProvider;

    /** 解析当前登录用户：返回 {id, isSuper}；用于 scope 过滤与 owner 校验 */
    private long[] currentActor(org.springframework.security.core.Authentication auth) {
        com.kk.security.entity.AdminUser u = userRepo.findByUsername(auth.getName()).orElse(null);
        if (u == null) return new long[]{0L, 0L};
        boolean isSuper = "SUPER".equalsIgnoreCase(u.getRole());
        return new long[]{u.getId(), isSuper ? 1L : 0L};
    }

    /** scope 解析：ADMIN 强制 mine；SUPER 默认 all，可选 mine。返回用于过滤的 uploaderId（null=不限） */
    private Long scopeUploader(org.springframework.security.core.Authentication auth, String scope) {
        long[] actor = currentActor(auth);
        boolean isSuper = actor[1] == 1L;
        if (isSuper && !"mine".equalsIgnoreCase(scope)) return null; // SUPER 看全部
        return actor[0]; // ADMIN 或 SUPER 选「我的」→ 按自己 id 过滤
    }

    /** 可用上传数据源（OSS 始终在；MinIO 仅启用时在），供前端选择 + 记忆 */
    @GetMapping("/sources")
    public List<StoredFileSource> sources() {
        return storedFileService.availableSources().stream()
                .map(s -> new StoredFileSource(s.id(), s.label()))
                .toList();
    }

    /** 当前用户的配额信息（已用/总额；SUPER 不限） */
    @GetMapping("/quota")
    public Map<String, Object> quota(org.springframework.security.core.Authentication auth) {
        long[] actor = currentActor(auth);
        boolean isSuper = actor[1] == 1L;
        com.kk.security.entity.AdminUser u = userRepo.findById(actor[0]).orElse(null);
        Long quota = u == null ? null : u.getQuotaBytes();
        boolean unlimited = isSuper || quota == null || quota <= 0;
        long used = storedFileService.usedSpace(actor[0]);
        return Map.of(
                "used", used,
                "quota", unlimited ? 0 : quota,
                "unlimited", unlimited
        );
    }

    /** 列目录 + 面包屑（分页 + scope + 搜索） */
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(value = "parentId", required = false) Long parentId,
                                    @RequestParam(value = "page", required = false, defaultValue = "0") int page,
                                    @RequestParam(value = "pageSize", required = false, defaultValue = "15") int pageSize,
                                    @RequestParam(value = "scope", required = false) String scope,
                                    @RequestParam(value = "keyword", required = false) String keyword,
                                    org.springframework.security.core.Authentication auth) {
        int size = (pageSize <= 0) ? 15 : Math.min(pageSize, 100);
        int p = Math.max(0, page);
        Long uploaderId = scopeUploader(auth, scope);
        var pageResult = storedFileService.listChildren(parentId, uploaderId, keyword, p, size);
        List<StoredFileNode> nodes = pageResult.getContent().stream()
                .map(f -> {
                    // UPLOADING 文件附带 contentMd5（从 StoredFileUpload 关联查），供前端续传
                    String md5 = null;
                    if (com.kk.storage.entity.StoredFile.STATUS_UPLOADING.equals(f.getStatus())) {
                        md5 = uploadRepository.findByStoredFileId(f.getId())
                                .map(u -> u.getContentMd5()).orElse(null);
                    }
                    return StoredFileNode.from(f, md5);
                })
                .toList();
        List<StoredFileService.PathCrumb> path = storedFileService.breadcrumb(parentId);
        return Map.of(
                "nodes", nodes,
                "path", path,
                "page", p,
                "pageSize", size,
                "total", pageResult.getTotalElements(),
                "totalPages", pageResult.getTotalPages()
        );
    }

    /** 创建文件夹 */
    @PostMapping("/mkdir")
    public StoredFileNode mkdir(@RequestBody MkdirReq req, org.springframework.security.core.Authentication auth) {
        long[] actor = currentActor(auth);
        return StoredFileNode.from(storedFileService.mkdir(req.getParentId(), req.getName(), actor[0]));
    }

    /**
     * 第一步：签发浏览器直传 PUT 预签名直链（对称 OSS direct-init）。
     * 前端拿到 putUrl + storageKey 后直接 PUT 到对象存储，不经过后端。
     */
    @PostMapping("/upload-init")
    public Map<String, Object> uploadInit(@RequestBody UploadInitReq req, org.springframework.security.core.Authentication auth) {
        long[] actor = currentActor(auth);
        StoredFileService.DirectUploadInit init = storedFileService.initUpload(
                req.getParentId(), req.getSource(), req.getOriginalName(), req.getContentType(), actor[0]);
        return Map.of(
                "storageKey", init.storageKey(),
                "storageSource", init.storageSource(),
                "putUrl", init.putUrl(),
                "expireSeconds", init.expireSeconds()
        );
    }

    /**
     * 第二步：前端直传完成后回调，stat 取真实 size + 落 DB（对称 OSS direct-complete）。
     */
    @PostMapping("/upload-complete")
    public StoredFileNode uploadComplete(@RequestBody UploadCompleteReq req, org.springframework.security.core.Authentication auth) {
        long[] actor = currentActor(auth);
        return StoredFileNode.from(storedFileService.completeUpload(
                req.getParentId(), req.getStorageSource(), req.getStorageKey(),
                req.getOriginalName(), req.getContentType(), req.getSize(), actor[0]));
    }

    /** 删除节点（文件夹递归）；ADMIN 只能删自己的，SUPER 全部 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id, org.springframework.security.core.Authentication auth) {
        long[] actor = currentActor(auth);
        boolean isSuper = actor[1] == 1L;
        StoredFileService.DeleteResult res = storedFileService.delete(id, isSuper ? null : actor[0]);
        return Map.of("ok", true, "deletedDb", res.deletedDb(), "failedObjects", res.failedObjects());
    }

    /** 下载链接 */
    @GetMapping("/download-url")
    public Map<String, Object> downloadUrl(@RequestParam("fileId") Long fileId,
                                           @RequestParam(value = "download", required = false, defaultValue = "true") boolean download,
                                           @RequestParam(value = "expireSeconds", required = false) Long expireSeconds,
                                           org.springframework.security.core.Authentication auth) {
        long expire = expireSeconds == null ? DEFAULT_EXPIRE_SECONDS : expireSeconds;
        long[] actor = currentActor(auth);
        boolean isSuper = actor[1] == 1L;
        return Map.of("url", storedFileService.downloadUrl(fileId, download, expire, isSuper ? null : actor[0]), "expireSeconds", expire);
    }

    /** 分享（复用既有 ShareLinkService）；ADMIN 只能分享自己的文件 */
    @PostMapping("/share")
    public Map<String, Object> share(@RequestBody ShareReq req, org.springframework.security.core.Authentication auth) {
        long[] actor = currentActor(auth);
        boolean isSuper = actor[1] == 1L;
        Long expire = req.getExpireSeconds();
        ShareLinkService.CreatedShare shared = storedFileService.createShare(req.getFileIds(), expire, req.getFilename(), isSuper ? null : actor[0]);
        return Map.of("code", shared.code(), "expireAt", shared.expireAt() == null ? "" : shared.expireAt());
    }

    // ===== 大文件断点续传（仅 MinIO）=====

    /** 分片上传初始化（含 ListParts 续传判断） */
    @PostMapping("/upload-multipart-init")
    public Map<String, Object> uploadMultipartInit(@RequestBody MultipartInitReq req, org.springframework.security.core.Authentication auth) {
        MultipartUploadService svc = requireMultipart();
        long[] actor = currentActor(auth);
        MultipartUploadService.InitResult r = svc.init(
                req.getParentId(), req.getOriginalName(), req.getContentType(),
                req.getFileSize(), req.getTotalChunks(), req.getContentMd5(), actor[0]);
        List<Map<String, Object>> uploaded = r.uploadedParts.stream()
                .map(p -> Map.<String, Object>of("partNumber", p.partNumber(), "etag", p.etag()))
                .toList();
        return Map.of(
                "alreadyDone", r.alreadyDone,
                "uploadId", r.uploadId == null ? "" : r.uploadId,
                "chunkKeyPrefix", r.chunkKeyPrefix == null ? "" : r.chunkKeyPrefix,
                "storageKey", r.storageKey == null ? "" : r.storageKey,
                "totalChunks", r.totalChunks,
                "uploadedParts", uploaded
        );
    }

    /** 签发某 chunk 的 presigned PUT 直链 */
    @PostMapping("/upload-multipart-sign")
    public Map<String, Object> uploadMultipartSign(@RequestBody MultipartSignReq req) {
        MultipartUploadService svc = requireMultipart();
        String url = svc.sign(req.getContentMd5(), req.getChunkId());
        return Map.of("url", url, "chunkId", req.getChunkId());
    }

    /** 合并完成（前端提交全部 part 的 chunkId+etag，MinIO 校验 part ETag） */
    @PostMapping("/upload-multipart-complete")
    public Map<String, Object> uploadMultipartComplete(@RequestBody MultipartCompleteReq req) {
        MultipartUploadService svc = requireMultipart();
        List<MultipartUploadService.PartETag> parts = req.getParts().stream()
                .map(p -> new MultipartUploadService.PartETag(p.getChunkId(), p.getEtag()))
                .toList();
        MultipartUploadService.CompleteResult res = svc.complete(req.getContentMd5(), parts);
        return Map.of("ok", true, "storageKey", res.storageKey(), "storedFileId", res.storedFileId());
    }

    private MultipartUploadService requireMultipart() {
        MultipartUploadService svc = multipartProvider.getIfAvailable();
        if (svc == null) {
            throw new IllegalArgumentException("分片上传仅支持 MinIO，且 MinIO 未启用");
        }
        return svc;
    }

    // ===== 异常映射 =====

    @ExceptionHandler(StoredFileService.ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(StoredFileService.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    // ===== DTOs =====

    /** 暴露给前端的节点视图（不包含 storageKey） */
    public record StoredFileNode(
            Long id,
            Long parentId,
            String name,
            String type,
            long size,
            String contentType,
            String originalName,
            String storageSource,
            Instant createdAt,
            Instant updatedAt,
            String status,
            String contentMd5
    ) {
        public static StoredFileNode from(StoredFile f) {
            return from(f, null);
        }
        public static StoredFileNode from(StoredFile f, String contentMd5) {
            return new StoredFileNode(
                    f.getId(), f.getParentId(), f.getName(), f.getType(), f.getSize(),
                    f.getContentType(), f.getOriginalName(), f.getStorageSource(),
                    f.getCreatedAt(), f.getUpdatedAt(), f.getStatus(), contentMd5
            );
        }
    }

    /** 可用数据源 */
    public record StoredFileSource(String id, String label) {}

    @Data
    public static class MkdirReq {
        private Long parentId;
        private String name;
    }

    @Data
    public static class UploadInitReq {
        private Long parentId;
        /** 存储源（minio/oss），前端可选并记忆 */
        private String source;
        /** 原始文件名（用于拼 storageKey 的真实文件名段） */
        private String originalName;
        /** Content-Type（OSS 预签名必须纳入，否则 PUT 签名校验失败 403） */
        private String contentType;
    }

    @Data
    public static class UploadCompleteReq {
        private Long parentId;
        private String storageSource;
        private String storageKey;
        private String originalName;
        private String contentType;
        private Long size;
    }

    @Data
    public static class ShareReq {
        private List<Long> fileIds;
        private Long expireSeconds;
        private String filename;
    }

    @Data
    public static class MultipartInitReq {
        private Long parentId;
        private String originalName;
        private String contentType;
        private long fileSize;
        private int totalChunks;
        /** 整文件 MD5（前端 SparkMD5），幂等 key */
        private String contentMd5;
    }

    @Data
    public static class MultipartSignReq {
        private String contentMd5;
        private int chunkId;
    }

    @Data
    public static class MultipartCompleteReq {
        private String contentMd5;
        private List<PartEtagDto> parts;
    }

    @Data
    public static class PartEtagDto {
        private int chunkId;
        private String etag;
    }
}
