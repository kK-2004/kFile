package com.kk.common;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

@ControllerAdvice
@lombok.extern.slf4j.Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex) {
        return new ResponseEntity<>(new ApiError(ex.getMessage()), HttpStatus.BAD_REQUEST);
    }

    private final Environment env;

    public GlobalExceptionHandler(Environment env) { this.env = env; }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(IllegalStateException ex) {
        String msg = ex.getMessage();
        boolean isDev = Arrays.asList(env.getActiveProfiles()).contains("dev");
        if (isDev && ex.getCause() != null) {
            Throwable cause = ex.getCause();
            try {
                if (cause instanceof com.aliyun.oss.OSSException oe) {
                    String extra = String.format(" [OSS] code=%s, reqId=%s, host=%s, err=%s",
                            oe.getErrorCode(), oe.getRequestId(), oe.getHostId(), oe.getErrorMessage());
                    msg = msg + extra;
                } else {
                    msg = msg + " [cause=" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + "]";
                }
            } catch (Throwable ignored) { }
        }
        return new ResponseEntity<>(new ApiError(msg), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return new ResponseEntity<>(new ApiError("Validation failed"), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(AccessDeniedException ex) {
        return new ResponseEntity<>(new ApiError("无权限"), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuth(AuthenticationException ex) {
        return new ResponseEntity<>(new ApiError("用户名或密码错误"), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return new ResponseEntity<>(new ApiError("上传文件过大，请压缩或分批上传"), HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        // 保留 ResponseStatusException 的原始状态码与 reason（OAuth 控制器用它抛 400/401/404 等业务错误）
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return new ResponseEntity<>(new ApiError(ex.getReason()), status);
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleClientDisconnect(org.springframework.web.context.request.async.AsyncRequestNotUsableException ex) {
        // 客户端断连（SSE 流关闭、Broken pipe）：MCP Streamable HTTP 长连接的正常生命周期，
        // 静默丢弃，不记录堆栈，避免日志刷屏。
        log.debug("Client disconnected from async request: {}", ex.getMessage());
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotWritableException.class)
    public ResponseEntity<Void> handleSseNotWritable(org.springframework.http.converter.HttpMessageNotWritableException ex) {
        // SSE 异步流断开后，试图写 ApiError 到已关闭/非 JSON 流：静默丢弃。
        log.debug("Response not writable (likely SSE client gone): {}", ex.getMessage());
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<Void> handleIo(java.io.IOException ex) {
        // Broken pipe / ClientAbortException：客户端已断开，静默丢弃。
        log.debug("IOException (client likely disconnected): {}", ex.getMessage());
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        boolean isDev = java.util.Arrays.asList(env.getActiveProfiles()).contains("dev");
        // 始终记录未处理异常，便于线上排查
        log.error("Unhandled exception in controller", ex);
        String msg = isDev ? ("服务器错误 [" + ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()) + "]") : "服务器错误";
        try {
            if (isDev && ex.getCause() instanceof com.aliyun.oss.OSSException oe) {
                msg += String.format(" [OSS] code=%s, reqId=%s, host=%s, err=%s",
                        oe.getErrorCode(), oe.getRequestId(), oe.getHostId(), oe.getErrorMessage());
            }
        } catch (Throwable ignored) {}
        return new ResponseEntity<>(new ApiError(msg), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(com.kk.util.ratelimit.RateLimitedException.class)
    public ResponseEntity<ApiError> handleRateLimited(com.kk.util.ratelimit.RateLimitedException ex) {
        return new ResponseEntity<>(new ApiError(ex.getMessage()), HttpStatus.TOO_MANY_REQUESTS);
    }
}
