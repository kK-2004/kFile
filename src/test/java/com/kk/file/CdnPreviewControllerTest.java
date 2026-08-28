package com.kk.file;

import com.kk.storage.service.CdnPreviewLinkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CdnPreviewControllerTest {

    @Mock
    private CdnPreviewLinkService cdnPreviewLinkService;

    @Test
    void returnsExpiredResponseInsteadOfEmptyDownload() {
        CdnPreviewController controller = new CdnPreviewController(cdnPreviewLinkService);
        when(cdnPreviewLinkService.previewUrl("expired-token"))
                .thenThrow(new IllegalArgumentException("CDN 链接已过期"));

        ResponseEntity<?> response = controller.preview("expired-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        assertThat(response.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) response.getBody()).get("message"))
                .isEqualTo("CDN 链接已过期");
    }

    @Test
    void keepsMissingLinkAsJsonNotFoundResponse() {
        CdnPreviewController controller = new CdnPreviewController(cdnPreviewLinkService);
        when(cdnPreviewLinkService.previewUrl("missing-token"))
                .thenThrow(new IllegalArgumentException("CDN 链接不存在"));

        ResponseEntity<?> response = controller.preview("missing-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((Map<?, ?>) response.getBody()).get("message"))
                .isEqualTo("CDN 链接不存在");
    }
}
