package com.kk.project.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "submissions", indexes = {
        @Index(name = "idx_submission_valid", columnList = "valid"),
        @Index(name = "idx_submission_proj_fpr_created", columnList = "project_id,submitterFingerprint,createdAt")
})
public class Submission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @JsonIgnore
    private Project project;

    // JSON object with submitter info
    @Column(columnDefinition = "json")
    private String submitterInfo;

    // canonical fingerprint (e.g., md5 of sorted json) for uniqueness checks
    @Column(length = 64)
    private String submitterFingerprint;

    // JSON array of URLs
    @Column(columnDefinition = "json")
    private String fileUrls;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    // total times this submitter has submitted to this project at the time of this record
    private Integer submitCount;

    // 是否有效（仅保留每个提交者最近的N条，超出则标记为无效，后续清理掉OSS文件）
    @Column(nullable = false)
    private Boolean valid = true;

    // 是否已过期（达到保留上限后被淘汰）
    private Boolean expired = false;

    // 记录提交来源IP
    @Column(length = 64)
    private String ipAddress;

    // 记录设备信息（User-Agent）
    @Column(columnDefinition = "text")
    private String userAgent;

    // 结构化设备信息
    @Column(length = 64)
    private String osName;
    @Column(length = 64)
    private String osVersion;
    @Column(length = 64)
    private String browserName;
    @Column(length = 64)
    private String browserVersion;
    @Column(length = 32)
    private String deviceType; // Desktop/Mobile/Tablet/Other

    // GeoIP 信息
    @Column(length = 64)
    private String ipCountry;
    @Column(length = 64)
    private String ipProvince;
    @Column(length = 64)
    private String ipCity;
}
