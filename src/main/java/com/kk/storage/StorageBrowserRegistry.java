package com.kk.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件管理数据源注册表：按 {@code oss} / {@code minio} 字符串收集可用实现。
 * OSS 实现始终装配；MinIO 实现仅在 minio.enabled=true 时存在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageBrowserRegistry {

    private final List<StorageBrowserService> services;

    private Map<String, StorageBrowserService> index() {
        Map<String, StorageBrowserService> map = new LinkedHashMap<>();
        for (StorageBrowserService s : services) {
            map.put(s.sourceId(), s);
        }
        return map;
    }

    /** 返回当前已启用的数据源列表（OSS 始终在；MinIO 仅启用时在） */
    public List<Source> sources() {
        return index().values().stream()
                .map(s -> new Source(s.sourceId(), s.sourceLabel()))
                .toList();
    }

    /** 按 source 分发；未知 source 抛 IllegalArgumentException */
    public StorageBrowserService get(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        StorageBrowserService svc = index().get(source);
        if (svc == null) {
            throw new IllegalArgumentException("未知或未启用的数据源: " + source);
        }
        return svc;
    }

    public Collection<StorageBrowserService> all() {
        return index().values();
    }

    public record Source(String id, String label) {}
}
