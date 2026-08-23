package com.kk.openapi.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 开放应用：外部应用接入内容中心开放 API 的凭证记录。
 * appToken 明文只在创建/轮换响应中出现一次，落库只存 SHA-256 哈希（tokenHash）。
 */
@Getter
@Setter
@Entity
@Table(name = "open_app", indexes = {
        @Index(name = "idx_open_app_token_hash", columnList = "token_hash", unique = true)
})
public class OpenApp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 应用名（全局唯一，同时用作默认上传根目录名） */
    @Column(name = "app_name", nullable = false, length = 128, unique = true)
    private String appName;

    /** appToken 的 SHA-256 hex */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(length = 512)
    private String description;

    /** SDK 上传虚拟根路径（斜杠分隔）；空 = 默认「开放应用/<appName>」 */
    @Column(name = "root_path", length = 512)
    private String rootPath;

    /** 该应用的默认数据源 sourceId（开放 API 请求未传 source 时使用）；空 = 兜底 oss */
    @Column(name = "default_source", length = 16)
    private String defaultSource;

    /** 禁用后 token 立即失效 */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 开放 API 最近鉴权成功时间（节流更新） */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
