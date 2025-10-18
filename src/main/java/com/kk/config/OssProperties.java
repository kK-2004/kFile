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
    private String endpoint; // oss-cn-xxx.aliyuncs.com
    private String ak;       // access key id
    private String sk;       // access key secret
    private String bucket;   // bucket name
    private String host;     // https://bucket.oss-cn-xxx.aliyuncs.com/
}

