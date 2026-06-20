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
    /** MinIO 服务地址，如 http://localhost:9000 */
    private String endpoint;
    private String accessKey;
    private String secretKey;
    /** bucket 名称，启动时会确保存在 */
    private String bucket;
    /** 对象 key 的根前缀，用于隔离命名空间（可选） */
    private String prefix;
    /** 是否允许返回 MinIO 预签名直链；false 时强制走 /file/minio/** 代理 */
    private boolean presignedDirect = true;
}
