package com.kk.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "configs", uniqueConstraints = @UniqueConstraint(columnNames = "k"))
public class Config {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "k", nullable = false, length = 64)
    private String cfgKey;

    @Column(name = "v", columnDefinition = "text")
    private String value;

    @UpdateTimestamp
    private Instant updatedAt;
}
