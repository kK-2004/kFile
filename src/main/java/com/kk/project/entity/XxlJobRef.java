package com.kk.project.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 项目与 XXL-JOB 任务之间的映射，用于 endAt 变动时定位旧任务进行 update/remove。
 * 一个项目最多关联一个截止提醒任务，靠 projectId 唯一约束保证。
 */
@Getter
@Setter
@Entity
@Table(name = "xxl_job_refs",
        uniqueConstraints = @UniqueConstraint(columnNames = "project_id"))
public class XxlJobRef {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** admin 分配的任务 id */
    private Long jobId;

    /** handler 名（如 projectDeadlineRemindJob） */
    @Column(length = 64)
    private String handler;

    /** 当前生效的 cron 表达式（用于排查） */
    @Column(length = 64)
    private String cron;

    /** 提前小时数（endAt 前 N 小时触发），与任务 param 一致，便于变更检测与触发时校验 */
    private Integer notifyHours;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
