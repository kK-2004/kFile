package com.kk.storage.config;

import com.kk.config.MinioProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * AWS SDK v2 S3 客户端 + Presigner，用于对 MinIO 发 S3 multipart（断点续传）。
 * 仅在 minio.enabled=true 时装配；endpoint/pathStyle/region/credentials 与 MinioClient 一致。
 * MinIO Java SDK 不暴露 multipart，故引入 AWS SDK v2（MinIO 服务端 S3 兼容）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(value = "minio.enabled", havingValue = "true")
public class MinioS3Config {

    /** MinIO 忽略 region 但 SDK 校验非空，给一个固定值 */
    private static final Region MINIO_REGION = Region.US_EAST_1;

    private final MinioProperties properties;

    @Bean
    public S3Client minioS3Client() {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(MINIO_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
        log.info("MinIO S3Client (AWS SDK v2) initialized. endpoint={}, bucket={}", properties.getEndpoint(), properties.getBucket());
        return client;
    }

    @Bean
    public S3Presigner minioS3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(MINIO_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
