package com.kk.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
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
    private OSS ossClientPublic;
    private OSS ossClientInternal;

    @PostConstruct
    public void init() {
        if (!"ali".equalsIgnoreCase(properties.getType())) {
            log.warn("OSS type is not 'ali', AliOssService will still initialize with provided config.");
        }
        com.aliyun.oss.ClientBuilderConfiguration conf = new com.aliyun.oss.ClientBuilderConfiguration();
        conf.setProtocol(com.aliyun.oss.common.comm.Protocol.HTTPS);
        conf.setConnectionTimeout(8000);
        conf.setSocketTimeout(15000);
        conf.setMaxConnections(128);
        conf.setRequestTimeout(20000);
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
            byte[] bytes = file.getBytes();
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String md5 = DigestUtils.md5DigestAsHex(bytes);
            String ext = getExtension(file.getOriginalFilename());
            String keyPrefix = properties.getPrefix();
            if (keyPrefix == null) keyPrefix = "";
            if (StringUtils.hasText(keyPrefix) && !keyPrefix.endsWith("/")) {
                keyPrefix = keyPrefix + "/";
            }
            String objectKey = keyPrefix + datePath + "/" + md5 + (ext.isEmpty() ? "" : "." + ext);
            PutObjectRequest request = new PutObjectRequest(properties.getBucket(), objectKey, new ByteArrayInputStream(bytes));
            try {
                ossClientPublic.putObject(request);
            } catch (Exception ex) {
                log.error("OSS上传失败: bucket={}, key={}, msg={}", properties.getBucket(), objectKey, ex.getMessage(), ex);
                throw new IllegalStateException("文件上传失败，请联系管理员", ex);
            }
            return "/file/oss/" + objectKey;
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
            byte[] bytes = file.getBytes();
            String originalName = baseName(file.getOriginalFilename());
            String enc = fileNameCodec.encrypt(originalName);
            String key = normalizePrefix(keyPrefix) + enc;
            PutObjectRequest request = new PutObjectRequest(properties.getBucket(), key, new ByteArrayInputStream(bytes));
            try {
                ossClientPublic.putObject(request);
            } catch (Exception ex) {
                log.error("OSS上传失败: bucket={}, key={}, msg={}", properties.getBucket(), key, ex.getMessage(), ex);
                throw new IllegalStateException("文件上传失败，请联系管理员", ex);
            }
            return "/file/oss/" + key;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read upload file", e);
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
        // Support internal proxy path: /file/oss/{key}
        String proxyPrefix = "/file/oss/";
        if (url != null && url.startsWith(proxyPrefix)) {
            return url.substring(proxyPrefix.length());
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

    private String getExtension(String filename) {
        if (!StringUtils.hasText(filename)) return "";
        int idx = filename.lastIndexOf('.')
                ;
        if (idx < 0 || idx == filename.length() - 1) return "";
        return filename.substring(idx + 1).toLowerCase();
    }
}
