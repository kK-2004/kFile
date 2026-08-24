package com.kk.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 持久化幂等键锁行；行本身很小，用于跨实例串行化 multipart init。 */
@Entity
@Table(name = "multipart_init_lock")
public class MultipartInitLock {

    @Id
    @Column(name = "content_key", length = 32, nullable = false)
    private String contentKey;

    protected MultipartInitLock() {}

    public MultipartInitLock(String contentKey) {
        this.contentKey = contentKey;
    }

    public String getContentKey() {
        return contentKey;
    }
}
