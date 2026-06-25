package com.kk.share.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.oss.OssService;
import com.kk.project.entity.Project;
import com.kk.project.entity.Submission;
import com.kk.project.repo.ProjectRepository;
import com.kk.project.repo.SubmissionRepository;
import com.kk.share.entity.ShareLink;
import com.kk.share.entity.ShareLinkItem;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.storage.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ShareLink#SHARE_TYPE_SUBMISSION_SYNC} 同步策略。
 * <p>
 * 按 {@code projectId} + 可选 {@code fieldKey}/{@code fieldValue} 重算「每个 submitterFingerprint
 * 的最新有效提交」集合（复用 {@code buildManifest} 的选择逻辑），并整体替换 item 集合：
 * <ul>
 *   <li>当前集合中有、item 无 → 插入（kind=SUBMISSION，存 submission_id）。</li>
 *   <li>item 有、当前集合无 → 物理删除（不置灰，提交分享为干净实时视图）。</li>
 *   <li>item 记录 submission_id 而非预签名 URL，下载链接访问时现签。</li>
 * </ul>
 * 幂等：重复同步相同源状态无重复 item。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmissionSyncStrategy implements ShareSyncStrategy {

    private final ProjectRepository projectRepository;
    private final SubmissionRepository submissionRepository;
    private final OssService ossService;
    private final ShareLinkItemRepository itemRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public void sync(ShareLink link) {
        if (link.getProjectId() == null) {
            log.warn("SUBMISSION_SYNC link {} missing projectId, skip", link.getId());
            return;
        }
        Project project = projectRepository.findById(link.getProjectId()).orElse(null);
        if (project == null) {
            // 项目被删：清空所有 item（实时视图，干净移除）
            List<ShareLinkItem> items = itemRepository.findByShareLinkIdOrderByRelativePath(link.getId());
            if (!items.isEmpty()) itemRepository.deleteAll(items);
            return;
        }

        List<Submission> currentLatest = selectLatestSubmissions(project, link.getFieldKey(), link.getFieldValue());

        List<ShareLinkItem> existing = itemRepository.findByShareLinkIdOrderByRelativePath(link.getId());
        Map<Long, ShareLinkItem> existingBySubmission = new LinkedHashMap<>();
        Set<Long> existingIds = new HashSet<>();
        for (ShareLinkItem it : existing) {
            existingBySubmission.put(it.getRefId(), it);
            existingIds.add(it.getRefId());
        }

        // 插入新增
        Set<Long> currentIds = new HashSet<>();
        boolean multiFiles = Boolean.TRUE.equals(project.getAllowMultiFiles());
        for (Submission s : currentLatest) {
            currentIds.add(s.getId());
            if (!existingIds.contains(s.getId())) {
                insertSubmissionItem(link, s, multiFiles);
            }
        }

        // 物理删除不再匹配的
        List<ShareLinkItem> toRemove = new ArrayList<>();
        for (ShareLinkItem it : existing) {
            if (!currentIds.contains(it.getRefId())) toRemove.add(it);
        }
        if (!toRemove.isEmpty()) itemRepository.deleteAll(toRemove);
    }

    /** 复用 buildManifest 的选择逻辑：visible + 可选字段前缀过滤 + 按 fingerprint 去重取最新。 */
    private List<Submission> selectLatestSubmissions(Project project, String fieldKey, String fieldValue) {
        List<Submission> all = submissionRepository.findVisibleByProjectOrderByCreatedAtDesc(project);
        boolean doFilter = fieldKey != null && !fieldKey.isBlank() && fieldValue != null && !fieldValue.isBlank();
        LinkedHashMap<String, Submission> latestMap = new LinkedHashMap<>();
        for (Submission s : all) {
            if (doFilter) {
                try {
                    JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                    JsonNode v = node.get(fieldKey);
                    String val = v == null || v.isNull() ? "" : v.asText("");
                    if (val == null || !val.startsWith(fieldValue)) continue;
                } catch (Exception ignored) {
                    continue;
                }
            }
            String key = s.getSubmitterFingerprint();
            if (key == null || key.isBlank()) key = String.valueOf(s.getId());
            if (!latestMap.containsKey(key)) latestMap.put(key, s);
        }
        return new ArrayList<>(latestMap.values());
    }

    private void insertSubmissionItem(ShareLink link, Submission s, boolean multiFiles) {
        // 推导展示文件名与字节数：从 fileUrls 取最后一个对象的原始名；大小用 totalSize
        String filename = deriveFilename(s, multiFiles);
        long size = s.getTotalSize() != null ? s.getTotalSize() : 0L;

        ShareLinkItem item = new ShareLinkItem();
        item.setShareLink(link);
        item.setKind(ShareLinkItem.KIND_SUBMISSION);
        item.setRefId(s.getId());
        item.setRelativePath(""); // 提交分享为平铺列表
        item.setFilename(filename);
        item.setSize(size);
        item.setDeleted(false);
        item.setDownloadCount(0);
        item.setSubmitterFingerprint(s.getSubmitterFingerprint());
        item.setSubmitterInfo(s.getSubmitterInfo());
        item.setSubmitCount(s.getSubmitCount());
        try {
            itemRepository.save(item);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.debug("SUBMISSION_SYNC duplicate item ignored: link={}, refId={}", link.getId(), s.getId());
        }
    }

    /** 从提交 fileUrls 推导下载展示文件名；allowMultiFiles=false 时取最后一个。 */
    private String deriveFilename(Submission s, boolean multiFiles) {
        List<String> urls = parseFileUrls(s);
        if (urls.isEmpty()) return "submission-" + s.getId();
        String target = multiFiles ? urls.get(0) : urls.get(urls.size() - 1);
        String key = ossService.extractObjectKey(target);
        String name = ossService.downloadFilenameFromKey(key);
        return StorageKeys.baseName(name);
    }

    private List<String> parseFileUrls(Submission s) {
        try {
            return objectMapper.readValue(s.getFileUrls(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
