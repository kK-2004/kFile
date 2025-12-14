package com.kk.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "oss")
public class OssProperties {
    private String type;     // ali
    private String prefix;   // directory prefix in bucket
    private String endpoint; // oss-cn-xxx.aliyuncs.com 用于上传
    private String internalEndpoint; // oss-cn-xxx-internal.aliyuncs.com 用于下载
    private String ak;       // access key id
    private String sk;       // access key secret
    private String bucket;   // bucket name
    private String host;     // https://bucket.oss-cn-xxx.aliyuncs.com/

    // legacy: 用于解密历史 OSS 对象 key 中的加密文件名（不再用于新上传）
    // 配置项保持兼容：oss.filename-secret
    private String filenameSecret;
}
