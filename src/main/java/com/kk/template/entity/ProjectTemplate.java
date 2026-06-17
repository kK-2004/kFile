package com.kk.template.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 项目模板：保存可复用的项目配置快照。
 * 仅保存与具体项目实例无关的可复用字段，不含 name/startAt/endAt/fileSizeLimitBytes/allowedFileTypes/offline。
 * 由 SUPER 创建，可授权给 ADMIN 使用。
 */
@Getter
@Setter
@Entity
@Table(name = "project_templates")
public class ProjectTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    // 创建者（SUPER 的 AdminUser.id），始终可用，且为唯一可编辑/删除者
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    // ===== 可复用字段（与 Project 对应字段存储方式一致）=====

    // JSON schema/array describing expected submitter fields
    @Column(columnDefinition = "json")
    private String expectedUserFields;

    // 上传路径字段key
    @Column(length = 64)
    private String pathFieldKey;

    // 上传路径层级（json 数组），支持 "$project" 或提交者字段 key
    @Column(columnDefinition = "json")
    private String pathSegments;

    // 用户端提交状态提示类型与文案
    @Column(length = 16)
    private String userSubmitStatusType;

    @Column(length = 255)
    private String userSubmitStatusText;

    // 用户端"查询提交状态"所使用的查询字段
    @Column(length = 64)
    private String queryFieldKey;

    // 提交者限制：允许提交的字段 key 列表
    @Column(columnDefinition = "json")
    private String allowedSubmitterKeys;

    // 允许提交的名单
    @Column(columnDefinition = "json")
    private String allowedSubmitterList;

    // 自动命名文件
    private Boolean autoFileNamingEnabled = false;

    @Column(columnDefinition = "json")
    private String autoFileNamingConfig;

    // 开关型规则
    private Boolean allowResubmit;
    private Boolean allowMultiFiles;
    private Boolean allowOverdue;

    @CreationTimestamp
    private Instant createdAt;
}
