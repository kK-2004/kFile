package com.kk.project.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

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

    // 下线标记：true 表示项目已下线，禁止提交
    private Boolean offline = false;

    // 上传路径字段key：提交者信息里用于作为二级目录的字段
    @Column(length = 64)
    private String pathFieldKey;

    // 上传路径层级，按顺序的段（json数组）。支持特殊值"$project"表示项目名称，其它为提交者字段key
    @Column(columnDefinition = "json")
    private String pathSegments;
}
