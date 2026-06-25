package com.kk.share.sync;

import com.kk.share.entity.ShareLink;
import com.kk.share.entity.ShareLinkItem;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.storage.StorageKeys;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
 * {@link ShareLink#SHARE_TYPE_FILE_SET} 同步策略（混合模式）。
 * <p>
 * 多选分享中：
 * <ul>
 *   <li><b>kind=FOLDER 条目（用户选中的文件夹）</b>：实时跟随——递归重算其下当前文件，
 *       新增文件→插 FILE item，消失文件→软删（deleted=true，保留计数）。</li>
 *   <li><b>kind=FILE 条目（用户单独选中的文件）</b>：冻结快照——只检测来源 stored_file 是否
 *       仍存在，不存在则软删；不做新增、不跟随任何文件夹。</li>
 * </ul>
 * FOLDER 条目被删（其 stored_file 不存在）→ 整个文件夹根行 deleted=true，且不重算其下子项
 * （保留既有置灰/计数）。独立 FILE 条目以 stored_file 是否存在为准。幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileSetSyncStrategy implements ShareSyncStrategy {

    private final StoredFileRepository storedFileRepository;
    private final ShareLinkItemRepository itemRepository;

    @Override
    @Transactional
    public void sync(ShareLink link) {
        List<ShareLinkItem> items = itemRepository.findByShareLinkIdOrderByRelativePath(link.getId());

        List<ShareLinkItem> folderItems = new ArrayList<>();
        List<ShareLinkItem> standaloneFileItems = new ArrayList<>();
        for (ShareLinkItem it : items) {
            if (ShareLinkItem.KIND_FOLDER.equals(it.getKind())) folderItems.add(it);
            else if (ShareLinkItem.KIND_FILE.equals(it.getKind())) standaloneFileItems.add(it);
        }

        // ===== 混合模式关键：区分 FILE item 的归属 =====
        // 用户「单独选中」的文件 item：refId 即其自身 stored_file_id；
        // 用户「选中文件夹」展开产生的文件 item：其 refId 仍为 stored_file_id，但与独立文件无法在表里区分。
        // 为正确实现「文件夹实时、文件冻结」，需依据 FOLDER 条目动态归属。
        // 做法：先处理 FOLDER 条目（实时同步其下文件），用「该次同步涉及到的 refId 集合」标记为文件夹派生；
        // 剩余未归属的 FILE item 视为独立选中文件，按冻结快照处理。

        // 1) 处理 FOLDER 条目：实时跟随，收集其下当前文件 refId（含本次新增/既有的可下载 FILE item）
        Set<Long> folderDerivedRefIds = new HashSet<>();
        for (ShareLinkItem folderItem : folderItems) {
            syncFolderItem(link, folderItem, folderDerivedRefIds);
        }

        // 2) 独立文件（非文件夹派生）按冻结快照：仅检测来源是否存在
        Set<Long> standaloneRefIds = new HashSet<>();
        for (ShareLinkItem it : standaloneFileItems) {
            if (!folderDerivedRefIds.contains(it.getRefId())) standaloneRefIds.add(it.getRefId());
        }
        if (!standaloneRefIds.isEmpty()) {
            Set<Long> existingIds = new HashSet<>(
                    storedFileRepository.findAllById(standaloneRefIds).stream().map(StoredFile::getId).toList());
            for (ShareLinkItem it : standaloneFileItems) {
                if (folderDerivedRefIds.contains(it.getRefId())) continue;
                if (!existingIds.contains(it.getRefId()) && !it.isDeleted()) {
                    it.setDeleted(true);
                    itemRepository.save(it);
                }
            }
        }
    }

    /**
     * 同步单个 FOLDER 条目（用户选中的文件夹）：递归重算其下文件。
     * 文件夹根行被删（stored_file 不存在）→ deleted=true 并返回（不重算子项）。
     * folderDerivedRefIds 收集本次该文件夹下当前仍存在的文件 refId，供上层区分文件归属。
     */
    private void syncFolderItem(ShareLink link, ShareLinkItem folderItem, Set<Long> folderDerivedRefIds) {
        Optional<StoredFile> folderOpt = storedFileRepository.findById(folderItem.getRefId());
        boolean folderExists = folderOpt.isPresent()
                && StoredFile.TYPE_FOLDER.equals(folderOpt.get().getType());

        // 文件夹根行置灰状态
        if (!folderExists) {
            if (!folderItem.isDeleted()) {
                folderItem.setDeleted(true);
                itemRepository.save(folderItem);
            }
            return;
        }
        if (folderItem.isDeleted()) {
            folderItem.setDeleted(false);
            itemRepository.save(folderItem);
        }

        StoredFile folder = folderOpt.get();
        // relativePath 以该文件夹名起头（与 createFileSet 一致，文件夹视图可用）
        List<FileEntry> currentFiles = new ArrayList<>();
        collectFiles(folder, folder.getName(), currentFiles);

        // 取该分享下、相对路径以该文件夹名起头的 FILE item 视为该文件夹派生
        // （createFileSet 写入时即按此规则）。用 refId 索引以便 diff。
        Map<Long, ShareLinkItem> derivedByRefId = new HashMap<>();
        List<ShareLinkItem> allFileItems = itemRepository.findByShareLinkIdOrderByRelativePath(link.getId()).stream()
                .filter(it -> ShareLinkItem.KIND_FILE.equals(it.getKind()))
                .toList();
        for (ShareLinkItem it : allFileItems) {
            String rp = it.getRelativePath();
            // 文件夹派生文件的 relativePath 形如 "folderName" 或 "folderName/.../..."；独立文件为 ""
            if (rp != null && !rp.isEmpty() && (rp.equals(folder.getName()) || rp.startsWith(folder.getName() + "/"))) {
                derivedByRefId.put(it.getRefId(), it);
                folderDerivedRefIds.add(it.getRefId());
            }
        }

        // 新增 / 校正路径
        Set<Long> currentRefIds = new HashSet<>();
        for (FileEntry fe : currentFiles) {
            currentRefIds.add(fe.file.getId());
            folderDerivedRefIds.add(fe.file.getId());
            ShareLinkItem existing = derivedByRefId.get(fe.file.getId());
            if (existing == null) {
                insertFileItem(link, fe.file, fe.relativePath);
            } else if (!existing.isDeleted()
                    && (fe.relativePath.equals(existing.getRelativePath()) == false)) {
                existing.setRelativePath(fe.relativePath);
                itemRepository.save(existing);
            }
        }

        // 消失：软删
        for (ShareLinkItem it : derivedByRefId.values()) {
            if (!currentRefIds.contains(it.getRefId()) && !it.isDeleted()) {
                it.setDeleted(true);
                itemRepository.save(it);
            }
        }
    }

    /** 递归收集文件夹下所有 FILE，relativePath 含文件夹名起头逐级累加。 */
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
        } catch (DataIntegrityViolationException e) {
            log.debug("FILE_SET duplicate item ignored: link={}, refId={}", link.getId(), f.getId());
        }
    }

    private record FileEntry(StoredFile file, String relativePath) {}
}
