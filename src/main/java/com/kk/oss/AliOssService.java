package com.kk.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadResult;
import com.aliyun.oss.model.UploadPartRequest;
import com.aliyun.oss.model.UploadPartResult;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.PartETag;
import com.kk.config.OssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(value = "oss.type", havingValue = "ali", matchIfMissing = true)
@RequiredArgsConstructor
public class AliOssService implements OssService {

    private final OssProperties properties;
    @org.springframework.beans.factory.annotation.Value("${app.base-path:}")
    private String appBasePath;
    private OSS ossClientPublic;
    private OSS ossClientInternal;

    @PostConstruct
    public void init() {
        if (!"ali".equalsIgnoreCase(properties.getType())) {
            log.warn("OSS type is not 'ali', AliOssService will still initialize with provided config.");
        }
        com.aliyun.oss.ClientBuilderConfiguration conf = new com.aliyun.oss.ClientBuilderConfiguration();
        conf.setProtocol(com.aliyun.oss.common.comm.Protocol.HTTPS);
        conf.setConnectionTimeout(10000);
        // 放宽超时以适配大文件/慢网
        conf.setSocketTimeout(120000);
        conf.setMaxConnections(128);
        conf.setRequestTimeout(120000);
        // 公网客户端（用于上传、删除）
        String publicEp = properties.getEndpoint();
        this.ossClientPublic = new OSSClientBuilder().build(publicEp, properties.getAk(), properties.getSk(), conf);
        // 内网客户端（仅读取时使用，可选）
        String internalEp = properties.getInternalEndpoint();
        if (org.springframework.util.StringUtils.hasText(internalEp)) {
            this.ossClientInternal = new OSSClientBuilder().build(internalEp, properties.getAk(), properties.getSk(), conf);
        } else {
            this.ossClientInternal = null;
        }
        log.info("AliOssService initialized. publicEp={}, internalEp={}, protocol=HTTPS, bucket={}", publicEp, internalEp, properties.getBucket());
    }

    @PreDestroy
    public void destroy() {
        if (ossClientPublic != null) {
            ossClientPublic.shutdown();
        }
        if (ossClientInternal != null) {
            ossClientInternal.shutdown();
        }
    }

    @Override
    public String upload(MultipartFile file) {
        try {
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String md5;
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] buf = new byte[8192];
                int n;
                try (InputStream is = file.getInputStream()) {
                    while ((n = is.read(buf)) > 0) {
                        md.update(buf, 0, n);
                    }
                }
                byte[] dig = md.digest();
                StringBuilder sb = new StringBuilder(dig.length * 2);
                for (byte b : dig) {
                    sb.append(String.format("%02x", b));
                }
                md5 = sb.toString();
            } catch (Exception e) {
                // 退回使用原始文件名的 MD5（低概率路径）
                md5 = DigestUtils.md5DigestAsHex((file.getOriginalFilename() + System.nanoTime()).getBytes());
            }
            String ext = getExtension(file.getOriginalFilename());
            String keyPrefix = properties.getPrefix();
            if (keyPrefix == null) keyPrefix = "";
            if (StringUtils.hasText(keyPrefix) && !keyPrefix.endsWith("/")) {
                keyPrefix = keyPrefix + "/";
            }
            String objectKey = keyPrefix + datePath + "/" + md5 + (ext.isEmpty() ? "" : "." + ext);
            putStreamOrMultipart(file, objectKey);
            return normalizeBase(appBasePath) + "/file/oss/" + objectKey;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read upload file", e);
        }
    }

    @Override
    public List<String> upload(List<MultipartFile> files) {
        List<String> urls = new ArrayList<>();
        for (MultipartFile f : files) {
            urls.add(upload(f));
        }
        return urls;
    }

    @Override
    public String uploadWithPrefix(MultipartFile file, String keyPrefix) {
        try {
            String originalName = baseName(file.getOriginalFilename());
            String key = normalizePrefix(keyPrefix) + originalName;
            putStreamOrMultipart(file, key);
            return normalizeBase(appBasePath) + "/file/oss/" + key;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read upload file", e);
        }
    }

    private void putStreamOrMultipart(MultipartFile file, String key) throws IOException {
        final long size = file.getSize();
        final long threshold = 5L * 1024 * 1024; // 5MB
        if (size >= threshold) {
            multipartUpload(file, key);
        } else {
            try (InputStream in = file.getInputStream()) {
                ObjectMetadata meta = new ObjectMetadata();
                if (size >= 0) meta.setContentLength(size);
                if (file.getContentType() != null) meta.setContentType(file.getContentType());
                PutObjectRequest req = new PutObjectRequest(properties.getBucket(), key, in, meta);
                ossClientPublic.putObject(req);
            } catch (Exception ex) {
                log.error("OSS上传失败: bucket={}, key={}, msg={}", properties.getBucket(), key, ex.getMessage(), ex);
                throw new IllegalStateException("文件上传失败，请联系管理员", ex);
            }
        }
    }

    private void multipartUpload(MultipartFile file, String key) throws IOException {
        String bucket = properties.getBucket();
        InitiateMultipartUploadRequest initReq = new InitiateMultipartUploadRequest(bucket, key);
        InitiateMultipartUploadResult initRes = ossClientPublic.initiateMultipartUpload(initReq);
        String uploadId = initRes.getUploadId();
        java.util.List<PartETag> partETags = new java.util.ArrayList<>();
        final int partSize = 5 * 1024 * 1024; // 5MB
        byte[] buffer = new byte[partSize];
        int partNumber = 1;
        long uploaded = 0;
        try (InputStream in = file.getInputStream()) {
            int read;
            while ((read = in.readNBytes(buffer, 0, partSize)) > 0) {
                UploadPartRequest up = new UploadPartRequest();
                up.setBucketName(bucket);
                up.setKey(key);
                up.setUploadId(uploadId);
                up.setInputStream(new java.io.ByteArrayInputStream(buffer, 0, read));
                up.setPartSize(read);
                up.setPartNumber(partNumber++);
                UploadPartResult upRes = ossClientPublic.uploadPart(up);
                partETags.add(upRes.getPartETag());
                uploaded += read;
            }
            CompleteMultipartUploadRequest completeReq = new CompleteMultipartUploadRequest(bucket, key, uploadId, partETags);
            ossClientPublic.completeMultipartUpload(completeReq);
        } catch (Exception ex) {
            try { ossClientPublic.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, uploadId)); } catch (Exception ignore) {}
            log.error("OSS分片上传失败: bucket={}, key={}, uploaded={}, msg={}", bucket, key, uploaded, ex.getMessage(), ex);
            throw new IllegalStateException("文件上传失败，请联系管理员", ex);
        }
    }

    @Override
    public List<String> uploadWithPrefix(List<MultipartFile> files, String keyPrefix) {
        List<String> urls = new ArrayList<>();
        try {
            for (MultipartFile f : files) {
                urls.add(uploadWithPrefix(f, keyPrefix));
            }
            return urls;
        } catch (RuntimeException ex) {
            // 发生异常时回滚已上传的对象，避免产生孤儿文件
            try { deleteByUrls(urls); } catch (Exception ignore) {}
            throw ex;
        }
    }

    @Override
    public String uploadStreamWithPrefix(InputStream in, long size, String contentType, String originalName, String keyPrefix) {
        String original = baseName(originalName);
        String key = normalizePrefix(keyPrefix) + original;
        final long threshold = 5L * 1024 * 1024; // 5MB
        try {
            if (size >= 0 && size < threshold) {
                ObjectMetadata meta = new ObjectMetadata();
                meta.setContentLength(size);
                if (contentType != null && !contentType.isBlank()) meta.setContentType(contentType);
                PutObjectRequest req = new PutObjectRequest(properties.getBucket(), key, in, meta);
                ossClientPublic.putObject(req);
            } else {
                multipartUploadFromStream(in, key);
            }
            return normalizeBase(appBasePath) + "/file/oss/" + key;
        } catch (Exception ex) {
            log.error("OSS上传失败: bucket={}, key={}, msg={}", properties.getBucket(), key, ex.getMessage(), ex);
            throw new IllegalStateException("文件上传失败，请联系管理员", ex);
        }
    }

    private void multipartUploadFromStream(InputStream in, String key) throws IOException {
        String bucket = properties.getBucket();
        InitiateMultipartUploadRequest initReq = new InitiateMultipartUploadRequest(bucket, key);
        InitiateMultipartUploadResult initRes = ossClientPublic.initiateMultipartUpload(initReq);
        String uploadId = initRes.getUploadId();
        java.util.List<PartETag> partETags = new java.util.ArrayList<>();
        final int partSize = 5 * 1024 * 1024; // 5MB
        byte[] buffer = new byte[partSize];
        int partNumber = 1;
        long uploaded = 0;
        try {
            int read;
            while ((read = in.read(buffer)) > 0) {
                UploadPartRequest up = new UploadPartRequest();
                up.setBucketName(bucket);
                up.setKey(key);
                up.setUploadId(uploadId);
                up.setInputStream(new ByteArrayInputStream(buffer, 0, read));
                up.setPartSize(read);
                up.setPartNumber(partNumber++);
                UploadPartResult upRes = ossClientPublic.uploadPart(up);
                partETags.add(upRes.getPartETag());
                uploaded += read;
            }
            CompleteMultipartUploadRequest completeReq = new CompleteMultipartUploadRequest(bucket, key, uploadId, partETags);
            ossClientPublic.completeMultipartUpload(completeReq);
        } catch (Exception ex) {
            try { ossClientPublic.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, uploadId)); } catch (Exception ignore) {}
            log.error("OSS分片上传失败: bucket={}, key={}, uploaded={}, msg={}", bucket, key, uploaded, ex.getMessage(), ex);
            throw new IllegalStateException("文件上传失败，请联系管理员", ex);
        }
    }

    private String baseName(String filename) {
        if (!StringUtils.hasText(filename)) return "file";
        String fn = filename;
        int slash = Math.max(fn.lastIndexOf('/'), fn.lastIndexOf('\\'));
        if (slash >= 0) fn = fn.substring(slash + 1);
        // 防止路径穿越，移除 ..
        fn = fn.replace("..", "");
        return fn;
    }

    private String normalizePrefix(String prefix) {
        String p = prefix == null ? "" : prefix;
        if (StringUtils.hasText(properties.getPrefix())) {
            String pre = properties.getPrefix();
            if (!pre.endsWith("/")) pre += "/";
            p = pre + p;
        }
        if (p.isEmpty()) return "";
        if (!p.endsWith("/")) p += "/";
        return p;
    }

    @Override
    public void deleteByUrl(String url) {
        if (!StringUtils.hasText(url)) return;
        String objectKey = extractObjectKey(url);
        if (!StringUtils.hasText(objectKey)) return;
        try {
            ossClientPublic.deleteObject(properties.getBucket(), objectKey);
        } catch (Exception e) {
            log.warn("Failed to delete OSS object: {} - {}", objectKey, e.getMessage());
        }
    }

    @Override
    public void deleteByUrls(List<String> urls) {
        if (urls == null) return;
        urls.forEach(this::deleteByUrl);
    }

    @Override
    public InputStream openByUrl(String url) {
        String key = extractObjectKey(url);
        if (!StringUtils.hasText(key)) throw new IllegalArgumentException("无效的OSS地址");
        try {
            // 优先内网读取，失败自动回退公网
            // 按要求：不再使用内网，全部走公网客户端
            return ossClientPublic.getObject(properties.getBucket(), key).getObjectContent();
        } catch (Exception e) {
            throw new IllegalStateException("读取OSS对象失败", e);
        }
    }

    @Override
    public String extractObjectKey(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String host = properties.getHost();
        String objectKey;
        // Support internal proxy path anywhere in URL: .../file/oss/{key}
        String proxyPrefix = "/file/oss/";
        int pos = url.indexOf(proxyPrefix);
        if (pos >= 0) {
            return url.substring(pos + proxyPrefix.length());
        }
        if (StringUtils.hasText(host) && url.startsWith(host)) {
            objectKey = url.substring(host.length());
        } else {
            int idx = url.indexOf(".aliyuncs.com/");
            if (idx > 0) {
                int slash = url.indexOf('/', idx + ".aliyuncs.com".length() + 1);
                objectKey = slash > 0 ? url.substring(slash + 1) : "";
            } else {
                int firstSlash = url.indexOf('/', 8);
                objectKey = firstSlash > 0 ? url.substring(firstSlash + 1) : url;
            }
        }
        return objectKey;
    }

    @Override
    public InputStream openByKey(String key) {
        try {
            // 统一走公网下载，便于在多环境/代理/CDN 下行为一致
            return ossClientPublic.getObject(properties.getBucket(), key).getObjectContent();
        } catch (Exception e) {
            throw new IllegalStateException("读取OSS对象失败", e);
        }
    }

    @Override
    public ObjectStat statByKey(String key) {
        try {
            com.aliyun.oss.model.ObjectMetadata meta = ossClientPublic.getObjectMetadata(properties.getBucket(), key);
            return new ObjectStat(meta.getContentLength(), meta.getETag(), meta.getLastModified(), meta.getContentType());
        } catch (Exception e) {
            throw new IllegalStateException("获取OSS对象元数据失败", e);
        }
    }

    @Override
    public InputStream openByKeyRange(String key, long startInclusive, long endInclusive) {
        try {
            com.aliyun.oss.model.GetObjectRequest req = new com.aliyun.oss.model.GetObjectRequest(properties.getBucket(), key);
            req.setRange(startInclusive, endInclusive);
            return ossClientPublic.getObject(req).getObjectContent();
        } catch (Exception e) {
            throw new IllegalStateException("读取OSS对象分段失败", e);
        }
    }

    @Override
    public String generatePresignedUrlByKey(String key, boolean forceDownload, long expireSeconds, boolean preferInternal) {
        java.util.Date expiration = new java.util.Date(System.currentTimeMillis() + Math.max(60, expireSeconds) * 1000);
        // 不再使用内网
        OSS client = ossClientPublic;
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(properties.getBucket(), key, HttpMethod.GET);
        req.setExpiration(expiration);
        // Content-Disposition 通过响应头参数传递
        String filename = downloadFilenameFromKey(key);
        String rawSegment = extractFilenameFromKey(key);
        boolean legacyEncoded = rawSegment != null && !rawSegment.isBlank() && !rawSegment.equals(filename);
        if (forceDownload || legacyEncoded) {
            String ascii = filename.replaceAll("[^\\x20-\\x7E]", "_");
            String encoded;
            try {
                encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            } catch (Exception e) { encoded = ascii; }
            String dispo = (forceDownload ? "attachment" : "inline")
                    + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
            req.addQueryParameter("response-content-disposition", dispo);
        }
        java.net.URL url = client.generatePresignedUrl(req);
        return url.toString();
    }

    @Override
    public String downloadFilenameFromKey(String key) {
        String raw = extractFilenameFromKey(key);
        if (!StringUtils.hasText(raw)) return "file";
        String decoded = legacyDecryptFilename(raw);
        // 兜底：避免路径穿越/空文件名
        String safe = baseName(decoded);
        return StringUtils.hasText(safe) ? safe : baseName(raw);
    }

    @Override
    public String generatePresignedPutUrlByKey(String key, long expireSeconds, String contentType) {
        java.util.Date expiration = new java.util.Date(System.currentTimeMillis() + Math.max(60, expireSeconds) * 1000);
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(properties.getBucket(), key, HttpMethod.PUT);
        req.setExpiration(expiration);
        if (contentType != null && !contentType.isBlank()) {
            // 将 Content-Type 加入签名的请求头中（而不是作为查询参数）
            req.setContentType(contentType);
        }
        java.net.URL url = ossClientPublic.generatePresignedUrl(req);
        return url.toString();
    }

    // ===== Direct multipart (browser) support =====
    @Override
    public String initiateMultipartUpload(String key) {
        InitiateMultipartUploadRequest initReq = new InitiateMultipartUploadRequest(properties.getBucket(), key);
        InitiateMultipartUploadResult res = ossClientPublic.initiateMultipartUpload(initReq);
        return res.getUploadId();
    }

    @Override
    public String generatePresignedUploadPartUrl(String key, String uploadId, int partNumber, long expireSeconds, String contentType) {
        java.util.Date expiration = new java.util.Date(System.currentTimeMillis() + Math.max(60, expireSeconds) * 1000);
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(properties.getBucket(), key, HttpMethod.PUT);
        req.setExpiration(expiration);
        req.addQueryParameter("partNumber", String.valueOf(partNumber));
        req.addQueryParameter("uploadId", uploadId);
        if (contentType != null && !contentType.isBlank()) {
            req.setContentType(contentType);
        }
        java.net.URL url = ossClientPublic.generatePresignedUrl(req);
        return url.toString();
    }

    @Override
    public void completeMultipartUpload(String key, String uploadId, List<PartETag> parts) {
        parts.sort(java.util.Comparator.comparingInt(PartETag::getPartNumber));
        CompleteMultipartUploadRequest completeReq = new CompleteMultipartUploadRequest(properties.getBucket(), key, uploadId, parts);
        ossClientPublic.completeMultipartUpload(completeReq);
    }

    @Override
    public void abortMultipartUpload(String key, String uploadId) {
        try {
            ossClientPublic.abortMultipartUpload(new AbortMultipartUploadRequest(properties.getBucket(), key, uploadId));
        } catch (Exception ignore) {}
    }

    @Override
    public String proxyUrlByKey(String key) {
        return normalizeBase(appBasePath) + "/file/oss/" + key;
    }

    private String getExtension(String filename) {
        if (!StringUtils.hasText(filename)) return "";
        int idx = filename.lastIndexOf('.')
                ;
        if (idx < 0 || idx == filename.length() - 1) return "";
        return filename.substring(idx + 1).toLowerCase();
    }

    private String normalizeBase(String base) {
        if (base == null || base.isBlank()) return "";
        String b = base.trim();
        if (!b.startsWith("/")) b = "/" + b;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        // root path should be empty string
        if ("/".equals(b)) return "";
        return b;
    }

    private String extractFilenameFromKey(String key) {
        if (key == null || key.isEmpty()) return "file";
        int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
        String name = slash >= 0 ? key.substring(slash + 1) : key;
        return name;
    }

    private String legacyDecryptFilename(String encrypted) {
        if (!StringUtils.hasText(encrypted)) return encrypted;
        try {
            if (encrypted.startsWith("f_")) {
                String b64 = encrypted.substring(2);
                byte[] enc = Base64.getUrlDecoder().decode(b64);
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(legacyKeyBytes(), "AES"));
                byte[] dec = cipher.doFinal(enc);
                String s = new String(dec, java.nio.charset.StandardCharsets.UTF_8);
                return StringUtils.hasText(s) ? s : encrypted;
            }
            if (encrypted.startsWith("b_")) {
                String b64 = encrypted.substring(2);
                String s = new String(Base64.getUrlDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
                return StringUtils.hasText(s) ? s : encrypted;
            }
            return encrypted;
        } catch (Exception e) {
            return encrypted;
        }
    }

    private byte[] legacyKeyBytes() {
        String secret = properties.getFilenameSecret();
        if (!StringUtils.hasText(secret)) secret = "default-filename-secret";
        byte[] src = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] key = new byte[16]; // AES-128
        for (int i = 0; i < key.length; i++) key[i] = i < src.length ? src[i] : 0;
        return key;
    }
}
