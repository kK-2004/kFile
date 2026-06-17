package com.kk.template.entity;

import com.kk.security.entity.AdminUser;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 模板使用授权：记录某管理员被授权使用某模板（SUPER 分配给 ADMIN）。
 * 语义与 ProjectPermission（user ↔ project）一致。
 */
@Getter
@Setter
@Entity
@Table(
        name = "project_template_assignments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "template_id"})
)
public class ProjectTemplateAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private AdminUser user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "template_id")
    private ProjectTemplate template;

    @CreationTimestamp
    private Instant createdAt;
}
