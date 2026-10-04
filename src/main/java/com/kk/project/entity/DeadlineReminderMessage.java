package com.kk.project.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** 同一次提醒的不可变请求快照；须在调用消息平台前提交，供重试和进程重启复用。 */
@Getter
@Setter
@Entity
@Table(name = "deadline_reminder_messages",
        uniqueConstraints = @UniqueConstraint(name = "uk_deadline_reminder_key", columnNames = "idempotency_key"))
public class DeadlineReminderMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 160, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private String groupId;

    @Column(nullable = false, columnDefinition = "LONGTEXT", updatable = false)
    private String cardJson;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
