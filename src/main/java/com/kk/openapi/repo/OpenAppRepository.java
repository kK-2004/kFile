package com.kk.openapi.repo;

import com.kk.openapi.entity.OpenApp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OpenAppRepository extends JpaRepository<OpenApp, Long> {

    /** 鉴权：按 token 哈希唯一索引查找 */
    Optional<OpenApp> findByTokenHash(String tokenHash);

    Optional<OpenApp> findByAppName(String appName);

    boolean existsByAppName(String appName);

    List<OpenApp> findAllByOrderByCreatedAtDesc();
}
