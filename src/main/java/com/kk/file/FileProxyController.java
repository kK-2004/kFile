package com.kk.file;

import com.kk.oss.OssService;
import com.kk.oss.AliOssService;
import com.kk.config.OssProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaTypeFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.io.InputStream;

@RestController
@RequiredArgsConstructor
public class FileProxyController {

    private final OssService ossService;
    private final com.kk.common.FileNameCodec fileNameCodec;

    @GetMapping("/file/oss/**")
    public ResponseEntity<?> proxy(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String marker = "/file/oss/";
        int pos = uri.indexOf(marker);
        String key = pos >= 0 ? uri.substring(pos + marker.length()) : uri;
        // decode percent-encoded path segments (e.g., %E7%BA%BF...)
        try {
            key = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        String filename = decryptFilenameFromKey(key);
        boolean forceDownload = "1".equals(request.getParameter("download"));
        boolean forceProxy = "1".equals(request.getParameter("proxy"));

        if (!forceProxy) {
            // 改为默认 302 到 OSS 公网签名直链（浏览器直接下载）
            String signed = ossService.generatePresignedUrlByKey(key, forceDownload, 300, false);
            return ResponseEntity.status(302).header(HttpHeaders.LOCATION, signed).build();
        }

        // 兼容：强制通过本站代理（会占用本站带宽）
        com.kk.oss.OssService.ObjectStat stat = ossService.statByKey(key);
        MediaType mediaType;
        if (stat.contentType != null && !stat.contentType.isBlank()) {
            try { mediaType = MediaType.parseMediaType(stat.contentType); }
            catch (Exception e) { mediaType = MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM); }
        } else {
            mediaType = MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);
        }

        String range = request.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            long total = stat.length;
            String spec = range.substring("bytes=".length()).trim();
            // 仅支持单一范围
            long start = 0, end = total - 1;
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String s1 = spec.substring(0, dash).trim();
                String s2 = spec.substring(dash + 1).trim();
                if (!s1.isEmpty()) start = Long.parseLong(s1);
                if (!s2.isEmpty()) end = Long.parseLong(s2); else end = total - 1;
            }
            if (start < 0) start = 0;
            if (end >= total) end = total - 1;
            if (start > end) start = 0;

            InputStream in = ossService.openByKeyRange(key, start, end);
            long len = end - start + 1;

            ResponseEntity.BodyBuilder builder = ResponseEntity.status(206)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total)
                    .header(HttpHeaders.ETAG, stat.eTag)
                    .header(HttpHeaders.LAST_MODIFIED, httpDate(stat.lastModified))
                    .contentLength(len);
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

        // 无 Range：整文件回传，带 Content-Length
        InputStream in = ossService.openByKey(key);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.ETAG, stat.eTag)
                .header(HttpHeaders.LAST_MODIFIED, httpDate(stat.lastModified))
                .contentLength(Math.max(0, stat.length));
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

    private String decryptFilenameFromKey(String key) {
        if (key == null || key.isEmpty()) return "file";
        int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
        String enc = slash >= 0 ? key.substring(slash + 1) : key;
        String name = fileNameCodec.decrypt(enc);
        return (name == null || name.isBlank()) ? enc : name;
    }

    private String httpDate(java.util.Date d) {
        if (d == null) return null;
        java.time.ZonedDateTime z = d.toInstant().atZone(java.time.ZoneId.of("GMT"));
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(z);
    }
}
