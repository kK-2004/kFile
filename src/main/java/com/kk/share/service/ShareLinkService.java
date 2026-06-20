package com.kk.share.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.share.entity.ShareLink;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.util.Base62Util;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShareLinkService {
    private final ShareLinkRepository shareLinkRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CreatedShare create(Long projectId, String filename, List<Map<String, Object>> entries, Long expireSeconds) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("entries 不能为空");
        }
        String code = Base62Util.encode(UUID.randomUUID());

        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(Map.of(
                    "filename", filename != null && !filename.isBlank() ? filename : "download.zip",
                    "entries", entries
            ));
        } catch (Exception e) {
            throw new IllegalStateException("序列化分享数据失败", e);
        }

        ShareLink link = new ShareLink();
        link.setCode(code);
        link.setProjectId(projectId);
        link.setData(dataJson);
        link.setCreatedAt(Instant.now());
        // expireSeconds=null 表示永久分享（expireAt=null）；否则按秒数过期
        if (expireSeconds != null && expireSeconds > 0) {
            link.setExpireAt(Instant.now().plusSeconds(expireSeconds));
        } else {
            link.setExpireAt(null); // 永久
        }
        shareLinkRepository.save(link);
        return new CreatedShare(code, link.getExpireAt() == null ? null : link.getExpireAt().toEpochMilli());
    }

    public record CreatedShare(String code, Long expireAt) {}
}
