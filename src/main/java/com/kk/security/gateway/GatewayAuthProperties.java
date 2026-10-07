package com.kk.security.gateway;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 统一认证网关接入配置（app.gateway.*）。
 *
 * <p>kfile 作为网关 OIDC 应用（client_id 默认 {@code kfile}）：前端经网关登录拿 access token，
 * 接口经网关代理（authMode=APP，Authorization 原样透传），由本服务用网关 JWKS 自行验签。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.gateway")
public class GatewayAuthProperties {

    /** 是否接受网关签发的 Bearer token；关闭时只有本地会话（应急登录）。 */
    private boolean enabled = false;

    /** 网关 issuer，如 https://gw.ksite.xin（与 token iss 完全一致）。 */
    private String issuer;

    /** JWKS 地址；缺省为 {@code <issuer>/auth/.well-known/jwks}。 */
    private String jwkSetUri;

    /** 本应用在网关登记的 client_id（token aud）。 */
    private String clientId = "kfile";

    /** 踢下线回调 HMAC secret（网关管理端应用详情中生成）。 */
    private String callbackSecret;

    public String resolvedJwkSetUri() {
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            return jwkSetUri;
        }
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        return base + "/auth/.well-known/jwks";
    }
}
