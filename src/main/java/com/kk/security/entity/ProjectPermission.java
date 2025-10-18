package com.kk.security.entity;

import com.kk.project.entity.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "project_permissions", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "project_id"}))
public class ProjectPermission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private AdminUser user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "project_id")
    private Project project;
}

