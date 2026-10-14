package com.kk.storage.config;

import com.kk.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端装配；仅在 minio.enabled=true 时生效。
 * 启动时确保配置 bucket 存在（不存在则创建），与 drug-store MinioConfig 一致。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(value = "minio.enabled", havingValue = "true")
public class MinioConfig {

    /** MinIO 未显式配置 region 时的默认值；签名中的 region 必须与服务端一致 */
    static final String PRESIGN_REGION = "us-east-1";

    private final MinioProperties properties;

    @Bean
    public MinioClient minioClient() {
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        ensureBucket(client, properties.getBucket());
        log.info("MinioClient initialized. endpoint={}, bucket={}", properties.getEndpoint(), properties.getBucket());
        return client;
    }

    /** 预签名专用客户端：对外端点 + 固定 region（MinIO 默认 us-east-1），避免签名前探测 region。 */
    @Bean
    public MinioPresignClient minioPresignClient() {
        String publicEndpoint = properties.effectivePublicEndpoint();
        MinioClient client = MinioClient.builder()
                .endpoint(publicEndpoint)
                .region(PRESIGN_REGION)
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        log.info("MinIO presign client initialized. publicEndpoint={}", publicEndpoint);
        return new MinioPresignClient(client);
    }

    private void ensureBucket(MinioClient client, String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("minio.bucket 不能为空（minio.enabled=true 时必须配置）");
        }
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            throw new IllegalStateException("校验/创建 MinIO bucket 失败: " + bucket, e);
        }
    }
}
