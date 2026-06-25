package com.kk.share.sync;

import com.kk.share.entity.ShareLink;
import com.kk.share.entity.ShareLinkItem;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.storage.StorageKeys;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ShareLink#SHARE_TYPE_FOLDER_SYNC} 同步策略。
 * <p>
 * 以根文件夹为锚点，递归重算当前全部文件，与已存 {@link ShareLinkItem}（kind=FILE）按
 * {@code refId}（stored_file_id）做 diff：
 * <ul>
 *   <li>根文件夹已删除 → 将根条目（kind=FOLDER）{@code deleted=true}，并不重算子项（保留既有置灰）。</li>
 *   <li>新增文件 → 插入 item（kind=FILE）。</li>
 *   <li>消失文件 → 对应 item {@code deleted=true}（保留行用于置灰展示与下载计数，不物理删）。</li>
 * </ul>
 * 幂等：唯一约束 (share_link_id, kind, ref_id) 防重复插入，重复置灰无副作用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FolderSyncStrategy implements ShareSyncStrategy {

    private final StoredFileRepository storedFileRepository;
    private final ShareLinkItemRepository itemRepository;

    @Override
    @Transactional
    public void sync(ShareLink link) {
        Long rootId = link.getRootStoredFileId();
        if (rootId == null) {
            log.warn("FOLDER_SYNC link {} missing rootStoredFileId, skip", link.getId());
            return;
        }

        // 1) 判定根文件夹是否存在
        Optional<StoredFile> rootOpt = storedFileRepository.findById(rootId);
        boolean rootExists = rootOpt.isPresent() && StoredFile.TYPE_FOLDER.equals(rootOpt.get().getType());

        // 2) 处理根条目（kind=FOLDER）的置灰状态
        itemRepository.findByShareLinkIdAndKindAndRefId(link.getId(), ShareLinkItem.KIND_FOLDER, rootId)
                .ifPresent(rootItem -> {
                    boolean wasDeleted = rootItem.isDeleted();
                    if (rootExists && wasDeleted) {
                        rootItem.setDeleted(false);
                        itemRepository.save(rootItem);
                    } else if (!rootExists && !wasDeleted) {
                        rootItem.setDeleted(true);
                        itemRepository.save(rootItem);
                    }
                });

        if (!rootExists) {
            // 根文件夹已删：保留既有子条目原状返回，不重算
            return;
        }

        // 3) 重算根文件夹当前全部文件。relativePath 以根文件夹名起头（与历史分享行为一致，
        //    使前端文件夹视图正常显示），递归时累加子文件夹名。
        List<FileEntry> currentFiles = new ArrayList<>();
        collectFiles(rootOpt.get(), rootOpt.get().getName(), currentFiles);

        // 4) diff：以 refId 为键
        List<ShareLinkItem> existingFileItems = itemRepository.findByShareLinkIdOrderByRelativePath(link.getId()).stream()
                .filter(it -> ShareLinkItem.KIND_FILE.equals(it.getKind()))
                .toList();
        Map<Long, ShareLinkItem> existingByRefId = new HashMap<>();
        Set<Long> existingRefIds = new HashSet<>();
        for (ShareLinkItem it : existingFileItems) {
            existingByRefId.put(it.getRefId(), it);
            existingRefIds.add(it.getRefId());
        }

        // 新增：currentFiles 中存在但 item 无 → 插入；已存在 → 校正 relativePath（文件夹改名/移动后保持准确）
        Set<Long> currentRefIds = new HashSet<>();
        for (FileEntry fe : currentFiles) {
            currentRefIds.add(fe.file.getId());
            ShareLinkItem existing = existingByRefId.get(fe.file.getId());
            if (existing == null) {
                insertFileItem(link, fe.file, fe.relativePath);
            } else if (!fe.relativePath.equals(existing.getRelativePath()) && !existing.isDeleted()) {
                existing.setRelativePath(fe.relativePath);
                itemRepository.save(existing);
            }
        }

        // 消失：item 有但 currentFiles 无 → 软删（不物理删，保留计数与可追溯）
        for (ShareLinkItem it : existingFileItems) {
            if (!currentRefIds.contains(it.getRefId()) && !it.isDeleted()) {
                it.setDeleted(true);
                itemRepository.save(it);
            }
        }
    }

    /**
     * 递归收集文件夹下所有 FILE。relativePath 为相对分享根的路径（含根文件夹名起头，
     * 子文件夹名逐级累加，与历史分享一致，保证前端文件夹视图可用）。
     */
    private void collectFiles(StoredFile folder, String pathPrefix, List<FileEntry> out) {
        List<StoredFile> children = storedFileRepository.findByParentId(folder.getId());
        for (StoredFile c : children) {
            String childPrefix = pathPrefix.isEmpty() ? c.getName() : pathPrefix + "/" + c.getName();
            if (StoredFile.TYPE_FOLDER.equals(c.getType())) {
                collectFiles(c, childPrefix, out);
            } else {
                out.add(new FileEntry(c, pathPrefix));
            }
        }
    }

    private void insertFileItem(ShareLink link, StoredFile f, String relativePath) {
        ShareLinkItem item = new ShareLinkItem();
        item.setShareLink(link);
        item.setKind(ShareLinkItem.KIND_FILE);
        item.setRefId(f.getId());
        item.setRelativePath(relativePath);
        item.setFilename(StorageKeys.baseName(f.getOriginalName()));
        item.setStorageSource(f.getStorageSource());
        item.setStorageKey(f.getStorageKey());
        item.setSize(f.getSize());
        item.setDeleted(false);
        item.setDownloadCount(0);
        try {
            itemRepository.save(item);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发下另一事务已插入：幂等忽略
            log.debug("FOLDER_SYNC duplicate item ignored: link={}, refId={}", link.getId(), f.getId());
        }
    }

    private record FileEntry(StoredFile file, String relativePath) {}
}
