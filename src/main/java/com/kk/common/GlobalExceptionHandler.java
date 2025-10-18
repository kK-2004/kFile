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

import java.util.Arrays;

@ControllerAdvice
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        boolean isDev = java.util.Arrays.asList(env.getActiveProfiles()).contains("dev");
        String msg = isDev ? ("服务器错误 [" + ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()) + "]") : "服务器错误";
        try {
            if (isDev && ex.getCause() instanceof com.aliyun.oss.OSSException oe) {
                msg += String.format(" [OSS] code=%s, reqId=%s, host=%s, err=%s",
                        oe.getErrorCode(), oe.getRequestId(), oe.getHostId(), oe.getErrorMessage());
            }
        } catch (Throwable ignored) {}
        return new ResponseEntity<>(new ApiError(msg), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
