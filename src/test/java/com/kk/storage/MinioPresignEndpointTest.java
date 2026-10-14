package com.kk.storage;

import com.kk.config.MinioProperties;
import com.kk.storage.config.MinioConfig;
import com.kk.storage.config.MinioPresignClient;
import com.kk.storage.config.MinioS3Config;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 对外端点只影响签发给浏览器的直链；服务端客户端始终使用内网 endpoint。签名全在本地完成，无网络依赖。 */
class MinioPresignEndpointTest {

    private static MinioProperties properties(String publicEndpoint) {
        MinioProperties properties = new MinioProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://10.0.0.5:9000");
        properties.setPublicEndpoint(publicEndpoint);
        properties.setAccessKey("test-access");
        properties.setSecretKey("test-secret-key");
        properties.setBucket("k-file");
        return properties;
    }

    private static MinioStorageService service(MinioProperties properties) {
        MinioClient internal = MinioClient.builder().endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey()).build();
        MinioPresignClient presign = new MinioConfig(properties).minioPresignClient();
        return new MinioStorageService(internal, properties, presign);
    }

    @Test
    void 直传与下载直链使用对外端点且中文对象键正确编码() {
        MinioStorageService service = service(properties("https://minio.example.com"));

        URI put = URI.create(service.presignedPutUrl("开放应用/kwiki/a/财报.pdf", 600, "application/pdf"));
        assertThat(put.getScheme()).isEqualTo("https");
        assertThat(put.getHost()).isEqualTo("minio.example.com");
        assertThat(put.getPath()).isEqualTo("/k-file/开放应用/kwiki/a/财报.pdf");
        assertThat(put.getRawQuery()).contains("X-Amz-Signature=", "X-Amz-Credential=test-access%2F");
        assertThat(put.getRawQuery()).contains("%2Fus-east-1%2Fs3%2Faws4_request");

        URI download = URI.create(service.downloadUrl("开放应用/kwiki/a/财报.pdf", true, 300, "财报.pdf"));
        assertThat(download.getHost()).isEqualTo("minio.example.com");
        URI preview = URI.create(service.previewUrl("开放应用/kwiki/a/图.png", 300, "图.png", "image/png"));
        assertThat(preview.getHost()).isEqualTo("minio.example.com");
    }

    @Test
    void 未配置对外端点时回落到endpoint() {
        MinioStorageService service = service(properties(null));
        URI put = URI.create(service.presignedPutUrl("k/a.png", 600, "image/png"));
        assertThat(put.getHost()).isEqualTo("10.0.0.5");
        assertThat(put.getPort()).isEqualTo(9000);
        assertThat(properties(" ").effectivePublicEndpoint()).isEqualTo("http://10.0.0.5:9000");
    }

    @Test
    void 分片直链使用对外端点_服务端S3客户端仍用内网endpoint() {
        MinioProperties properties = properties("https://minio.example.com");
        MinioS3Config config = new MinioS3Config(properties);
        try (var presigner = config.minioS3Presigner(); var client = config.minioS3Client()) {
            var presigned = presigner.presignUploadPart(UploadPartPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(10))
                    .uploadPartRequest(UploadPartRequest.builder().bucket("k-file").key("k/a.bin")
                            .uploadId("u1").partNumber(1).build())
                    .build());
            assertThat(presigned.url().getHost()).isEqualTo("minio.example.com");
            assertThat(client.serviceClientConfiguration().endpointOverride())
                    .hasValue(URI.create("http://10.0.0.5:9000"));
        }
    }
}
