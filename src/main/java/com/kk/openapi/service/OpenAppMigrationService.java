package com.kk.openapi.service;

import com.kk.config.MinioProperties;
import com.kk.config.OssProperties;
import com.kk.openapi.entity.OpenApp;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.StorageKeys;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.entity.StoredFileUpload;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.repo.StoredFileUploadRepository;
import com.kk.openapi.service.OpenAppService.MigrationItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 开放应用 rootPath 变更的同步全量迁移（设计决策见 openspec add-open-api-sdk design.md）。
 * <p>
 * 流程：收集迁移项（跳过活跃分片上传/不在旧根下的文件）→ 逐项 copy 新 key + stat 校验
 * → 全部成功后单事务落地 DB（reparent + storageKey 改写 + 清理搬空旧目录）→ best-effort 删除旧对象。
 * 任一 copy 失败：清理已复制新对象、DB 不动、抛 500，应用保持原 rootPath。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAppMigrationService {

    private final OpenAppService openAppService;
    private final StoredFileRepository storedFileRepository;
    private final StoredFileUploadRepository uploadRepository;
    private final StorageBrowserRegistry registry;
    private final MinioProperties minioProperties;
    private final OssProperties ossProperties;

    public record MigrationResult(int moved, int skipped, List<String> skippedFiles) {}

    /**
     * 修改应用 rootPath 并同步迁移其名下全部已登记文件；请求阻塞至迁移完成。
     * rawRootPath 为原始入参（blank = 恢复默认「开放应用/<appName>」）。
     */
    public MigrationResult changeRootPath(OpenApp app, String rawRootPath) {
        String normalized = OpenAppService.normalizeRootPath(rawRootPath);
        String oldRoot = OpenAppService.effectiveRoot(app);
        String newRoot = (normalized != null) ? normalized
                : OpenAppService.DEFAULT_ROOT_DIR + "/" + app.getAppName();

        if (oldRoot.equals(newRoot)) {
            // 路径无变化：仅落库（如仅清理空格等归一化差异）
            openAppService.applyMigration(app.getId(), normalized, List.of(), List.of(oldRoot.split("/")));
            return new MigrationResult(0, 0, List.of());
        }

        List<String> oldRootSegments = Arrays.asList(oldRoot.split("/"));
        List<String> newRootSegments = Arrays.asList(newRoot.split("/"));

        // ===== 收集迁移项 =====
        List<MigrationItem> items = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (StoredFile f : storedFileRepository.findByOpenAppId(app.getId())) {
            if (!StoredFile.TYPE_FILE.equals(f.getType())) continue;
            if (uploadRepository.findByStoredFileId(f.getId())
                    .map(u -> StoredFileUpload.STATUS_UPLOADING.equals(u.getStatus()))
                    .orElse(false)) {
                skipped.add(f.getName() + ": 分片上传进行中");
                continue;
            }
            StorageBrowserService svc = resolveForSource(f.getStorageSource());
            if (svc == null) {
                skipped.add(f.getName() + ": 存储源未启用(" + f.getStorageSource() + ")");
                continue;
            }
            List<String> fullSegments = folderSegments(f.getParentId());
            if (fullSegments.size() < oldRootSegments.size()
                    || !fullSegments.subList(0, oldRootSegments.size()).equals(oldRootSegments)) {
                skipped.add(f.getName() + ": 不在当前根目录下");
                continue;
            }
            String oldKey = f.getStorageKey();
            if (oldKey == null || oldKey.isBlank()) {
                skipped.add(f.getName() + ": 缺少 storageKey");
                continue;
            }
            String rootPrefix = rootPrefixFor(f.getStorageSource());
            String oldPrefix = StorageKeys.normalizePrefix(String.join("/", fullSegments), rootPrefix);
            if (!oldKey.startsWith(oldPrefix)) {
                skipped.add(f.getName() + ": storageKey 与目录不一致");
                continue;
            }
            List<String> newSegments = new ArrayList<>(newRootSegments);
            newSegments.addAll(fullSegments.subList(oldRootSegments.size(), fullSegments.size()));
            String newPrefix = StorageKeys.normalizePrefix(String.join("/", newSegments), rootPrefix);
            String newKey = newPrefix + oldKey.substring(oldPrefix.length());

            // 源对象不存在（UPLOADING 孤儿等）：仅移动 DB 节点，不做对象搬运
            boolean objectMove = svc.stat(oldKey) != null;
            items.add(new MigrationItem(f.getId(), newSegments, oldKey, newKey, objectMove));
        }

        if (items.isEmpty()) {
            openAppService.applyMigration(app.getId(), normalized, List.of(), oldRootSegments);
            return new MigrationResult(0, skipped.size(), skipped);
        }

        // ===== 阶段 1：对象复制（失败即整体回退） =====
        List<MigrationItem> copied = new ArrayList<>();
        try {
            for (MigrationItem item : items) {
                if (!item.objectMove()) continue;
                StoredFile f = storedFileRepository.findById(item.fileId()).orElse(null);
                if (f == null) continue;
                StorageBrowserService svc = Objects.requireNonNull(resolveForSource(f.getStorageSource()));
                svc.copy(item.oldKey(), item.newKey());
                if (svc.stat(item.newKey()) == null) {
                    throw new IllegalStateException("复制后校验失败: " + item.newKey());
                }
                copied.add(item);
            }
        } catch (Exception e) {
            log.error("rootPath 迁移失败，回退已复制对象: appName={}, copied={}", app.getAppName(), copied.size(), e);
            for (MigrationItem item : copied) {
                try {
                    StoredFile f = storedFileRepository.findById(item.fileId()).orElse(null);
                    if (f == null) continue;
                    StorageBrowserService svc = resolveForSource(f.getStorageSource());
                    if (svc != null) svc.delete(item.newKey());
                } catch (Exception ignored) {
                }
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "文件迁移失败，已保持原状: " + e.getMessage());
        }

        // ===== 阶段 2：DB 事务落地（reparent + rootPath 更新 + 清理空旧目录） =====
        openAppService.applyMigration(app.getId(), normalized, items, oldRootSegments);

        // ===== 阶段 3：best-effort 删除旧对象（失败仅告警，孤儿留待清理任务） =====
        for (MigrationItem item : items) {
            if (!item.objectMove()) continue;
            try {
                StoredFile f = storedFileRepository.findById(item.fileId()).orElse(null);
                if (f == null) continue;
                StorageBrowserService svc = resolveForSource(f.getStorageSource());
                if (svc != null) svc.delete(item.oldKey());
            } catch (Exception e) {
                log.warn("迁移后删除旧对象失败（忽略）: key={}, msg={}", item.oldKey(), e.getMessage());
            }
        }

        log.info("rootPath 迁移完成: appName={}, {} -> {}, moved={}, skipped={}",
                app.getAppName(), oldRoot, newRoot, items.size(), skipped.size());
        return new MigrationResult(items.size(), skipped.size(), skipped);
    }

    /** 从根到 parentId 的虚拟目录名字链（parentId 为 null 返回空链） */
    private List<String> folderSegments(Long parentId) {
        List<String> segments = new ArrayList<>();
        Long cursor = parentId;
        while (cursor != null) {
            StoredFile node = storedFileRepository.findById(cursor).orElse(null);
            if (node == null) break;
            segments.add(0, node.getName());
            cursor = node.getParentId();
        }
        return segments;
    }

    private String rootPrefixFor(String source) {
        return "minio".equals(source) ? minioProperties.getPrefix() : ossProperties.getPrefix();
    }

    private StorageBrowserService resolveForSource(String storageSource) {
        if (storageSource == null) return null;
        try {
            return registry.get(storageSource);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
