package com.kk.security.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "admin_users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class AdminUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 100)
    private String password; // BCrypt

    // SUPER or ADMIN
    @Column(nullable = false, length = 16)
    private String role;

    private Boolean enabled = true;

    @CreationTimestamp
    private Instant createdAt;
}
