package com.kk.storage;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/** 上传 Content-Type 的统一解析规则，确保预签名、实际 PUT 与数据库记录使用同一个值。 */
public final class UploadContentTypeResolver {

    public static final String BINARY = "application/octet-stream";

    private static final Map<String, String> PREVIEWABLE_TYPES = Map.ofEntries(
            Map.entry("apng", "image/apng"),
            Map.entry("avif", "image/avif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("gif", "image/gif"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("aac", "audio/aac"),
            Map.entry("flac", "audio/flac"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("3gp", "video/3gpp"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("m4v", "video/mp4"),
            Map.entry("mkv", "video/x-matroska"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"));

    private UploadContentTypeResolver() {}

    /** 显式的非二进制兜底类型优先；否则只对可预览媒体扩展名做确定性推断。 */
    public static String resolve(String filename, String requestedContentType) {
        if (StringUtils.hasText(requestedContentType)) {
            String normalized = requestedContentType.trim().toLowerCase(Locale.ROOT);
            if (!BINARY.equals(normalized)
                    && !"binary/octet-stream".equals(normalized)
                    && !"application/binary".equals(normalized)) {
                return requestedContentType.trim();
            }
        }
        String extension = extension(filename);
        return PREVIEWABLE_TYPES.getOrDefault(extension, BINARY);
    }

    private static String extension(String filename) {
        if (!StringUtils.hasText(filename)) return "";
        String base = StorageKeys.baseName(filename.trim());
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) return "";
        return base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
