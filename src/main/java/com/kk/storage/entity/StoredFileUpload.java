package com.kk.storage.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 分片上传进度元数据。与 {@link StoredFile}（文件树节点）分离。
 * <p>
 * contentMd5 唯一索引作幂等 key（前端 SparkMD5 算），用于断点续传识别同一文件；
 * uploadId 是 S3/MinIO multipart upload 标识，续传时据此调 ListParts 查已传 part。
 * 进度状态全在 MinIO（S3 multipart 服务端状态），本表只持久化 uploadId 等元数据，不存 Redis。
 */
@Getter
@Setter
@Entity
@Table(name = "stored_file_upload", indexes = {
        @Index(name = "idx_sfu_md5", columnList = "content_md5", unique = true),
        @Index(name = "idx_sfu_status_updated", columnList = "status,updated_at")
})
public class StoredFileUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 整文件 MD5（前端 SparkMD5），唯一幂等 key */
    @Column(name = "content_md5", nullable = false, length = 32)
    private String contentMd5;

    /** 关联的 StoredFile.id（type=FILE，status=UPLOADING/UPLOADED） */
    @Column(name = "stored_file_id")
    private Long storedFileId;

    /** S3 multipart upload id */
    @Column(name = "upload_id", nullable = false, length = 255)
    private String uploadId;

    /** 分片对象 key 前缀（不含 chunkId 后缀） */
    @Column(name = "chunk_key_prefix", nullable = false, length = 1024)
    private String chunkKeyPrefix;

    /** 合并后最终对象 key */
    @Column(name = "storage_key", nullable = false, length = 1024)
    private String storageKey;

    @Column(nullable = false)
    private String bucket;

    /** 总分片数 */
    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    /** UPLOADING / UPLOADED / FAILED */
    @Column(nullable = false, length = 16)
    private String status;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public static final String STATUS_UPLOADING = "UPLOADING";
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_FAILED = "FAILED";
}
