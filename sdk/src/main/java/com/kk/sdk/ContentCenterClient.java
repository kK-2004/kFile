package com.kk.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.sdk.model.DownloadLinkResponse;
import com.kk.sdk.model.CdnLinkResponse;
import com.kk.sdk.model.MultipartCompleteResponse;
import com.kk.sdk.model.MultipartInitResponse;
import com.kk.sdk.model.UploadCompleteResponse;
import com.kk.sdk.model.UploadInitResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 内容中心开放 API 官方 Java 客户端。
 *
 * <p>零 Spring 依赖：HTTP 走 JDK {@link HttpClient}，JSON 走 Jackson。
 * 所有对内容中心的请求自动携带 {@code Authorization: Bearer <appToken>}；
 * 文件字节经预签名 URL 直传对象存储，不经过内容中心服务端。
 *
 * <pre>{@code
 * ContentCenterClient client = ContentCenterClient.builder()
 *         .baseUrl("https://content-center.example.com")
 *         .appToken("kapp_xxx")
 *         .build();
 * UploadResult result = client.upload(Path.of("report.pdf"),
 *         UploadOptions.defaults().path("avatars").contentType("application/pdf"));
 * DownloadLink link = client.getDownloadLink(DownloadLinkRequest.ofFileId(result.fileId()));
 * }</pre>
 */
public final class ContentCenterClient {

    public static final int DEFAULT_PART_SIZE = 5 * 1024 * 1024;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String appToken;
    private final Duration connectTimeout;
    private final Duration requestTimeout;

    private ContentCenterClient(Builder b) {
        this.baseUrl = trimTrailingSlash(b.baseUrl);
        this.appToken = b.appToken;
        this.connectTimeout = b.connectTimeout;
        this.requestTimeout = b.requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 构建器：baseUrl / appToken 必填，超时可选 */
    public static final class Builder {
        private String baseUrl;
        private String appToken;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofMinutes(10);

        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder appToken(String appToken) { this.appToken = appToken; return this; }
        public Builder connectTimeout(Duration d) { this.connectTimeout = d; return this; }
        public Builder requestTimeout(Duration d) { this.requestTimeout = d; return this; }

        public ContentCenterClient build() {
            if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl 不能为空");
            if (appToken == null || appToken.isBlank()) throw new IllegalArgumentException("appToken 不能为空");
            return new ContentCenterClient(this);
        }
    }

    // ===== 结果对象 =====

    /** 上传结果 */
    public record UploadResult(Long fileId, String name, long size, String contentType,
                               String storageKey, String source) {}

    /** 浏览器分片直传完成时提交的分片标识（chunkId 从 0 开始） */
    public record MultipartPart(int chunkId, String etag) {}

    /** 下载链接 */
    public record DownloadLink(String url, long expiresIn) {}

    /** 图片、音频、视频的 CDN 预览链接；url 可直接交给浏览器媒体标签使用。 */
    public record CdnLink(String url, long expiresIn, boolean permanent, String contentType) {}

    /** 简单上传选项 */
    public record UploadOptions(String source, String path, String contentType) {
        public static UploadOptions defaults() { return new UploadOptions(null, null, null); }
        public UploadOptions source(String v) { return new UploadOptions(v, path, contentType); }
        public UploadOptions path(String v) { return new UploadOptions(source, v, contentType); }
        public UploadOptions contentType(String v) { return new UploadOptions(source, path, v); }
    }

    /** 分片上传选项：partSizeBytes 最小 5MB（S3 分片约束） */
    public record MultipartOptions(String source, String path, String contentType, int partSizeBytes) {
        public static MultipartOptions defaults() { return new MultipartOptions(null, null, null, DEFAULT_PART_SIZE); }
        public MultipartOptions source(String v) { return new MultipartOptions(v, path, contentType, partSizeBytes); }
        public MultipartOptions path(String v) { return new MultipartOptions(source, v, contentType, partSizeBytes); }
        public MultipartOptions contentType(String v) { return new MultipartOptions(source, path, v, partSizeBytes); }
        public MultipartOptions partSizeBytes(int v) { return new MultipartOptions(source, path, contentType, v); }
    }

    /** 下载链接请求：fileId 或 storageKey+source 二选一 */
    public record DownloadLinkRequest(Long fileId, String key, String source, String filename, Long expiresIn) {
        public static DownloadLinkRequest ofFileId(long fileId) { return new DownloadLinkRequest(fileId, null, null, null, null); }
        public static DownloadLinkRequest ofKey(String storageKey, String source) { return new DownloadLinkRequest(null, storageKey, source, null, null); }
        public DownloadLinkRequest filename(String v) { return new DownloadLinkRequest(fileId, key, source, v, expiresIn); }
        public DownloadLinkRequest expiresIn(long v) { return new DownloadLinkRequest(fileId, key, source, filename, v); }
    }

    /** CDN 预览链接请求：仅支持按 fileId 获取，expiresIn 省略表示永久。 */
    public record CdnLinkRequest(Long fileId, Long expiresIn) {
        public static CdnLinkRequest ofFileId(long fileId) { return new CdnLinkRequest(fileId, null); }
        public CdnLinkRequest expiresIn(long v) { return new CdnLinkRequest(fileId, v); }
    }

    // ===== 简单上传（预签名直传） =====

    /**
     * 仅初始化简单上传并返回预签名 PUT URL，不读取或上传文件字节。
     * 适合由业务后端保管 appToken、浏览器直接 PUT 对象存储的场景。
     */
    public UploadInitResponse initUpload(String filename, Long size, UploadOptions options) {
        UploadOptions opt = options == null ? UploadOptions.defaults() : options;
        Map<String, Object> body = new HashMap<>();
        body.put("originalName", filename);
        putIfNotNull(body, "contentType", opt.contentType());
        putIfNotNull(body, "size", size);
        putIfNotNull(body, "path", opt.path());
        putIfNotNull(body, "source", opt.source());
        return postJson("/api/open/uploads", body, UploadInitResponse.class);
    }

    /** 仅确认简单上传完成，由内容中心 stat 对象并返回权威元数据。 */
    public UploadResult completeUpload(String storageKey, String source) {
        Map<String, Object> complete = new HashMap<>();
        complete.put("storageKey", storageKey);
        complete.put("source", source);
        UploadCompleteResponse done = postJson("/api/open/uploads/complete", complete, UploadCompleteResponse.class);
        return new UploadResult(done.fileId(), done.name(), done.size(), done.contentType(), storageKey, source);
    }

    public UploadResult upload(Path file, UploadOptions options) {
        try (InputStream in = Files.newInputStream(file)) {
            return upload(in, file.getFileName().toString(), Files.size(file), options);
        } catch (IOException e) {
            throw new ContentCenterException(-1, "读取本地文件失败: " + file, e);
        }
    }

    /** init → 直传对象存储（PUT 预签名 URL）→ complete；PUT 失败抛异常且不调 complete */
    public UploadResult upload(InputStream in, String filename, Long size, UploadOptions options) {
        UploadOptions opt = options == null ? UploadOptions.defaults() : options;
        UploadInitResponse init = initUpload(filename, size, opt);

        String contentType = opt.contentType() == null ? "application/octet-stream" : opt.contentType();
        HttpResponse<String> put = exchange(HttpRequest.newBuilder(URI.create(init.putUrl()))
                .header("Content-Type", contentType)
                .timeout(requestTimeout)
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> in))
                .build());
        if (put.statusCode() / 100 != 2) {
            throw new ContentCenterException(put.statusCode(),
                    "直传对象存储失败 (HTTP " + put.statusCode()
                            + storageErrorCodeSuffix(put.body())
                            + ")，putUrl 可能已过期或无权限，请重试上传");
        }

        return completeUpload(init.storageKey(), init.source());
    }

    // ===== 分片上传（断点续传，仅支持分片的数据源如 MinIO） =====

    /** 仅初始化浏览器分片直传，不读取或上传文件字节。 */
    public MultipartInitResponse initMultipartUpload(String filename, long fileSize, int totalChunks,
                                                       String contentMd5, MultipartOptions options) {
        MultipartOptions opt = options == null ? MultipartOptions.defaults() : options;
        Map<String, Object> initBody = new HashMap<>();
        initBody.put("originalName", filename);
        putIfNotNull(initBody, "contentType", opt.contentType());
        initBody.put("size", fileSize);
        initBody.put("totalChunks", totalChunks);
        initBody.put("contentMd5", contentMd5);
        putIfNotNull(initBody, "path", opt.path());
        putIfNotNull(initBody, "source", opt.source());
        return postJson("/api/open/uploads/multipart/init", initBody, MultipartInitResponse.class);
    }

    /** 为一个浏览器直传分片签发预签名 PUT URL。 */
    public String signMultipartPart(String contentMd5, int chunkId) {
        Map<String, Object> resp = postJson("/api/open/uploads/multipart/sign",
                Map.of("contentMd5", contentMd5, "chunkId", chunkId), Map.class);
        Object url = resp == null ? null : resp.get("url");
        if (url == null) {
            throw new ContentCenterException(-1, "分片签名响应缺少 url");
        }
        return String.valueOf(url);
    }

    /** 提交浏览器已直传的全部分片 ETag 并触发服务端合并。 */
    public MultipartCompleteResponse completeMultipartUpload(String contentMd5, List<MultipartPart> parts) {
        List<Map<String, Object>> bodyParts = new ArrayList<>();
        if (parts != null) {
            for (MultipartPart part : parts) {
                bodyParts.add(Map.of("chunkId", part.chunkId(), "etag", part.etag()));
            }
        }
        return postJson("/api/open/uploads/multipart/complete",
                Map.of("contentMd5", contentMd5, "parts", bodyParts), MultipartCompleteResponse.class);
    }

    /** 整文件 MD5 作幂等 key；init 返回的已传分片自动跳过，仅上传缺失分片后合并 */
    public UploadResult uploadMultipart(Path file, MultipartOptions options) {
        MultipartOptions opt = options == null ? MultipartOptions.defaults() : options;
        int partSize = Math.max(DEFAULT_PART_SIZE, opt.partSizeBytes());
        String filename = file.getFileName().toString();
        long fileSize;
        String md5;
        try {
            fileSize = Files.size(file);
            md5 = md5Hex(file);
        } catch (IOException e) {
            throw new ContentCenterException(-1, "读取本地文件失败: " + file, e);
        }
        int totalChunks = (int) ((fileSize + partSize - 1) / partSize);

        MultipartInitResponse init = initMultipartUpload(filename, fileSize, totalChunks, md5, opt);

        if (init.alreadyDone()) {
            return new UploadResult(init.fileId(), filename, fileSize, opt.contentType(),
                    init.storageKey(), opt.source());
        }

        Map<Integer, String> etagByPart = new HashMap<>();
        if (init.uploadedParts() != null) {
            for (var p : init.uploadedParts()) {
                etagByPart.put(p.partNumber(), p.etag());
            }
        }

        try (var raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            byte[] buffer = new byte[partSize];
            for (int chunkId = 0; chunkId < totalChunks; chunkId++) {
                int partNumber = chunkId + 1;
                if (etagByPart.containsKey(partNumber)) {
                    continue; // 断点续传：跳过已上传分片
                }
                int len = readPart(raf, buffer, chunkId * (long) partSize, partSize);
                String url = signMultipartPart(md5, chunkId);
                HttpResponse<String> put = exchange(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/octet-stream")
                        .timeout(requestTimeout)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(buffer, 0, len))
                        .build());
                if (put.statusCode() / 100 != 2) {
                    throw new ContentCenterException(put.statusCode(),
                            "分片 " + partNumber + "/" + totalChunks + " 直传失败 (HTTP " + put.statusCode()
                                    + storageErrorCodeSuffix(put.body()) + ")");
                }
                String etag = put.headers().firstValue("etag").orElse(null);
                if (etag == null || etag.isBlank()) {
                    throw new ContentCenterException(-1, "分片 " + partNumber + " 响应缺少 ETag，无法合并");
                }
                etagByPart.put(partNumber, etag.replace("\"", ""));
            }
        } catch (IOException e) {
            throw new ContentCenterException(-1, "读取本地文件失败: " + file, e);
        }

        List<MultipartPart> parts = new ArrayList<>();
        for (int chunkId = 0; chunkId < totalChunks; chunkId++) {
            parts.add(new MultipartPart(chunkId, etagByPart.get(chunkId + 1)));
        }
        MultipartCompleteResponse done = completeMultipartUpload(md5, parts);
        return new UploadResult(done.fileId(), filename, done.size(), opt.contentType(),
                done.storageKey(), opt.source());
    }

    // ===== 下载链接 =====

    public DownloadLink getDownloadLink(DownloadLinkRequest request) {
        Map<String, Object> body = new HashMap<>();
        putIfNotNull(body, "fileId", request.fileId());
        putIfNotNull(body, "key", request.key());
        putIfNotNull(body, "source", request.source());
        putIfNotNull(body, "filename", request.filename());
        putIfNotNull(body, "expiresIn", request.expiresIn());
        DownloadLinkResponse resp = postJson("/api/open/download-links", body, DownloadLinkResponse.class);
        return new DownloadLink(resp.url(), resp.expiresIn());
    }

    /** 获取图片、音频或视频的稳定 CDN 预览地址；默认永久有效。 */
    public CdnLink getCdnLink(CdnLinkRequest request) {
        Map<String, Object> body = new HashMap<>();
        putIfNotNull(body, "fileId", request.fileId());
        putIfNotNull(body, "expiresIn", request.expiresIn());
        CdnLinkResponse resp = postJson("/api/open/cdn-links", body, CdnLinkResponse.class);
        return new CdnLink(resp.url(), resp.expiresIn(), resp.permanent(), resp.contentType());
    }

    // ===== HTTP helpers =====

    private <T> T postJson(String path, Object body, Class<T> type) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ContentCenterException(-1, "请求序列化失败", e);
        }
        HttpResponse<String> resp = exchange(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + appToken)
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build());
        return parse(resp, type);
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ContentCenterException(-1, "网络请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContentCenterException(-1, "请求被中断", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T parse(HttpResponse<String> resp, Class<T> type) {
        if (resp.statusCode() / 100 == 2) {
            if (type == Void.class) return null;
            try {
                return mapper.readValue(resp.body(), type);
            } catch (Exception e) {
                throw new ContentCenterException(resp.statusCode(), "响应解析失败: " + e.getMessage(), e);
            }
        }
        String message = null;
        try {
            Map<String, Object> err = mapper.readValue(resp.body(), Map.class);
            Object m = err == null ? null : err.get("message");
            if (m != null) message = String.valueOf(m);
        } catch (Exception ignored) {
        }
        if (message == null || message.isBlank()) {
            message = resp.statusCode() == 401
                    ? "appToken 无效、已轮换或应用被禁用 (HTTP 401)"
                    : "请求失败 (HTTP " + resp.statusCode() + ")";
        } else if (resp.statusCode() == 401) {
            message = message + "（appToken 可能已轮换或应用被禁用）";
        }
        throw new ContentCenterException(resp.statusCode(), message);
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    /** 从 S3/OSS/MinIO 的 XML 错误响应中提取错误码（如 AccessDenied/SignatureDoesNotMatch），便于定位权限/签名/时钟问题 */
    static String storageErrorCodeSuffix(String body) {
        if (body == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<Code>\\s*([^<\\s]+)\\s*</Code>").matcher(body);
        return m.find() ? ", " + m.group(1) : "";
    }

    private static String trimTrailingSlash(String s) {
        String v = s.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    private static int readPart(java.io.RandomAccessFile raf, byte[] buffer, long offset, int length)
            throws IOException {
        raf.seek(offset);
        int read = 0;
        while (read < length) {
            int n = raf.read(buffer, read, length - read);
            if (n < 0) break;
            read += n;
        }
        return read;
    }

    private static String md5Hex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }
}
