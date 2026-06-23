package com.kk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * kMessage 客户端配置。
 *
 * enabled=false（默认）时所有消息发送短路，不阻断业务。
 * 注意：channelInstanceId / groupId 由 SUPER 在后台 config 维护，不在 yml 配置，
 *       避免出现两个真相来源；此 Properties 仅承载 endpoint/appKey/appSecret 等部署期常量。
 */
@Data
@ConfigurationProperties(prefix = "app.kmessage")
public class KMessageProperties {

    /** 总开关；false 时全部短路 */
    private boolean enabled;

    /** kMessage 服务端地址 */
    private String endpoint;

    /** 应用 key */
    private String appKey;

    /** 应用 secret */
    private String appSecret;
}
