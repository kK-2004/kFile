package com.kk.share.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.oss.OssService;
import com.kk.project.entity.Submission;
import com.kk.project.repo.SubmissionRepository;
import com.kk.share.entity.ShareLink;
import com.kk.share.entity.ShareLinkItem;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.share.service.ShareLinkService;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
public class ShareController {

    /** 永久分享访问时现签下载 URL 的有效期（秒） */
    private static final long PRESIGN_EXPIRE_SECONDS = 600L;

    private final ShareLinkRepository shareLinkRepository;
    private final ShareLinkItemRepository shareLinkItemRepository;
    private final ShareLinkService shareLinkService;
    private final StorageBrowserRegistry storageRegistry;
    private final SubmissionRepository submissionRepository;
    private final OssService ossService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ShareController(ShareLinkRepository shareLinkRepository,
                           ShareLinkItemRepository shareLinkItemRepository,
                           ShareLinkService shareLinkService,
                           StorageBrowserRegistry storageRegistry,
                           SubmissionRepository submissionRepository,
                           OssService ossService) {
        this.shareLinkRepository = shareLinkRepository;
        this.shareLinkItemRepository = shareLinkItemRepository;
        this.shareLinkService = shareLinkService;
        this.storageRegistry = storageRegistry;
        this.submissionRepository = submissionRepository;
        this.ossService = ossService;
    }

    @PostMapping("/api/admin/projects/{projectId}/share")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #projectId)")
    public ResponseEntity<?> createShare(@PathVariable Long projectId, @RequestBody CreateShareRequest req) {
        try {
            ShareLinkService.CreatedShare share = shareLinkService.createSubmissionSync(
                    projectId, req.getFieldKey(), req.getFieldValue(), req.getExpireSeconds(), req.getFilename());
            return ResponseEntity.ok(Map.of("code", share.code(), "expireAt", share.expireAt()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("createSubmissionShare failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "创建分享链接失败"));
        }
    }

    @GetMapping("/api/share/{code}")
    @Transactional
    public ResponseEntity<?> getShare(@PathVariable String code) {
        ShareLink link = shareLinkRepository.findByCode(code).orElse(null);
        if (link == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "分享链接不存在"));
        }
        if (link.getExpireAt() != null && Instant.now().isAfter(link.getExpireAt())) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("message", "分享链接已过期"));
        }
        try {
            // 历史 JSON-only 链接（share_type=null）：只读快照兜底，不同步、不写 item
            if (link.getShareType() == null) {
                return ResponseEntity.ok(legacySnapshot(link));
            }

            // 实时同步 + 取最终 item 列表
            List<ShareLinkItem> items = shareLinkService.syncAndLoad(link);

            Map<String, Object> data = new HashMap<>();
            data.put("filename", link.getFilename() != null ? link.getFilename() : "download.zip");
            data.put("shareType", link.getShareType());
            data.put("downloadCount", link.getDownloadCount() == null ? 0 : link.getDownloadCount());
            if (link.getExpireAt() != null) {
                data.put("expireAt", link.getExpireAt().toEpochMilli());
            }
            data.put("entries", renderEntries(items, link.getShareType()));
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.warn("getShare parse failed for {}: {}", code, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "数据解析失败"));
        }
    }

    /** 渲染 item 列表为前端结构，按类型现签下载 URL 并剥离内部字段。 */
    private List<Map<String, Object>> renderEntries(List<ShareLinkItem> items, String shareType) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ShareLinkItem it : items) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", it.getId());
            e.put("kind", it.getKind());
            e.put("f", it.getFilename());
            e.put("p", it.getRelativePath() == null ? "" : it.getRelativePath());
            e.put("s", it.getSize());
            e.put("downloadCount", it.getDownloadCount());
            e.put("deleted", it.isDeleted());

            if (ShareLinkItem.KIND_SUBMISSION.equals(it.getKind())) {
                // 提交分享：按 submission_id 现取 fileUrls 现签
                e.put("u", signSubmissionUrl(it.getRefId()));
                if (it.getSubmitterFingerprint() != null) e.put("submitterFingerprint", it.getSubmitterFingerprint());
                if (it.getSubmitterInfo() != null) e.put("submitterInfo", parseJson(it.getSubmitterInfo()));
                if (it.getSubmitCount() != null) e.put("submitCount", it.getSubmitCount());
            } else if (!it.isDeleted()) {
                // FILE / FOLDER：按 storageSource+storageKey 现签（仅未删除条目）
                e.put("u", signStorageUrl(it.getStorageSource(), it.getStorageKey(), it.getFilename()));
            } else {
                e.put("u", "");
            }
            out.add(e);
        }
        return out;
    }

    /** 现签对象存储下载 URL（600s）；存储源不可用或被删返回空串。 */
    private String signStorageUrl(String storageSource, String storageKey, String filename) {
        if (storageSource == null || storageKey == null) return "";
        try {
            StorageBrowserService svc = storageRegistry.get(storageSource);
            return svc.downloadUrl(storageKey, true, PRESIGN_EXPIRE_SECONDS, filename);
        } catch (Exception ex) {
            log.warn("sign storage url failed: source={}, key={}, msg={}", storageSource, storageKey, ex.getMessage());
            return "";
        }
    }

    /** 按 submission_id 取提交 fileUrls 现签下载 URL（取最后一个对象，与历史打包逻辑一致）。 */
    private String signSubmissionUrl(Long submissionId) {
        if (submissionId == null) return "";
        Submission s = submissionRepository.findById(submissionId).orElse(null);
        if (s == null) return "";
        try {
            List<String> urls = mapper.readValue(s.getFileUrls(),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            if (urls == null || urls.isEmpty()) return "";
            String url = urls.get(urls.size() - 1);
            String key = ossService.extractObjectKey(url);
            if (key == null || key.isBlank()) return "";
            return ossService.generatePresignedUrlByKey(key, true, PRESIGN_EXPIRE_SECONDS, false);
        } catch (Exception ex) {
            log.warn("sign submission url failed: submissionId={}, msg={}", submissionId, ex.getMessage());
            return "";
        }
    }

    private Object parseJson(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    /** 历史 JSON-only 链接只读兜底：解析 data JSON，保留旧逻辑现签永久链接。 */
    private Map<String, Object> legacySnapshot(ShareLink link) throws Exception {
        Map<String, Object> data = mapper.readValue(link.getData(), Map.class);
        boolean permanent = link.getExpireAt() == null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get("entries");
        if (entries != null) {
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> en : entries) {
                Map<String, Object> out = new HashMap<>();
                String url = (String) en.get("u");
                if (permanent || url == null || url.isEmpty()) {
                    String source = (String) en.get("storageSource");
                    String key = (String) en.get("storageKey");
                    if (source != null && key != null) {
                        try {
                            StorageBrowserService svc = storageRegistry.get(source);
                            String filename = (String) en.get("f");
                            url = svc.downloadUrl(key, true, PRESIGN_EXPIRE_SECONDS, filename);
                        } catch (Exception ex) {
                            url = "";
                        }
                    }
                }
                out.put("u", url);
                out.put("f", en.get("f"));
                out.put("p", en.getOrDefault("p", ""));
                out.put("s", en.get("s"));
                out.put("downloadCount", en.getOrDefault("downloadCount", 0));
                filtered.add(out);
            }
            data.put("entries", filtered);
        }
        if (link.getExpireAt() != null) {
            data.put("expireAt", link.getExpireAt().toEpochMilli());
        }
        data.put("downloadCount", link.getDownloadCount() == null ? 0 : link.getDownloadCount());
        return data;
    }

    /** 记录下载（permitAll，无鉴权）：链接维度 +1；新 item 表按 itemId 维度 +1。 */
    @PostMapping("/api/share/{code}/download")
    @Transactional
    public ResponseEntity<?> recordDownload(@PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        ShareLink link = shareLinkRepository.findByCodeForUpdate(code).orElse(null);
        if (link == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "分享链接不存在"));
        }
        if (link.getExpireAt() != null && Instant.now().isAfter(link.getExpireAt())) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("message", "分享链接已过期"));
        }
        try {
            Set<Long> itemIds = extractItemIds(body);
            Set<Integer> legacyIndexes = extractEntryIndexes(body);
            int incrementBy = 1;

            // 新 item 表：按 itemId 维度自增（且只计未删除条目）
            if (!itemIds.isEmpty()) {
                int validCount = 0;
                for (Long id : itemIds) {
                    ShareLinkItem it = shareLinkItemRepository.findById(id).orElse(null);
                    if (it == null || !it.getShareLink().getId().equals(link.getId())) continue;
                    if (it.isDeleted()) continue; // 已删除条目不再计入
                    shareLinkItemRepository.incrementDownloadCount(it.getId());
                    validCount++;
                }
                incrementBy = validCount;
            } else if (!legacyIndexes.isEmpty() && link.getShareType() == null && link.getData() != null) {
                // 历史链接兼容：旧 entryIndex 计数写回 data JSON
                Map<String, Object> data = mapper.readValue(link.getData(), Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get("entries");
                int validCount = 0;
                if (entries != null) {
                    for (Integer idx : legacyIndexes) {
                        if (idx == null || idx < 0 || idx >= entries.size()) continue;
                        Map<String, Object> en = entries.get(idx);
                        int cur = en.get("downloadCount") instanceof Number num ? num.intValue() : 0;
                        en.put("downloadCount", cur + 1);
                        validCount++;
                    }
                }
                if (validCount > 0) {
                    link.setData(mapper.writeValueAsString(data));
                    incrementBy = validCount;
                } else {
                    incrementBy = 0;
                }
            }

            if (incrementBy > 0) {
                int current = link.getDownloadCount() == null ? 0 : link.getDownloadCount();
                link.setDownloadCount(current + incrementBy);
                shareLinkRepository.save(link);
            }
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "记录失败"));
        }
    }

    private Set<Long> extractItemIds(Map<String, Object> body) {
        Set<Long> ids = new LinkedHashSet<>();
        if (body == null) return ids;
        if (body.get("itemId") instanceof Number n) ids.add(n.longValue());
        if (body.get("itemIds") instanceof Iterable<?> values) {
            for (Object v : values) {
                if (v instanceof Number n) ids.add(n.longValue());
            }
        }
        return ids;
    }

    private Set<Integer> extractEntryIndexes(Map<String, Object> body) {
        Set<Integer> indexes = new LinkedHashSet<>();
        if (body == null) return indexes;
        if (body.get("entryIndex") instanceof Number n) indexes.add(n.intValue());
        if (body.get("entryIndexes") instanceof Iterable<?> values) {
            for (Object value : values) {
                if (value instanceof Number n) indexes.add(n.intValue());
            }
        }
        return indexes;
    }

    @Data
    public static class CreateShareRequest {
        private String filename;
        /** 提交分享可选：字段过滤 key（兼容历史 entries 入参） */
        private String fieldKey;
        /** 提交分享可选：字段过滤 value 前缀 */
        private String fieldValue;
        /** 兼容历史 entries 入参（已忽略，提交分享改为实时同步） */
        private List<Map<String, Object>> entries;
        private Long expireSeconds;
    }
}
