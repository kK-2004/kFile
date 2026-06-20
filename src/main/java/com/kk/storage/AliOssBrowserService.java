package com.kk.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.HttpMethod;
import com.kk.config.OssProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;

/**
 * OSS 数据源实现（复用 oss.* 配置，独立构造 OSSClient，不改动 OssService/AliOssService 既有签名）。
 * 职责：按 storageKey 上传/删除/取元数据/生成预签名（虚拟树逻辑由 StoredFileService 负责）。
 */
@Slf4j
@Service("aliOssBrowserService")
@RequiredArgsConstructor
public class AliOssBrowserService implements StorageBrowserService {

    private static final String SOURCE_ID = "oss";

    private final OssProperties properties;
    private OSS ossClient;

    @PostConstruct
    public void init() {
        com.aliyun.oss.ClientBuilderConfiguration conf = new com.aliyun.oss.ClientBuilderConfiguration();
        conf.setProtocol(com.aliyun.oss.common.comm.Protocol.HTTPS);
        conf.setConnectionTimeout(10000);
        conf.setSocketTimeout(120000);
        conf.setMaxConnections(128);
        conf.setRequestTimeout(120000);
        this.ossClient = new OSSClientBuilder().build(properties.getEndpoint(), properties.getAk(), properties.getSk(), conf);
        log.info("AliOssBrowserService initialized. endpoint={}, bucket={}", properties.getEndpoint(), properties.getBucket());
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) ossClient.shutdown();
    }

    @Override
    public String sourceId() { return SOURCE_ID; }

    @Override
    public String sourceLabel() { return "OSS"; }

    @Override
    public void delete(String storageKey) {
        if (!StringUtils.hasText(storageKey)) return;
        try {
            ossClient.deleteObject(properties.getBucket(), storageKey);
        } catch (Exception e) {
            log.warn("OSS 删除对象失败: key={}, msg={}", storageKey, e.getMessage());
        }
    }

    @Override
    public String downloadUrl(String storageKey, boolean download, long expireSeconds, String downloadFilename) {
        Date expiration = new Date(System.currentTimeMillis() + Math.max(60, expireSeconds) * 1000);
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(properties.getBucket(), storageKey, HttpMethod.GET);
        req.setExpiration(expiration);
        if (download) {
            // 优先用调用方指定的真实文件名（DB originalName），否则回退到 key 末尾段
            String filename = StringUtils.hasText(downloadFilename) ? downloadFilename : StorageKeys.baseName(storageKey);
            String ascii = filename.replaceAll("[^\\x20-\\x7E]", "_");
            String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            String dispo = "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
            req.addQueryParameter("response-content-disposition", dispo);
        }
        return ossClient.generatePresignedUrl(req).toString();
    }

    @Override
    public String presignedPutUrl(String storageKey, long expireSeconds, String contentType) {
        Date expiration = new Date(System.currentTimeMillis() + Math.max(60, expireSeconds) * 1000);
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(properties.getBucket(), storageKey, HttpMethod.PUT);
        req.setExpiration(expiration);
        if (contentType != null && !contentType.isBlank()) {
            // 将 Content-Type 纳入签名请求头（与 AliOssService.generatePresignedPutUrlByKey 一致）
            req.setContentType(contentType);
        }
        return ossClient.generatePresignedUrl(req).toString();
    }

    @Override
    public Entry stat(String storageKey) {
        try {
            ObjectMetadata meta = ossClient.getObjectMetadata(properties.getBucket(), storageKey);
            return new Entry(StorageKeys.baseName(storageKey), meta.getContentLength(), meta.getLastModified(), storageKey, meta.getContentType());
        } catch (OSSException e) {
            if ("NoSuchKey".equals(e.getErrorCode())) return null;
            throw new IllegalStateException("获取 OSS 对象元数据失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("获取 OSS 对象元数据失败", e);
        }
    }

    public String proxyUrl(String storageKey) {
        return "/file/" + SOURCE_ID + "/" + storageKey;
    }
}
