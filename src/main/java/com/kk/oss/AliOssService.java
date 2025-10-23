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
import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(value = "oss.type", havingValue = "ali", matchIfMissing = true)
@RequiredArgsConstructor
public class AliOssService implements OssService {

    private final OssProperties properties;
    private final com.kk.common.FileNameCodec fileNameCodec;
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
            String enc = fileNameCodec.encrypt(originalName);
            String key = normalizePrefix(keyPrefix) + enc;
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
        for (MultipartFile f : files) {
            urls.add(uploadWithPrefix(f, keyPrefix));
        }
        return urls;
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
        String host = properties.getHost();
        String objectKey;
        if (StringUtils.hasText(host) && url.startsWith(host)) {
            objectKey = url.substring(host.length());
        } else {
            // try best effort: remove protocol and bucket host, keep path
            int idx = url.indexOf(".aliyuncs.com/");
            if (idx > 0) {
                int slash = url.indexOf('/', idx + ".aliyuncs.com".length() + 1);
                objectKey = slash > 0 ? url.substring(slash + 1) : "";
            } else {
                // fallback: try after first single slash after domain
                int pos = url.indexOf('/', 8); // skip protocol
                objectKey = pos > 0 ? url.substring(pos + 1) : url;
            }
        }
        if (StringUtils.hasText(objectKey)) {
            try {
                ossClientPublic.deleteObject(properties.getBucket(), objectKey);
            } catch (Exception e) {
                log.warn("Failed to delete OSS object: {} - {}", objectKey, e.getMessage());
            }
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
            if (ossClientInternal != null) {
                try {
                    return ossClientInternal.getObject(properties.getBucket(), key).getObjectContent();
                } catch (Exception ex) {
                    log.warn("OSS 内网读取失败，回退公网: key={}, reason={}", key, ex.getMessage());
                }
            }
            return ossClientPublic.getObject(properties.getBucket(), key).getObjectContent();
        } catch (Exception e) {
            throw new IllegalStateException("读取OSS对象失败", e);
        }
    }

    @Override
    public String extractObjectKey(String url) {
        String host = properties.getHost();
        String objectKey;
        // Support internal proxy path anywhere in URL: .../file/oss/{key}
        String proxyPrefix = "/file/oss/";
        if (url != null) {
            int pos = url.indexOf(proxyPrefix);
            if (pos >= 0) {
                return url.substring(pos + proxyPrefix.length());
            }
        }
        if (StringUtils.hasText(host) && url.startsWith(host)) {
            objectKey = url.substring(host.length());
        } else {
            int idx = url.indexOf(".aliyuncs.com/");
            if (idx > 0) {
                int slash = url.indexOf('/', idx + ".aliyuncs.com".length() + 1);
                objectKey = slash > 0 ? url.substring(slash + 1) : "";
            } else {
                int pos = url.indexOf('/', 8);
                objectKey = pos > 0 ? url.substring(pos + 1) : url;
            }
        }
        return objectKey;
    }

    @Override
    public InputStream openByKey(String key) {
        try {
            if (ossClientInternal != null) {
                try {
                    return ossClientInternal.getObject(properties.getBucket(), key).getObjectContent();
                } catch (Exception ex) {
                    log.warn("OSS 内网读取失败，回退公网: key={}, reason={}", key, ex.getMessage());
                }
            }
            return ossClientPublic.getObject(properties.getBucket(), key).getObjectContent();
        } catch (Exception e) {
            throw new IllegalStateException("读取OSS对象失败", e);
        }
    }

    @Override
    public String generatePresignedUrlByKey(String key, boolean forceDownload, long expireSeconds, boolean preferInternal) {
        java.util.Date expiration = new java.util.Date(System.currentTimeMillis() + Math.max(60, expireSeconds) * 1000);
        OSS client = (preferInternal && ossClientInternal != null) ? ossClientInternal : ossClientPublic;
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(properties.getBucket(), key, HttpMethod.GET);
        req.setExpiration(expiration);
        if (forceDownload) {
            // Content-Disposition 通过响应头参数传递
            String filename = decryptFilenameFromKey(key);
            String ascii = filename.replaceAll("[^\\x20-\\x7E]", "_");
            String encoded;
            try {
                encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            } catch (Exception e) { encoded = ascii; }
            req.addQueryParameter("response-content-disposition", "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
        }
        java.net.URL url = client.generatePresignedUrl(req);
        return url.toString();
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

    private String decryptFilenameFromKey(String key) {
        if (key == null || key.isEmpty()) return "file";
        int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
        String enc = slash >= 0 ? key.substring(slash + 1) : key;
        String name = fileNameCodec.decrypt(enc);
        return (name == null || name.isBlank()) ? enc : name;
    }
}
