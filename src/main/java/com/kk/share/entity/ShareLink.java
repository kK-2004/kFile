package com.kk.share.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "share_link")
public class ShareLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    private Long projectId;

    /** 分享展示名（新链接写入；历史链接仍从 data JSON 解析） */
    @Column(length = 512)
    private String filename;

    @Column(columnDefinition = "LONGTEXT")
    private String data;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant expireAt;

    /** 链接维度下载总次数（包装类型兼容旧行 NULL） */
    @Column(name = "download_count")
    private Integer downloadCount = 0;

    // ===== 实时同步元数据 =====

    /**
     * 分享类型：{@link #SHARE_TYPE_FOLDER_SYNC} / {@link #SHARE_TYPE_FILE_SET} / {@link #SHARE_TYPE_SUBMISSION_SYNC}。
     * 可空用于历史兼容（老链接 {@code data} JSON 只读兜底，不参与同步）。
     */
    @Column(name = "share_type", length = 16)
    private String shareType;

    /** FOLDER_SYNC 根文件夹 stored_file_id；其余类型为 null */
    @Column(name = "root_stored_file_id")
    private Long rootStoredFileId;

    /** SUBMISSION_SYNC 字段过滤 key（可空） */
    @Column(name = "field_key", length = 128)
    private String fieldKey;

    /** SUBMISSION_SYNC 字段过滤 value 前缀（可空） */
    @Column(name = "field_value", length = 255)
    private String fieldValue;

    public static final String SHARE_TYPE_FOLDER_SYNC = "FOLDER_SYNC";
    public static final String SHARE_TYPE_FILE_SET = "FILE_SET";
    public static final String SHARE_TYPE_SUBMISSION_SYNC = "SUBMISSION_SYNC";
}
