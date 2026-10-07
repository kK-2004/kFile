package com.kk.storage;

import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.kk.config.OssProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliOssBrowserServiceTest {
    @Test
    void signsCustomDomainWithoutProxyingFileBytes() throws Exception {
        OssProperties properties = new OssProperties();
        properties.setBucket("test-bucket");
        properties.setEndpoint("https://oss-cn-beijing.aliyuncs.com");
        properties.setAk("test-access-key");
        properties.setSk("test-secret");
        properties.setPreviewEndpoint("https://media.example.com");
        AliOssBrowserService service = new AliOssBrowserService(properties);
        try {
            service.init();
            java.net.URI url = java.net.URI.create(service.previewUrl("files/photo.png", 300, "photo.png", "image/png"));
            assertThat(url.getHost()).isEqualTo("media.example.com");
            assertThat(url.getPath()).isEqualTo("/files/photo.png");
            assertThat(url.getQuery()).contains("Signature=", "response-content-disposition=inline");
            assertThat(url.getQuery()).doesNotContain("proxy=", "response-content-type");
        } finally {
            service.destroy();
        }
    }

    @Test
    void missingCustomDomainDoesNotFallBackToServerProxy() {
        AliOssBrowserService service = new AliOssBrowserService(new OssProperties());
        assertThatThrownBy(() -> service.previewUrl("photo.png", 300, "photo.png", "image/png"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("oss.preview-endpoint");
    }

    @Test
    void previewRequestDoesNotOverrideContentTypeForOssGet() {
        OssProperties properties = new OssProperties();
        properties.setBucket("test-bucket");
        AliOssBrowserService service = new AliOssBrowserService(properties);

        GeneratePresignedUrlRequest request =
                service.buildPreviewRequest("files/example.pdf", 300, "example.pdf");

        assertThat(request.getBucketName()).isEqualTo("test-bucket");
        assertThat(request.getKey()).isEqualTo("files/example.pdf");
        assertThat(request.getQueryParameter())
                .containsKey("response-content-disposition")
                .doesNotContainKey("response-content-type");
    }
}
