package com.kk.storage;

import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件管理存储 key 生成与文件名处理工具。
 * <p>
 * storageKey 规则完全对齐用户端 OSS 直传（{@code SubmissionController.directInit}）：
 * <pre>
 *   storageKey = &lt;rootPrefix&gt;/&lt;folderPath&gt;/&lt;yyyyMMddHHmmssSSS-uuid8&gt;/&lt;真实文件名.ext&gt;
 * </pre>
 * - {@code rootPrefix}：对象存储根前缀（如 minio.prefix / oss.prefix）
 * - {@code folderPath}：虚拟文件夹路径（从根到当前目录的名字链，不含根前缀），根目录为空
 * - {@code yyyyMMddHHmmssSSS-uuid8}：一次性子目录，防止同名文件覆盖
 * - 真实文件名：baseName 剥离路径与 {@code ..} 后的纯文件名
 * <p>
 * 下载/分享时按 storageKey 取对象，Content-Disposition 用 originalName 还原真实文件名。
 */
public final class StorageKeys {

    private StorageKeys() {}

    /** 剥离路径前缀与 `..`，仅保留安全文件名；空则返回 "file" */
    public static String baseName(String filename) {
        if (!StringUtils.hasText(filename)) return "file";
        String fn = filename;
        int slash = Math.max(fn.lastIndexOf('/'), fn.lastIndexOf('\\'));
        if (slash >= 0) fn = fn.substring(slash + 1);
        fn = fn.replace("..", "");
        return StringUtils.hasText(fn) ? fn : "file";
    }

    /**
     * 生成浏览器直传 storageKey，对齐 OSS 用户端直传规则。
     *
     * @param rootPrefix   对象存储根前缀（minio.prefix / oss.prefix），可为空
     * @param folderPath   虚拟文件夹路径（根目录为 "" 或 null；已有末尾 "/" 也兼容）
     * @param originalName 原始文件名（取 baseName 作真实文件名）
     * @return 形如 {@code <rootPrefix>/<folderPath>/<timestamp-uuid>/<真实文件名.ext>}
     */
    public static String buildDirectUploadKey(String rootPrefix, String folderPath, String originalName) {
        String uniq = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
                .format(ZonedDateTime.now(ZoneId.of("UTC")))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        String safeName = baseName(originalName);

        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(rootPrefix)) {
            sb.append(rootPrefix);
            if (!rootPrefix.endsWith("/")) sb.append('/');
        }
        // 虚拟文件夹路径（不含根前缀，根目录为空）
        if (StringUtils.hasText(folderPath)) {
            sb.append(folderPath);
            if (!folderPath.endsWith("/")) sb.append('/');
        }
        // 一次性子目录（防同名覆盖）
        sb.append(uniq).append('/');
        // 真实文件名
        sb.append(safeName);
        return sb.toString();
    }

    /**
     * 生成一次性 timestamp-uuid（防同名覆盖），供直传/分片共用。
     */
    public static String timestampUuid() {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
                .format(ZonedDateTime.now(ZoneId.of("UTC")))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 生成分片上传的 chunk 路径前缀（不含 chunkId 后缀）。
     * 形如 {@code <rootPrefix>/_chunks/<folderPath>/<timestamp-uuid>/<真实文件名>}，
     * 前端追加 {@code -<chunkId>} 即得单个 chunk 对象 key。
     * 同一文件所有 chunk 共用同一个 timestampUuid（init 时生成一次）。
     */
    public static String buildChunkKeyPrefix(String rootPrefix, String folderPath, String timestampUuid, String originalName) {
        String safeName = baseName(originalName);
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(rootPrefix)) {
            sb.append(rootPrefix);
            if (!rootPrefix.endsWith("/")) sb.append('/');
        }
        sb.append(CHUNKS_DIR).append('/');
        if (StringUtils.hasText(folderPath)) {
            sb.append(folderPath);
            if (!folderPath.endsWith("/")) sb.append('/');
        }
        sb.append(timestampUuid).append('/').append(safeName);
        return sb.toString();
    }

    /**
     * 生成合并后最终对象 key（与 chunkKeyPrefix 共用 timestampUuid）。
     * 形如 {@code <rootPrefix>/<folderPath>/<timestamp-uuid>/<真实文件名.ext>}，对齐 OSS 直传规则。
     */
    public static String buildMergedStorageKey(String rootPrefix, String folderPath, String timestampUuid, String originalName) {
        String safeName = baseName(originalName);
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(rootPrefix)) {
            sb.append(rootPrefix);
            if (!rootPrefix.endsWith("/")) sb.append('/');
        }
        if (StringUtils.hasText(folderPath)) {
            sb.append(folderPath);
            if (!folderPath.endsWith("/")) sb.append('/');
        }
        sb.append(timestampUuid).append('/').append(safeName);
        return sb.toString();
    }

    /** 分片专用文件夹名常量 */
    public static final String CHUNKS_DIR = "_chunks";

    /**
     * 归一化前缀：补全并以 {@code /} 结尾；空返回 ""。
     * 与 {@code AliOssService.normalizePrefix} 行为一致。
     */
    public static String normalizePrefix(String prefix, String rootPrefix) {
        String p = prefix == null ? "" : prefix;
        if (StringUtils.hasText(rootPrefix)) {
            String pre = rootPrefix;
            if (!pre.endsWith("/")) pre += "/";
            p = pre + p;
        }
        if (p.isEmpty()) return "";
        if (!p.endsWith("/")) p += "/";
        return p;
    }

    /** 校验文件夹/文件名是否包含路径穿越片段；安全时返回归一化后的纯名称，否则抛异常 */
    public static String safeName(String name) {
        if (!StringUtils.hasText(name)) throw new IllegalArgumentException("名称不能为空");
        String trimmed = name.trim();
        String cleaned = baseName(trimmed);
        if (cleaned.contains("/") || cleaned.contains("\\") || cleaned.contains("..")) {
            throw new IllegalArgumentException("非法的名称: " + name);
        }
        return cleaned;
    }
}
