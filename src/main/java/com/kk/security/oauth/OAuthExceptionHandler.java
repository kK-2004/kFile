package com.kk.security.oauth;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * OAuth 端点的错误响应格式化（RFC 6749 / RFC 7591）。
 *
 * <p>OAuth 客户端（如 WorkBuddy/Codex/Claude）按规范解析错误响应，期望：
 * <pre>{"error": "invalid_grant", "error_description": "..."}</pre>
 * 而非全局 {@code ApiError} 的 {@code {"message":"..."}}。本 advice 限定在
 * {@code com.kk.security.oauth} 包，用更高优先级覆盖全局处理器，保证 OAuth 端点返回标准格式。
 *
 * <p>约定：控制器抛 {@link ResponseStatusException}，{@code reason} 即作为 {@code error_description}；
 * error code 由 HTTP 状态码映射（400→invalid_request，401→invalid_token，403→insufficient_scope，404→not_found）。
 */
@RestControllerAdvice(basePackages = "com.kk.security.oauth")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OAuthExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleOAuthError(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errorCodeFor(status));
        body.put("error_description", ex.getReason() != null ? ex.getReason() : status.getReasonPhrase());
        // 强制 UTF-8：中文 error_description 默认会用 ISO-8859-1 编码导致乱码
        MediaType contentType = new MediaType(MediaType.APPLICATION_JSON, java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.status(status).contentType(contentType).body(body);
    }

    private static String errorCodeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "invalid_request";
            case UNAUTHORIZED -> "invalid_token";
            case FORBIDDEN -> "insufficient_scope";
            case NOT_FOUND -> "not_found";
            case TOO_MANY_REQUESTS -> "slow_down";
            default -> "server_error";
        };
    }
}
