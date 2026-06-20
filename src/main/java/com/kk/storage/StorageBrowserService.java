package com.kk.storage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 面向 SUPER 文件管理的对象存储能力抽象（DB 虚拟树 + 浏览器直传方案）。
 * <p>
 * 职责：签发直传 PUT 直链、下载 URL、删除、取元数据。
 * 文件夹层级、路径、重名等虚拟树逻辑由 {@code StoredFileService}（DB 层）负责。
 */
public interface StorageBrowserService {

    /** 数据源标识，如 "oss" / "minio" */
    String sourceId();

    /** 数据源展示名，如 "OSS" / "MinIO" */
    String sourceLabel();

    /** 删除对象（按 storageKey） */
    void delete(String storageKey);

    /** 生成下载 URL：预签名直链或代理 URL；downloadFilename 用于 Content-Disposition */
    default String downloadUrl(String storageKey, boolean download, long expireSeconds) {
        return downloadUrl(storageKey, download, expireSeconds, null);
    }

    /** 生成下载 URL：预签名直链或代理 URL；downloadFilename 覆盖 Content-Disposition 中的文件名 */
    String downloadUrl(String storageKey, boolean download, long expireSeconds, String downloadFilename);

    /**
     * 生成浏览器直传 PUT 预签名直链（前端直接 PUT 到对象存储，不经过后端）。
     */
    String presignedPutUrl(String storageKey, long expireSeconds, String contentType);

    /** 查询对象元信息（key 不存在时返回 null） */
    Entry stat(String storageKey);

    /** 对象元信息条目 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class Entry {
        /** 显示名 */
        private String name;
        /** 字节数 */
        private long size;
        /** 最后修改时间 */
        private Date lastModified;
        /** 对象 key */
        private String key;
        /** Content-Type */
        private String contentType;
    }
}
