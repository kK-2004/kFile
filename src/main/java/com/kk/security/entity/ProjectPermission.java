package com.kk.security.entity;

import com.kk.project.entity.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "project_permissions",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"user_id", "project_id"}),
                @UniqueConstraint(columnNames = {"site_user_id", "project_id"})
        }
)
public class ProjectPermission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = true)
    @JoinColumn(name = "user_id", nullable = true)
    private AdminUser user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    // 对应 k-Site 的 userId（user_accounts.external_id），bigint unsigned
    @Column(name = "site_user_id", columnDefinition = "BIGINT UNSIGNED")
    private Long siteUserId;
}
