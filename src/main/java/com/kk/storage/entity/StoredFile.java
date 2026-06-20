package com.kk.storage.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * DB 虚拟文件树节点。parentId 自引用构成多级文件夹；type=FOLDER/FILE。
 * 与对象存储的真实扁平结构解耦：FILE 节点的 storageKey 指向 MinIO 扁平对象，
 * storageSource 标识存储源（minio/oss）。FOLDER 节点的 storageKey/storageSource 为 null。
 */
@Getter
@Setter
@Entity
@Table(name = "stored_file", indexes = {
        @Index(name = "idx_stored_file_parent", columnList = "parent_id"),
        @Index(name = "idx_stored_file_uploader", columnList = "uploader_id")
})
public class StoredFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 父文件夹 id；null 表示根级 */
    @Column(name = "parent_id")
    private Long parentId;

    /** 上传者 AdminUser.id；文件夹为创建者 */
    @Column(name = "uploader_id")
    private Long uploaderId;

    /** 显示名（文件夹名/文件名） */
    @Column(nullable = false, length = 255)
    private String name;

    /** FOLDER 或 FILE */
    @Column(nullable = false, length = 16)
    private String type;

    /** 存储源标识（minio/oss）；FOLDER 为 null */
    @Column(name = "storage_source", length = 16)
    private String storageSource;

    /** 对象存储真实 key；FOLDER 为 null */
    @Column(name = "storage_key", length = 1024)
    private String storageKey;

    /** 上传时的原始文件名 */
    @Column(name = "original_name", length = 512)
    private String originalName;

    /** 字节数（FOLDER 为 0） */
    private long size;

    /** Content-Type */
    @Column(name = "content_type", length = 255)
    private String contentType;

    /** 上传状态：UPLOADED（默认，含非分片文件）/ UPLOADING（分片上传中） */
    @Column(length = 16)
    private String status;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public static final String TYPE_FOLDER = "FOLDER";
    public static final String TYPE_FILE = "FILE";
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_UPLOADING = "UPLOADING";
}
