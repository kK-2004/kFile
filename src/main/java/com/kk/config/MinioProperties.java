package com.kk.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 数据源配置（与 oss.* 并列，互不影响）。
 * 默认 enabled=false，未启用时不装配任何 MinIO bean。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {
    /** 是否启用 MinIO 数据源；未启用则不装配 MinIO 相关 bean */
    private boolean enabled = false;
    /** MinIO 服务地址，如 http://localhost:9000；服务端自身的读写、stat、删除都走这个地址，建议用内网地址 */
    private String endpoint;
    /**
     * 对外地址（可选），如 https://minio.example.com：只用于签发给浏览器/外部调用方的预签名直链
     * （直传 PUT、分片 PUT、下载与预览）。服务端请求经 CDN/反向代理转发时可能被改写而导致
     * SignatureDoesNotMatch，因此服务端走内网 endpoint、对外直链走此地址。未配置时与 endpoint 相同。
     */
    private String publicEndpoint;
    private String accessKey;
    private String secretKey;
    /** bucket 名称，启动时会确保存在 */
    private String bucket;
    /** 对象 key 的根前缀，用于隔离命名空间（可选） */
    private String prefix;
    /** 是否允许返回 MinIO 预签名直链；false 时强制走 /file/minio/** 代理 */
    private boolean presignedDirect = true;

    /** 签发预签名直链使用的地址：配置了 publicEndpoint 时用它，否则回落到 endpoint。 */
    public String effectivePublicEndpoint() {
        return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint.trim();
    }
}
