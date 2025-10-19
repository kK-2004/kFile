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
}
