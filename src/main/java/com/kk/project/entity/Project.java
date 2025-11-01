package com.kk.project.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Setter
@Entity
@Table(name = "projects")
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // JSON array of strings (e.g., ["pdf","jpg"]) stored as text/json
    @Column(columnDefinition = "json")
    private String allowedFileTypes;

    // max allowed size per file, in bytes (null = unlimited)
    private Long fileSizeLimitBytes;

    // maintained by service: distinct submitter count
    private Integer totalSubmitters;

    // JSON schema/array describing expected submitter fields
    @Column(columnDefinition = "json")
    private String expectedUserFields;

    private Instant startAt;
    private Instant endAt;

    private Boolean allowResubmit;
    // 是否允许一次提交多个文件
    private Boolean allowMultiFiles = true;

    // 是否允许逾期：到截止时间后仍可提交，但会标记为逾期
    private Boolean allowOverdue = false;

    // 下线标记：true 表示项目已下线，禁止提交
    private Boolean offline = false;

    // 上传路径字段key：提交者信息里用于作为二级目录的字段
    @Column(length = 64)
    private String pathFieldKey;

    // 上传路径层级，按顺序的段（json数组）。支持特殊值"$project"表示项目名称，其它为提交者字段key
    @Column(columnDefinition = "json")
    private String pathSegments;

    // 用户端提交状态提示类型与文案（如 info/warning/success/danger + 自定义文字）
    @Column(length = 16)
    private String userSubmitStatusType; // info, warning, success, danger

    @Column(length = 255)
    private String userSubmitStatusText;

    // 用户端“查询提交状态”所使用的查询字段（期望字段 key）
    @Column(length = 64)
    private String queryFieldKey;

    // 可选：限制允许提交的“提交者字段组合”key 列表（json 数组）
    @Column(columnDefinition = "json")
    private String allowedSubmitterKeys;

    // 可选：允许提交的取值集合（json）。
    // - 若 keys 只有一个：此处可以为字符串数组 ["1001","1002",...] 或对象数组 [{"studentId":"1001"},...]
    // - 若 keys 多个：应为对象数组，如 [{"class":"一班","studentId":"1001"}, ...]
    @Column(columnDefinition = "json")
    private String allowedSubmitterList;

    // 创建者（站点用户ID）
    @Column(name = "creator_site_user_id", columnDefinition = "BIGINT UNSIGNED")
    private Long creatorSiteUserId;

    // 创建时间
    @CreationTimestamp
    private Instant createdAt;
}
