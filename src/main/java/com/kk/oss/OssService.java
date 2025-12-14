package com.kk.oss;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface OssService {
    String upload(MultipartFile file);
    List<String> upload(List<MultipartFile> files);
    void deleteByUrl(String url);
    void deleteByUrls(List<String> urls);

    default String uploadWithPrefix(MultipartFile file, String keyPrefix) { throw new UnsupportedOperationException(); }
    default List<String> uploadWithPrefix(List<MultipartFile> files, String keyPrefix) { throw new UnsupportedOperationException(); }

    java.io.InputStream openByUrl(String url);
    String extractObjectKey(String url);

    default java.io.InputStream openByKey(String key) { throw new UnsupportedOperationException(); }

    // 解析用于下载展示的文件名（兼容历史“加密文件名”对象 key）
    default String downloadFilenameFromKey(String key) {
        if (key == null || key.isEmpty()) return "file";
        int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    // 生成带有效期的下载直链；若实现不支持请抛出 UnsupportedOperationException
    default String generatePresignedUrlByKey(String key, boolean forceDownload, long expireSeconds, boolean preferInternal) {
        throw new UnsupportedOperationException();
    }

    // 对象元信息（用于设置 Content-Length/ETag/Last-Modified/Content-Type 等）
    class ObjectStat {
        public final long length;
        public final String eTag;
        public final java.util.Date lastModified;
        public final String contentType;
        public ObjectStat(long length, String eTag, java.util.Date lastModified, String contentType) {
            this.length = length; this.eTag = eTag; this.lastModified = lastModified; this.contentType = contentType;
        }
    }

    default ObjectStat statByKey(String key) { throw new UnsupportedOperationException(); }
    default java.io.InputStream openByKeyRange(String key, long startInclusive, long endInclusive) {
        throw new UnsupportedOperationException();
    }

    // 生成用于直传的 PUT 签名 URL（浏览器直传）
    default String generatePresignedPutUrlByKey(String key, long expireSeconds, String contentType) {
        throw new UnsupportedOperationException();
    }

    // 根据对象 key 构造站点代理访问路径（/file/oss/{key}，带上 app.base-path 如有）
    default String proxyUrlByKey(String key) { throw new UnsupportedOperationException(); }

    // ===== Direct multipart (browser) support =====
    default String initiateMultipartUpload(String key) { throw new UnsupportedOperationException(); }
    default String generatePresignedUploadPartUrl(String key, String uploadId, int partNumber, long expireSeconds, String contentType) { throw new UnsupportedOperationException(); }
    default void completeMultipartUpload(String key, String uploadId, List<com.aliyun.oss.model.PartETag> parts) { throw new UnsupportedOperationException(); }
    default void abortMultipartUpload(String key, String uploadId) { throw new UnsupportedOperationException(); }

    // ===== Stream upload (server-side) support =====
    // 直接以流方式上传单个文件，指定原始文件名与可选的 Content-Type，并返回可访问的代理 URL
    default String uploadStreamWithPrefix(java.io.InputStream in, long size, String contentType, String originalName, String keyPrefix) {
        throw new UnsupportedOperationException();
    }
}
