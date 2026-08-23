package com.kk.sdk;

/**
 * 开放 API 调用异常：status 为 HTTP 状态码（-1 表示传输层失败），message 来自服务端
 * {@code ApiError{message}}（解析失败时为兜底文案；401 附加 token 轮换/禁用提示）。
 */
public class ContentCenterException extends RuntimeException {

    private final int status;

    public ContentCenterException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ContentCenterException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** HTTP 状态码；-1 表示连接失败/中断等传输层错误 */
    public int getStatus() {
        return status;
    }
}
