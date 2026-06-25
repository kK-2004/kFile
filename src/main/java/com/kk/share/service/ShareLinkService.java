package com.kk.share.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.share.entity.ShareLink;
import com.kk.share.entity.ShareLinkItem;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.share.sync.FolderSyncStrategy;
import com.kk.share.sync.FileSetSyncStrategy;
import com.kk.share.sync.SubmissionSyncStrategy;
import com.kk.storage.StorageKeys;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.util.Base62Util;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 分享链接服务：按 {@link ShareLink#getShareType()} 创建与同步。
 * <ul>
 *   <li>{@code createFolderSync} / {@code createFileSet}：文件管理入口。</li>
 *   <li>{@code createSubmissionSync}：项目提交入口。</li>
 *   <li>{@code create(...)}：旧 JSON 快照签名，仅历史兜底/测试用。</li>
 *   <li>{@code syncAndLoad}：访问时按类型分发到对应同步策略，再返回最终 item 列表。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareLinkService {

    private final ShareLinkRepository shareLinkRepository;
    private final ShareLinkItemRepository shareLinkItemRepository;
    private final StoredFileRepository storedFileRepository;
    private final FolderSyncStrategy folderSyncStrategy;
    private final FileSetSyncStrategy fileSetSyncStrategy;
    private final SubmissionSyncStrategy submissionSyncStrategy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===== 文件管理：文件夹实时同步 =====

    /** 分享单个文件夹：FOLDER_SYNC。同时写一条 kind=FOLDER 根条目用于根置灰。 */
    @Transactional
    public CreatedShare createFolderSync(Long rootNodeId, Long expireSeconds, String filename) {
        StoredFile root = storedFileRepository.findById(rootNodeId)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + rootNodeId));
        if (!StoredFile.TYPE_FOLDER.equals(root.getType())) {
            throw new IllegalArgumentException("所选节点不是文件夹");
        }
        ShareLink link = newLink(ShareLink.SHARE_TYPE_FOLDER_SYNC, null, filename, expireSeconds);
        link.setRootStoredFileId(root.getId());

        // 根条目（kind=FOLDER）用于根文件夹被删时置灰展示
        ShareLinkItem rootItem = new ShareLinkItem();
        rootItem.setShareLink(link);
        rootItem.setKind(ShareLinkItem.KIND_FOLDER);
        rootItem.setRefId(root.getId());
        rootItem.setRelativePath("");
        rootItem.setFilename(root.getName());
        rootItem.setDeleted(false);
        rootItem.setDownloadCount(0);

        shareLinkRepository.save(link);
        shareLinkItemRepository.save(rootItem);

        // 初始化时做一次全量同步，生成子文件 item
        folderSyncStrategy.sync(link);
        return new CreatedShare(link.getCode(), link.getExpireAt() == null ? null : link.getExpireAt().toEpochMilli());
    }

    // ===== 文件管理：多选文件冻结快照 =====

    /** 分享多选节点（文件 / 多个 / 含文件夹混合）：FILE_SET。文件夹在创建时展开为扁平文件条目并锁定。 */
    @Transactional
    public CreatedShare createFileSet(List<Long> nodeIds, Long expireSeconds, String filename) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个文件或文件夹");
        }
        ShareLink link = newLink(ShareLink.SHARE_TYPE_FILE_SET, null, filename, expireSeconds);
        shareLinkRepository.save(link);

        for (Long nid : nodeIds) {
            StoredFile node = storedFileRepository.findById(nid)
                    .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + nid));
            if (StoredFile.TYPE_FOLDER.equals(node.getType())) {
                // 文件夹节点本身记一条 kind=FOLDER（用于该文件夹被删置灰 + 混合模式下实时跟随）
                shareLinkItemRepository.save(buildFolderItem(link, node));
                // 展开其下全部文件为扁平 FILE 条目，relativePath 以该文件夹名起头（文件夹视图可用）
                collectFileItems(link, node, node.getName());
            } else {
                // 独立选中文件：p 为空，作为冻结快照（FILE_SET 不跟随单个文件变化）
                shareLinkItemRepository.save(buildFileItem(link, node, ""));
            }
        }
        if (shareLinkItemRepository.findByShareLink(link).isEmpty()) {
            throw new IllegalArgumentException("所选文件夹下没有文件");
        }
        return new CreatedShare(link.getCode(), link.getExpireAt() == null ? null : link.getExpireAt().toEpochMilli());
    }

    private ShareLinkItem buildFolderItem(ShareLink link, StoredFile folder) {
        ShareLinkItem item = new ShareLinkItem();
        item.setShareLink(link);
        item.setKind(ShareLinkItem.KIND_FOLDER);
        item.setRefId(folder.getId());
        item.setRelativePath("");
        item.setFilename(folder.getName());
        item.setDeleted(false);
        item.setDownloadCount(0);
        return item;
    }

    private ShareLinkItem buildFileItem(ShareLink link, StoredFile f, String pathPrefix) {
        ShareLinkItem item = new ShareLinkItem();
        item.setShareLink(link);
        item.setKind(ShareLinkItem.KIND_FILE);
        item.setRefId(f.getId());
        item.setRelativePath(pathPrefix);
        item.setFilename(StorageKeys.baseName(f.getOriginalName()));
        item.setStorageSource(f.getStorageSource());
        item.setStorageKey(f.getStorageKey());
        item.setSize(f.getSize());
        item.setDeleted(false);
        item.setDownloadCount(0);
        return item;
    }

    private void collectFileItems(ShareLink link, StoredFile folder, String pathPrefix) {
        List<StoredFile> children = storedFileRepository.findByParentId(folder.getId());
        for (StoredFile c : children) {
            String childPrefix = pathPrefix.isEmpty() ? c.getName() : pathPrefix + "/" + c.getName();
            if (StoredFile.TYPE_FOLDER.equals(c.getType())) {
                collectFileItems(link, c, childPrefix);
            } else {
                // 文件落在当前 pathPrefix 下（已含所在文件夹层级名）
                shareLinkItemRepository.save(buildFileItem(link, c, pathPrefix));
            }
        }
    }

    // ===== 项目提交：实时最新有效提交 =====

    /** 项目提交分享：SUBMISSION_SYNC。记 projectId + 可选字段过滤，提交条目访问时现签。 */
    @Transactional
    public CreatedShare createSubmissionSync(Long projectId, String fieldKey, String fieldValue,
                                             Long expireSeconds, String filename) {
        ShareLink link = newLink(ShareLink.SHARE_TYPE_SUBMISSION_SYNC, projectId, filename, expireSeconds);
        link.setFieldKey(blankToNull(fieldKey));
        link.setFieldValue(blankToNull(fieldValue));
        shareLinkRepository.save(link);
        // 初始化即同步一次，生成 SUBMISSION item
        submissionSyncStrategy.sync(link);
        return new CreatedShare(link.getCode(), link.getExpireAt() == null ? null : link.getExpireAt().toEpochMilli());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ===== 访问时同步 + 取条目 =====

    /**
     * 按 {@link ShareLink#getShareType()} 分发到对应策略同步 item，再返回最终 item 列表。
     * {@code shareType==null}（历史 JSON-only 链接）走只读兜底，不调任何策略。
     */
    @Transactional
    public List<ShareLinkItem> syncAndLoad(ShareLink link) {
        String type = link.getShareType();
        if (type == null) {
            return List.of();
        }
        switch (type) {
            case ShareLink.SHARE_TYPE_FOLDER_SYNC -> folderSyncStrategy.sync(link);
            case ShareLink.SHARE_TYPE_FILE_SET -> fileSetSyncStrategy.sync(link);
            case ShareLink.SHARE_TYPE_SUBMISSION_SYNC -> submissionSyncStrategy.sync(link);
            default -> log.warn("Unknown shareType {} on link {}, skip sync", type, link.getId());
        }
        return shareLinkItemRepository.findByShareLinkIdOrderByRelativePath(link.getId());
    }

    // ===== 删除/吊销 =====

    /**
     * 删除（吊销）分享链接：先清子表 {@code share_link_item}，再删链接本身。
     * 两者在同一事务内，自定义 {@code @Modifying} 删除查询需要环境事务支持。
     *
     * @param link 已加载的分享链接实体
     */
    @Transactional
    public void deleteLink(ShareLink link) {
        shareLinkItemRepository.deleteByShareLinkId(link.getId());
        shareLinkRepository.delete(link);
    }

    // ===== 公共构造 =====

    private ShareLink newLink(String shareType, Long projectId, String filename, Long expireSeconds) {
        ShareLink link = new ShareLink();
        link.setCode(Base62Util.encode(UUID.randomUUID()));
        link.setProjectId(projectId);
        link.setFilename(filename);
        link.setShareType(shareType);
        link.setCreatedAt(Instant.now());
        boolean permanent = expireSeconds != null && expireSeconds <= 0;
        if (!permanent && expireSeconds != null && expireSeconds > 0) {
            link.setExpireAt(Instant.now().plusSeconds(expireSeconds));
        } else {
            link.setExpireAt(null); // 永久（或永久标记）
        }
        return link;
    }

    // ===== 历史 JSON 快照创建（兜底/测试用） =====

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
        link.setFilename(filename);
        link.setData(dataJson);
        link.setCreatedAt(Instant.now());
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
