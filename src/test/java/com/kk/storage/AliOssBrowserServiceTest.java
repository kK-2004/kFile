package com.kk.storage;

import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.kk.config.OssProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AliOssBrowserServiceTest {

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
