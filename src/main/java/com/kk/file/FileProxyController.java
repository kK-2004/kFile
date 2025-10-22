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
    public ResponseEntity<InputStreamResource> proxy(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String marker = "/file/oss/";
        int pos = uri.indexOf(marker);
        String key = pos >= 0 ? uri.substring(pos + marker.length()) : uri;
        // decode percent-encoded path segments (e.g., %E7%BA%BF...)
        try {
            key = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        String filename = decryptFilenameFromKey(key);
        MediaType mediaType = MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);
        boolean forceDownload = "1".equals(request.getParameter("download"));
        InputStream in = ossService.openByKey(key);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (forceDownload) {
            // Set Content-Disposition as attachment with UTF-8 filename* fallback
            String ascii = filename.replaceAll("[^\\x20-\\x7E]", "_");
            String encoded;
            try {
                encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            } catch (Exception e) {
                encoded = filename;
            }
            builder.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
            builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        } else {
            builder.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"");
            builder.contentType(mediaType);
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
}
