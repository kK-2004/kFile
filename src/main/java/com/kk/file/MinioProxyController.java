package com.kk.file;

import com.kk.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.io.InputStream;

/**
 * MinIO 下载代理，与 {@code /file/oss/**} 对称：
 * - 默认 302 到 MinIO 预签名直链（{@code minio.presigned-direct=true}）
 * - {@code ?proxy=1} 强制流式代理
 * - {@code ?download=1} 强制下载（Content-Disposition: attachment）
 *
 * 当 MinIO 未启用时，所有请求返回 404。
 */
@Slf4j
@RestController
public class MinioProxyController {

    private static final String MARKER = "/file/minio/";

    /** 通过 ObjectProvider 注入，MinIO 未启用时返回 null，避免装配期失败 */
    private final ObjectProvider<MinioStorageService> minioProvider;

    public MinioProxyController(ObjectProvider<MinioStorageService> minioProvider) {
        this.minioProvider = minioProvider;
    }

    @GetMapping("/file/minio/**")
    public ResponseEntity<?> proxy(HttpServletRequest request) {
        MinioStorageService minio = minioProvider.getIfAvailable();
        if (minio == null) {
            // MinIO 未启用，无对象可访问
            return ResponseEntity.notFound().build();
        }
        String uri = request.getRequestURI();
        int pos = uri.indexOf(MARKER);
        String key = pos >= 0 ? uri.substring(pos + MARKER.length()) : uri;
        try {
            if (key != null && !key.isEmpty()) {
                // 防止对象 key 里的字面 + 被解析为空格
                key = java.net.URLDecoder.decode(key.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}

        boolean forceDownload = "1".equals(request.getParameter("download"));
        boolean forceProxy = "1".equals(request.getParameter("proxy"));

        // 若 MinIO 配置强制走代理，忽略默认 302 分支
        if (!forceProxy && minio.isPresignedDirect()) {
            String signed = minio.downloadUrl(key, forceDownload, 300);
            return ResponseEntity.status(302).header(HttpHeaders.LOCATION, signed).build();
        }

        // 流式代理
        com.kk.storage.StorageBrowserService.Entry stat = minio.stat(key);
        String filename = com.kk.storage.StorageKeys.baseName(key);
        long size = stat != null ? Math.max(0, stat.getSize()) : -1L;
        MediaType mediaType = (stat != null && stat.getContentType() != null && !stat.getContentType().isBlank())
                ? MediaTypeFactory.getMediaType(filename).orElse(MediaType.parseMediaType(stat.getContentType()))
                : MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);

        InputStream in = minio.getObjectStream(key);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (size >= 0) builder.contentLength(size);
        if (forceDownload) {
            String ascii = filename.replaceAll("[^\\x20-\\x7E]", "_");
            String encoded;
            try { encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"); } catch (Exception e) { encoded = filename; }
            builder.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                   .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        } else {
            builder.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                   .contentType(mediaType);
        }
        return builder.body(new InputStreamResource(in));
    }
}
