package com.kk.openapi.controller;

import com.kk.openapi.OpenAppPrincipal;
import com.kk.openapi.entity.OpenApp;
import com.kk.openapi.service.OpenAppService;
import com.kk.openapi.service.OpenFileService;
import com.kk.storage.service.MultipartUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kk.util.ratelimit.RateLimit;

import java.util.List;
import java.util.Map;

/**
 * 开放文件 API（/api/open/**，Bearer appToken，ROLE_OPEN_APP）。
 * 错误语义走 GlobalExceptionHandler：IllegalArgumentException→400、NotFound→404、RateLimited→429。
 */
@RestController
@RequestMapping("/api/open")
@RequiredArgsConstructor
public class OpenFileController {

    private final OpenFileService openFileService;
    private final OpenAppService openAppService;

    /** 生成 SDK 可直接使用的绝对 CDN 地址；未配置时回退到当前请求 origin。 */
    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    /** 简单直传第一步：取预签名 PUT 直链 + 预生成 storageKey（客户端直传对象存储后调 complete） */
    @PostMapping("/uploads")
    @RateLimit(ip = true, capacity = 30, refillRate = 10)
    public OpenFileService.UploadInitResult uploadInit(@RequestBody UploadInitReq req, Authentication auth) {
        OpenApp app = currentApp(auth);
        return openFileService.initUpload(app, req.originalName(), req.contentType(), req.path(), req.source());
    }

    /** 简单直传第二步：对象 PUT 完成后确认登记（stat 校验回填真实 size） */
    @PostMapping("/uploads/complete")
    @RateLimit(ip = true, capacity = 30, refillRate = 10)
    public OpenFileService.UploadCompleteResult uploadComplete(@RequestBody UploadCompleteReq req, Authentication auth) {
        OpenApp app = currentApp(auth);
        return openFileService.completeUpload(app, req.storageKey(), req.source());
    }

    /** 分片上传第一步：初始化/续传（contentMd5 幂等，续传返回已传 part） */
    @PostMapping("/uploads/multipart/init")
    @RateLimit(ip = true, capacity = 30, refillRate = 10)
    public Map<String, Object> multipartInit(@RequestBody MultipartInitReq req, Authentication auth) {
        OpenApp app = currentApp(auth);
        MultipartUploadService.InitResult r = openFileService.multipartInit(app,
                req.originalName(), req.contentType(), req.size(), req.totalChunks(),
                req.contentMd5(), req.path(), req.source());
        return Map.of(
                "uploadId", r.uploadId == null ? "" : r.uploadId,
                "chunkKeyPrefix", r.chunkKeyPrefix == null ? "" : r.chunkKeyPrefix,
                "storageKey", r.storageKey == null ? "" : r.storageKey,
                "totalChunks", r.totalChunks,
                "fileId", r.storedFileId == null ? 0 : r.storedFileId,
                "uploadedParts", r.uploadedParts,
                "alreadyDone", r.alreadyDone);
    }

    /** 分片上传：按 chunkId 签发预签名 PUT 直链 */
    @PostMapping("/uploads/multipart/sign")
    @RateLimit(ip = true, capacity = 120, refillRate = 60)
    public Map<String, String> multipartSign(@RequestBody MultipartSignReq req, Authentication auth) {
        OpenApp app = currentApp(auth);
        return Map.of("url", openFileService.multipartSign(app, req.contentMd5(), req.chunkId()));
    }

    /** 分片上传最后一步：提交全部 part ETag，合并完成登记 */
    @PostMapping("/uploads/multipart/complete")
    @RateLimit(ip = true, capacity = 30, refillRate = 10)
    public OpenFileService.MultipartCompleteResult multipartComplete(@RequestBody MultipartCompleteReq req,
                                                                     Authentication auth) {
        OpenApp app = currentApp(auth);
        List<MultipartUploadService.PartETag> parts = req.parts() == null ? List.of()
                : req.parts().stream().map(p -> new MultipartUploadService.PartETag(p.chunkId(), p.etag())).toList();
        return openFileService.multipartComplete(app, req.contentMd5(), parts);
    }

    /** 预签名下载链接：fileId 优先，或回传 storageKey + source；expiresIn clamp [60,3600] */
    @PostMapping("/download-links")
    @RateLimit(ip = true, capacity = 60, refillRate = 10)
    public OpenFileService.DownloadLinkResult downloadLink(@RequestBody DownloadLinkReq req, Authentication auth) {
        OpenApp app = currentApp(auth);
        return openFileService.downloadLink(app, req.fileId(), req.key(), req.source(),
                req.filename(), req.expiresIn());
    }

    /** 创建图片/音频/视频的稳定 CDN 预览地址；expiresIn 省略或为 0 表示永久。 */
    @PostMapping("/cdn-links")
    @RateLimit(ip = true, capacity = 60, refillRate = 10)
    public CdnLinkResponse cdnLink(@RequestBody CdnLinkReq req, Authentication auth,
                                   HttpServletRequest request) {
        OpenApp app = currentApp(auth);
        OpenFileService.CdnLinkResult result = openFileService.cdnLink(app, req.fileId(), req.expiresIn());
        return new CdnLinkResponse(
                cdnUrl(result.token(), request), result.expiresIn(), result.permanent(), result.contentType());
    }

    private String cdnUrl(String token, HttpServletRequest request) {
        String base = StringUtils.hasText(publicBaseUrl)
                ? publicBaseUrl.trim()
                : requestOrigin(request);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/file/cdn/" + token;
    }

    private String requestOrigin(HttpServletRequest request) {
        String requestUrl = request.getRequestURL().toString();
        String requestUri = request.getRequestURI();
        int pathStart = requestUrl.indexOf(requestUri);
        return pathStart >= 0 ? requestUrl.substring(0, pathStart) : requestUrl;
    }

    private OpenApp currentApp(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof OpenAppPrincipal principal)) {
            throw new IllegalArgumentException("应用身份缺失");
        }
        return openAppService.requireApp(principal.id());
    }

    // ===== 请求 DTO =====

    public record UploadInitReq(String originalName, String contentType, Long size, String path, String source) {}

    public record UploadCompleteReq(String storageKey, String source) {}

    public record MultipartInitReq(String originalName, String contentType, long size, int totalChunks,
                                   String contentMd5, String path, String source) {}

    public record MultipartSignReq(String contentMd5, int chunkId) {}

    public record PartEtagDto(int chunkId, String etag) {}

    public record MultipartCompleteReq(String contentMd5, List<PartEtagDto> parts) {}

    public record DownloadLinkReq(Long fileId, String key, String source, String filename, Long expiresIn) {}

    public record CdnLinkReq(Long fileId, Long expiresIn) {}

    public record CdnLinkResponse(String url, long expiresIn, boolean permanent, String contentType) {}
}
