package com.kk.file;

import com.kk.storage.service.CdnPreviewLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Public stable URL which redirects to a fresh inline media URL. */
@RestController
@RequiredArgsConstructor
public class CdnPreviewController {

    private final CdnPreviewLinkService cdnPreviewLinkService;

    @GetMapping("/file/cdn/{token}")
    public ResponseEntity<Void> preview(@PathVariable String token) {
        try {
            String url = cdnPreviewLinkService.previewUrl(token);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
