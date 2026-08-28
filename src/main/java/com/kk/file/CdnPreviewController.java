package com.kk.file;

import com.kk.storage.service.CdnPreviewLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Public stable URL which redirects to a fresh inline media URL. */
@RestController
@RequiredArgsConstructor
public class CdnPreviewController {

    private static final MediaType JSON_UTF_8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final CdnPreviewLinkService cdnPreviewLinkService;

    @GetMapping("/file/cdn/{token}")
    public ResponseEntity<?> preview(@PathVariable String token) {
        try {
            String url = cdnPreviewLinkService.previewUrl(token);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? "CDN 链接不可用"
                    : e.getMessage();
            HttpStatus status = message.contains("已过期")
                    ? HttpStatus.GONE
                    : HttpStatus.NOT_FOUND;
            return ResponseEntity.status(status)
                    .contentType(JSON_UTF_8)
                    .body(Map.of("message", message));
        }
    }
}
