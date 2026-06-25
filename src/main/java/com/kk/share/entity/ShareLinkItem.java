package com.kk.share.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 分享链接的规范化条目（取代旧 {@code share_link.data} JSON 快照）。
 * <p>
 * 每条记录独立追踪：
 * <ul>
 *   <li>{@link #kind}：FOLDER / FILE / SUBMISSION</li>
 *   <li>{@link #refId}：来源引用（{@code stored_file_id} 或 {@code submission_id}）</li>
 *   <li>{@link #deleted}：软删置灰标志（FILE/FOLDER 用，SUBMISSION 物理删除）</li>
 *   <li>{@link #downloadCount}：条目级下载计数</li>
 * </ul>
 * 访问分享时按 {@link ShareLink#getShareType()} 触发同步器更新本表，渲染与计数均以此为准。
 */
@Getter
@Setter
@Entity
@Table(name = "share_link_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_share_item_link_kind_ref", columnNames = {"share_link_id", "kind", "ref_id"})
}, indexes = {
        @Index(name = "idx_share_item_link", columnList = "share_link_id")
})
public class ShareLinkItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属分享链接 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "share_link_id", nullable = false)
    private ShareLink shareLink;

    /** FOLDER / FILE / SUBMISSION */
    @Column(nullable = false, length = 16)
    private String kind;

    /** 来源引用：kind=FILE/FOLDER 时为 stored_file_id；kind=SUBMISSION 时为 submission_id */
    @Column(name = "ref_id", nullable = false)
    private Long refId;

    /** 在分享中的相对路径前缀（用于保留文件夹层级展示），可为空 */
    @Column(name = "relative_path", length = 1024)
    private String relativePath;

    /** 显示文件名 */
    @Column(nullable = false, length = 512)
    private String filename;

    /** 对象存储源（kind=FILE/FOLDER）；SUBMISSION 为 null，访问时现签 */
    @Column(name = "storage_source", length = 16)
    private String storageSource;

    /** 对象存储 key（kind=FILE/FOLDER）；SUBMISSION 为 null */
    @Column(name = "storage_key", length = 1024)
    private String storageKey;

    /** 字节数 */
    private long size;

    /** 软删置灰标志：FOLDER_SYNC/FILE_SET 中被删的来源置 true 并保留行 */
    @Column(nullable = false)
    private boolean deleted = false;

    /** 条目级下载计数 */
    @Column(name = "download_count")
    private int downloadCount = 0;

    // ===== SUBMISSION_SYNC 专用展示字段（其它类型可空） =====

    @Column(name = "submitter_fingerprint", length = 64)
    private String submitterFingerprint;

    /** 提交者信息（解析后的 JSON 字符串，前端可直接展示） */
    @Column(name = "submitter_info", columnDefinition = "text")
    private String submitterInfo;

    @Column(name = "submit_count")
    private Integer submitCount;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public static final String KIND_FOLDER = "FOLDER";
    public static final String KIND_FILE = "FILE";
    public static final String KIND_SUBMISSION = "SUBMISSION";
}
