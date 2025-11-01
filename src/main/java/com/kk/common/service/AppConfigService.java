package com.kk.common.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.common.entity.Config;
import com.kk.common.repo.ConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AppConfigService {
    public static final String KEY_USER_MONTHLY_LIMIT = "USER_MONTHLY_LIMIT";
    public static final String KEY_USER_MAX_FILE_SIZE_BYTES = "USER_MAX_FILE_SIZE_BYTES"; // legacy, not used
    public static final String KEY_USER_TOTAL_QUOTA_BYTES = "USER_TOTAL_QUOTA_BYTES";
    public static final String KEY_USER_ALLOWED_FILE_TYPES = "USER_ALLOWED_FILE_TYPES";

    private final ConfigRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Integer getInt(String key) {
        return repo.findByCfgKey(key).map(c -> parseInt(c.getValue())).orElse(null);
    }

    public Long getLong(String key) {
        return repo.findByCfgKey(key).map(c -> parseLong(c.getValue())).orElse(null);
    }

    public List<String> getStringList(String key) {
        return repo.findByCfgKey(key).map(c -> parseStringList(c.getValue())).orElse(Collections.emptyList());
    }

    public String getRaw(String key) {
        return repo.findByCfgKey(key).map(Config::getValue).orElse(null);
    }

    @Transactional
    public void setRaw(String key, String value) {
        Config c = repo.findByCfgKey(key).orElseGet(() -> { Config x = new Config(); x.setCfgKey(key); return x; });
        c.setValue(value);
        repo.save(c);
    }

    private Integer parseInt(String v) {
        try { return v == null ? null : Integer.parseInt(v.trim()); } catch (Exception e) { return null; }
    }
    private Long parseLong(String v) {
        try { return v == null ? null : Long.parseLong(v.trim()); } catch (Exception e) { return null; }
    }
    private List<String> parseStringList(String v) {
        if (v == null || v.isBlank()) return Collections.emptyList();
        try {
            // try JSON array first
            if (v.trim().startsWith("[")) {
                return objectMapper.readValue(v, new TypeReference<>(){});
            }
        } catch (Exception ignored) {}
        // fallback: comma separated
        String[] parts = v.split(",");
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        for (String p : parts) {
            String s = p.trim(); if (!s.isEmpty()) list.add(s);
        }
        return list;
    }
}
