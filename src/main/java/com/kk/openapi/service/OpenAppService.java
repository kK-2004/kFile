package com.kk.openapi.service;

import com.kk.openapi.entity.OpenApp;
import com.kk.openapi.repo.OpenAppRepository;
import com.kk.security.oauth.OAuthCrypto;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.StorageKeys;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.entity.StoredFileUpload;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.repo.StoredFileUploadRepository;
import com.kk.storage.service.MultipartUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 开放应用凭证管理：创建（token 一次性明文 + 哈希落库）、轮换、启停、删除（级联清理应用文件）、
 * rootPath 校验，以及 rootPath 变更时迁移的 DB 落地（事务）与应用目录链懒创建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAppService {

    /** appToken 明文前缀（便于识别与密钥扫描） */
    public static final String TOKEN_PREFIX = "kfile_";
    /** 未配置 rootPath 时的默认上传根目录第一段 */
    public static final String DEFAULT_ROOT_DIR = "开放应用";

    private final OpenAppRepository openAppRepository;
    private final OAuthCrypto crypto;
    private final StoredFileRepository storedFileRepository;
    private final StoredFileUploadRepository uploadRepository;
    private final StorageBrowserRegistry registry;
    private final ObjectProvider<MultipartUploadService> multipartUploadService;

    public record CreatedApp(OpenApp app, String token) {}

    public List<OpenApp> list() {
        return openAppRepository.findAllByOrderByCreatedAtDesc();
    }

    public OpenApp requireApp(Long id) {
        return openAppRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在: " + id));
    }

    /** 按鉴权 principal 加载应用（rootPath 可能随时被修改，不缓存实体） */
    public OpenApp requireAppByPrincipal(Long id) {
        return requireApp(id);
    }

    @Transactional
    public CreatedApp create(String appName, String description, String rootPath) {
        if (appName == null || appName.contains("/") || appName.contains("\\")) {
            throw new IllegalArgumentException("应用名不能包含路径分隔符: " + appName);
        }
        String name = StorageKeys.safeName(appName);
        if (openAppRepository.existsByAppName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "应用名已存在: " + name);
        }
        String token = TOKEN_PREFIX + crypto.generateOpaqueToken();
        OpenApp app = new OpenApp();
        app.setAppName(name);
        app.setDescription(description);
        app.setRootPath(normalizeRootPath(rootPath));
        app.setEnabled(true);
        app.setTokenHash(crypto.sha256Hex(token));
        return new CreatedApp(openAppRepository.save(app), token);
    }

    /** 轮换：覆盖 tokenHash，旧 token 立即失效；新明文仅本次返回 */
    @Transactional
    public String rotate(Long id) {
        OpenApp app = requireApp(id);
        String token = TOKEN_PREFIX + crypto.generateOpaqueToken();
        app.setTokenHash(crypto.sha256Hex(token));
        openAppRepository.save(app);
        return token;
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        OpenApp app = requireApp(id);
        app.setEnabled(enabled);
        openAppRepository.save(app);
    }

    @Transactional
    public void updateDescription(Long id, String description) {
        OpenApp app = requireApp(id);
        app.setDescription(description);
        openAppRepository.save(app);
    }

    // ===== 删除（级联清理应用文件） =====

    /** 删除前统计：文件数与总字节数（强确认弹窗展示） */
    public record AppFileStats(long fileCount, long totalBytes) {}

    public AppFileStats stats(Long id) {
        requireApp(id);
        return new AppFileStats(
                storedFileRepository.countByOpenAppIdAndType(id, StoredFile.TYPE_FILE),
                storedFileRepository.sumSizeByOpenAppId(id));
    }

    public record DeleteResult(long deletedFiles, long failedObjects) {}

    /**
     * 删除应用并级联清理其名下全部文件：活跃分片先 abort（服务可用时）→ best-effort 删对象
     * （失败计入 failedObjects 不阻断）→ 删分片残留记录与 FILE 节点 → 清理搬空的应用根路径目录链
     * （共享目录自动保留）→ 删应用记录（token 立即失效）。
     */
    @Transactional
    public DeleteResult deleteApp(Long id) {
        OpenApp app = requireApp(id);
        List<StoredFile> files = storedFileRepository.findByOpenAppId(id);
        long failedObjects = 0;
        for (StoredFile f : files) {
            if (!StoredFile.TYPE_FILE.equals(f.getType())) continue;
            StoredFileUpload upload = uploadRepository.findByStoredFileId(f.getId()).orElse(null);
            if (upload != null && StoredFileUpload.STATUS_UPLOADING.equals(upload.getStatus())) {
                MultipartUploadService mp = multipartUploadService.getIfAvailable();
                if (mp != null) {
                    try {
                        // abort 内部会 abortMultipartUpload + 删记录 + 删节点，本文件无需再处理
                        mp.abort(upload.getContentMd5());
                        continue;
                    } catch (Exception e) {
                        log.warn("删除应用时 abort 分片失败（继续删记录）: appName={}, md5={}, msg={}",
                                app.getAppName(), upload.getContentMd5(), e.getMessage());
                    }
                }
            }
            if (f.getStorageKey() != null) {
                StorageBrowserService svc = resolveForSource(f.getStorageSource());
                if (svc != null) {
                    try {
                        svc.delete(f.getStorageKey());
                    } catch (Exception e) {
                        failedObjects++;
                        log.warn("删除应用时删除对象失败（仍删除 DB 行）: source={}, key={}, msg={}",
                                f.getStorageSource(), f.getStorageKey(), e.getMessage());
                    }
                }
            }
            if (upload != null) {
                uploadRepository.delete(upload);
            }
            storedFileRepository.delete(f);
        }
        // 清理搬空的应用根路径目录链（仍含其他应用/后台文件的共享目录自动保留）
        Long rootId = resolveFolderId(Arrays.asList(effectiveRoot(app).split("/")));
        if (rootId != null) {
            deleteEmptySubtree(rootId);
        }
        openAppRepository.delete(app);
        log.info("开放应用已删除（级联）: appName={}, deletedFiles={}, failedObjects={}",
                app.getAppName(), files.size(), failedObjects);
        return new DeleteResult(files.size(), failedObjects);
    }

    private StorageBrowserService resolveForSource(String storageSource) {
        if (storageSource == null) return null;
        try {
            return registry.get(storageSource);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 校验并归一化 rootPath：blank → null（= 默认「开放应用/<appName>」）；
     * 每段经 {@link StorageKeys#safeName} 校验（拒绝 ..、空段、路径分隔符），非法抛 IllegalArgumentException。
     */
    public static String normalizeRootPath(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) return null;
        List<String> segments = new ArrayList<>();
        for (String seg : rootPath.split("/")) {
            if (seg == null || seg.isBlank()) {
                throw new IllegalArgumentException("rootPath 含空路径段: " + rootPath);
            }
            segments.add(safeSegment(seg));
        }
        return String.join("/", segments);
    }

    /** 路径段校验：显式拒绝 `..`（safeName 会静默净化为 "file"，开放 API 需明确 400） */
    static String safeSegment(String seg) {
        if (seg.contains("..")) {
            throw new IllegalArgumentException("非法的路径段: " + seg);
        }
        return StorageKeys.safeName(seg);
    }

    /** 应用当前生效的上传根路径（斜杠分隔虚拟路径） */
    public static String effectiveRoot(OpenApp app) {
        return (app.getRootPath() != null && !app.getRootPath().isBlank())
                ? app.getRootPath()
                : DEFAULT_ROOT_DIR + "/" + app.getAppName();
    }

    /**
     * 懒创建（幂等）虚拟目录链，返回最深一层目录节点 id；段与段逐级查找/创建 FOLDER 节点。
     */
    public Long ensureFolderChain(List<String> segments) {
        Long parentId = null;
        for (String seg : segments) {
            String safe = StorageKeys.safeName(seg);
            StoredFile folder = (parentId == null)
                    ? storedFileRepository.findByParentIdIsNullAndNameAndType(safe, StoredFile.TYPE_FOLDER).orElse(null)
                    : storedFileRepository.findByParentIdAndNameAndType(parentId, safe, StoredFile.TYPE_FOLDER).orElse(null);
            if (folder == null) {
                folder = new StoredFile();
                folder.setParentId(parentId);
                folder.setName(safe);
                folder.setType(StoredFile.TYPE_FOLDER);
                folder = storedFileRepository.save(folder);
            }
            parentId = folder.getId();
        }
        return parentId;
    }

    /** 迁移 DB 落地（由 {@link OpenAppMigrationService} 在对象复制全部成功后调用）：
     *  懒建新目录链 → 文件节点 reparent + storageKey 改写 → 更新应用 rootPath → 清理搬空的旧目录子树。 */
    @Transactional
    public void applyMigration(Long appId, String normalizedRootPath,
                               List<MigrationItem> items, List<String> oldRootSegments) {
        OpenApp app = requireApp(appId);
        for (MigrationItem item : items) {
            StoredFile f = storedFileRepository.findById(item.fileId()).orElse(null);
            if (f == null) continue;
            Long parentId = ensureFolderChain(item.newSegments());
            f.setParentId(parentId);
            f.setStorageKey(item.newKey());
            storedFileRepository.save(f);
        }
        app.setRootPath(normalizedRootPath);
        openAppRepository.save(app);

        // 清理搬空的旧目录子树（仍有文件/活跃分片的分支自动保留）
        Long oldRootId = resolveFolderId(oldRootSegments);
        if (oldRootId != null) {
            deleteEmptySubtree(oldRootId);
        }
    }

    /** 沿目录链查找已存在的最深节点 id（任一段缺失返回已找到的最后一段的 id；第一段就缺失返回 null） */
    private Long resolveFolderId(List<String> segments) {
        Long parentId = null;
        Long lastFound = null;
        for (String seg : segments) {
            String safe = StorageKeys.safeName(seg);
            StoredFile folder = (parentId == null)
                    ? storedFileRepository.findByParentIdIsNullAndNameAndType(safe, StoredFile.TYPE_FOLDER).orElse(null)
                    : storedFileRepository.findByParentIdAndNameAndType(parentId, safe, StoredFile.TYPE_FOLDER).orElse(null);
            if (folder == null) break;
            lastFound = folder.getId();
            parentId = folder.getId();
        }
        return lastFound;
    }

    /** 自底向上删除空目录子树：仍含子项的节点及其祖先保留 */
    private void deleteEmptySubtree(Long folderId) {
        List<StoredFile> children = storedFileRepository.findByParentId(folderId);
        for (StoredFile child : children) {
            if (StoredFile.TYPE_FOLDER.equals(child.getType())) {
                deleteEmptySubtree(child.getId());
            }
        }
        if (storedFileRepository.findByParentId(folderId).isEmpty()) {
            storedFileRepository.findById(folderId).ifPresent(storedFileRepository::delete);
        }
    }

    /** 迁移单项：文件节点 + 新目录段链 + 新旧 storageKey；objectMove=false 表示仅移动 DB 节点（源对象缺失） */
    public record MigrationItem(Long fileId, List<String> newSegments, String oldKey, String newKey, boolean objectMove) {}
}
