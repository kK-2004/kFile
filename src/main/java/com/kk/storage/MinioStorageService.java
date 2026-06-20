package com.kk.storage;

import com.kk.config.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 数据源实现。仅在 minio.enabled=true 时装配。
 * 职责：按 storageKey 上传/删除/取元数据/生成预签名（虚拟树逻辑由 StoredFileService 负责）。
 */
@Slf4j
@Service("minioStorageService")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "minio.enabled", havingValue = "true")
public class MinioStorageService implements StorageBrowserService {

    private static final String SOURCE_ID = "minio";

    private final MinioClient minioClient;
    private final MinioProperties properties;

    @Override
    public String sourceId() { return SOURCE_ID; }

    @Override
    public String sourceLabel() { return "MinIO"; }

    @Override
    public void delete(String storageKey) {
        if (!StringUtils.hasText(storageKey)) return;
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(storageKey)
                    .build());
        } catch (Exception e) {
            log.warn("MinIO 删除对象失败: key={}, msg={}", storageKey, e.getMessage());
        }
    }

    @Override
    public String downloadUrl(String storageKey, boolean download, long expireSeconds, String downloadFilename) {
        if (!properties.isPresignedDirect()) {
            return proxyUrl(storageKey) + (download ? "?download=1" : "");
        }
        try {
            GetPresignedObjectUrlArgs.Builder b = GetPresignedObjectUrlArgs.builder()
                    .bucket(properties.getBucket())
                    .object(storageKey)
                    .method(Method.GET)
                    .expiry((int) Math.max(60, expireSeconds), TimeUnit.SECONDS);
            if (download) {
                // 优先用调用方指定的真实文件名（DB originalName），否则回退到 key 末尾段
                String filename = StringUtils.hasText(downloadFilename) ? downloadFilename : StorageKeys.baseName(storageKey);
                String ascii = filename.replaceAll("[^\\x20-\\x7E]", "_");
                String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
                String dispo = "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
                b.extraQueryParams(Map.of("response-content-disposition", dispo));
            }
            return minioClient.getPresignedObjectUrl(b.build());
        } catch (Exception e) {
            throw new IllegalStateException("生成 MinIO 下载直链失败", e);
        }
    }

    /**
     * 生成浏览器直传 PUT 预签名直链。前端按此 URL 直接 PUT 文件到 MinIO，不经过后端。
     * 与 OSS {@code generatePresignedPutUrlByKey} 对称。
     */
    public String presignedPutUrl(String storageKey, long expireSeconds, String contentType) {
        try {
            GetPresignedObjectUrlArgs.Builder b = GetPresignedObjectUrlArgs.builder()
                    .bucket(properties.getBucket())
                    .object(storageKey)
                    .method(Method.PUT)
                    .expiry((int) Math.max(60, expireSeconds), TimeUnit.SECONDS);
            // MinIO 预签名 URL 不需要把 Content-Type 写进签名参数；浏览器 PUT 时带对应 header 即可
            return minioClient.getPresignedObjectUrl(b.build());
        } catch (Exception e) {
            throw new IllegalStateException("生成 MinIO 直传直链失败", e);
        }
    }

    @Override
    public Entry stat(String storageKey) {
        try {
            StatObjectResponse resp = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(storageKey)
                    .build());
            return new Entry(StorageKeys.baseName(storageKey), resp.size(), toDate(resp.lastModified()), storageKey, resp.contentType());
        } catch (ErrorResponseException e) {
            if (isNotFoundOrDenied(e)) return null;
            throw new IllegalStateException("获取 MinIO 对象元数据失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("获取 MinIO 对象元数据失败", e);
        }
    }

    /** 流式下载（供 /file/minio/** 代理使用） */
    public InputStream getObjectStream(String storageKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(storageKey)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("读取 MinIO 对象失败", e);
        }
    }

    public String proxyUrl(String storageKey) {
        return "/file/" + SOURCE_ID + "/" + storageKey;
    }

    public boolean isPresignedDirect() { return properties.isPresignedDirect(); }

    /** NoSuchKey（对象不存在）或 AccessDenied（无权访问，部分 MinIO 对不存在对象返回此码）时视为对象不可用 */
    private boolean isNotFoundOrDenied(ErrorResponseException e) {
        if (e.errorResponse() == null) return false;
        String code = e.errorResponse().code();
        return "NoSuchKey".equals(code) || "AccessDenied".equals(code) || "NoSuchObject".equals(code);
    }

    private static Date toDate(ZonedDateTime z) {
        return z == null ? null : Date.from(z.toInstant());
    }
}
