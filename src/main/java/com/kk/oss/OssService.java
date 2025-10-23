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
}
