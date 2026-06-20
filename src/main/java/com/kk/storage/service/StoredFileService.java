package com.kk.storage.service;

import com.kk.config.MinioProperties;
import com.kk.config.OssProperties;
import com.kk.share.service.ShareLinkService;
import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.StorageKeys;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DB 虚拟文件树操作：目录浏览/创建文件夹/上传/递归删除/预签名/分享。
 * 文件夹层级与对象存储真实扁平结构解耦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoredFileService {

    /** 浏览器直传预签名 PUT URL 有效期（秒） */
    private static final long DEFAULT_DIRECT_EXPIRE_SECONDS = 600L;

    private final StoredFileRepository storedFileRepository;
    private final com.kk.storage.repo.StoredFileUploadRepository uploadRepository;
    private final StorageBrowserRegistry registry;
    private final ShareLinkService shareLinkService;
    private final MinioProperties minioProperties;
    private final OssProperties ossProperties;
    private final com.kk.security.repo.AdminUserRepository adminUserRepository;
    private final com.kk.project.repo.SubmissionRepository submissionRepository;
    private final com.kk.common.service.AppConfigService appConfigService;

    // ===== 可用数据源 =====

    /** 返回当前已启用的数据源列表（供前端上传时选择） */
    public List<StorageBrowserRegistry.Source> availableSources() {
        return registry.sources();
    }

    // ===== 浏览 =====

    /** 列出某目录的直接子项（文件夹优先、按名排序），分页；按 uploaderId 过滤 + 可选关键词 */
    public org.springframework.data.domain.Page<StoredFile> listChildren(Long parentId, Long uploaderId, String keyword, int page, int pageSize) {
        validateParentExists(parentId, uploaderId);
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        return storedFileRepository.listChildren(parentId, uploaderId, kw,
                org.springframework.data.domain.PageRequest.of(page, pageSize));
    }

    /** 从根到当前节点的面包屑路径 */
    public List<PathCrumb> breadcrumb(Long parentId) {
        List<PathCrumb> path = new ArrayList<>();
        Long cursor = parentId;
        while (cursor != null) {
            Optional<StoredFile> opt = storedFileRepository.findById(cursor);
            if (opt.isEmpty()) break;
            StoredFile node = opt.get();
            path.add(0, new PathCrumb(node.getId(), node.getName(), node.getType()));
            cursor = node.getParentId();
        }
        return path;
    }

    // ===== 创建文件夹 =====

    @Transactional
    public StoredFile mkdir(Long parentId, String name, Long uploaderId) {
        String safe = StorageKeys.safeName(name);
        validateParentExists(parentId, uploaderId);
        Optional<StoredFile> dup = parentId == null
                ? storedFileRepository.findByParentIdIsNullAndNameAndType(safe, StoredFile.TYPE_FOLDER)
                : storedFileRepository.findByParentIdAndNameAndType(parentId, safe, StoredFile.TYPE_FOLDER);
        if (dup.isPresent()) {
            throw new ConflictException("同名文件夹已存在: " + safe);
        }
        StoredFile folder = new StoredFile();
        folder.setParentId(parentId);
        folder.setUploaderId(uploaderId);
        folder.setName(safe);
        folder.setType(StoredFile.TYPE_FOLDER);
        return storedFileRepository.save(folder);
    }

    // ===== 上传（浏览器直传，对称 OSS 用户端 direct-init / direct-complete）=====

    /**
     * 第一步：签发直传 PUT 预签名直链 + 预生成的 storageKey。
     * storageKey 规则对齐 OSS 用户端直传：
     * {@code <rootPrefix>/<虚拟文件夹路径>/<timestamp-uuid>/<真实文件名.ext>}
     * 前端拿到 putUrl + storageKey 后直接 PUT 到对象存储，不经过后端。
     */
    public DirectUploadInit initUpload(Long parentId, String source, String originalName, String contentType, Long uploaderId) {
        validateParentExists(parentId, uploaderId);
        // 上传前校验配额（ADMIN）
        checkQuota(uploaderId, 0);
        StorageBrowserService svc = resolveUploadService(source);
        String rootPrefix = svc.sourceId().equals("minio") ? minioProperties.getPrefix() : ossProperties.getPrefix();
        String folderPath = resolveFolderPath(parentId);
        String storageKey = StorageKeys.buildDirectUploadKey(rootPrefix, folderPath, originalName);
        String putUrl = svc.presignedPutUrl(storageKey, DEFAULT_DIRECT_EXPIRE_SECONDS, contentType);
        return new DirectUploadInit(storageKey, svc.sourceId(), putUrl, DEFAULT_DIRECT_EXPIRE_SECONDS);
    }

    @Transactional
    public StoredFile completeUpload(Long parentId, String source, String storageKey, String originalName, String contentType, Long size, Long uploaderId) {
        validateParentExists(parentId, uploaderId);
        StorageBrowserService svc = resolveUploadService(source);
        // 不再 stat（部分 MinIO 配置下 stat 返回 AccessDenied）；直接用前端传的 size
        long fileSize = size != null ? size : 0L;
        // 落库前校验配额
        checkQuota(uploaderId, fileSize);
        StoredFile f = new StoredFile();
        f.setParentId(parentId);
        f.setUploaderId(uploaderId);
        f.setName(StorageKeys.baseName(originalName));
        f.setType(StoredFile.TYPE_FILE);
        f.setStorageSource(svc.sourceId());
        f.setStorageKey(storageKey);
        f.setOriginalName(originalName);
        f.setSize(fileSize);
        f.setContentType(contentType);
        f.setStatus(StoredFile.STATUS_UPLOADED);
        return storedFileRepository.save(f);
    }

    // ===== 删除（递归） =====

    @Transactional
    public DeleteResult delete(Long id, Long actorId) {
        StoredFile node = storedFileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + id));
        // 非 owner 不能删（actorId=null 表示 SUPER，不限）
        if (actorId != null && node.getUploaderId() != null && !actorId.equals(node.getUploaderId())) {
            throw new IllegalArgumentException("无权删除该文件");
        }
        int[] counters = {0, 0}; // {deletedDb, failedObject}
        deleteRecursive(node, counters);
        return new DeleteResult(counters[0], counters[1]);
    }

    private void deleteRecursive(StoredFile node, int[] counters) {
        if (StoredFile.TYPE_FOLDER.equals(node.getType())) {
            List<StoredFile> children = storedFileRepository.findByParentId(node.getId());
            for (StoredFile c : children) {
                deleteRecursive(c, counters);
            }
        } else {
            // 文件：删对象
            StorageBrowserService svc = resolveForSource(node.getStorageSource());
            if (svc != null) {
                try {
                    svc.delete(node.getStorageKey());
                } catch (Exception e) {
                    counters[1]++;
                    log.warn("删除对象失败（仍删除 DB 行）: source={}, key={}, msg={}",
                            node.getStorageSource(), node.getStorageKey(), e.getMessage());
                }
            }
            // 删关联的分片上传记录（避免孤儿）
            uploadRepository.findByStoredFileId(node.getId()).ifPresent(uploadRepository::delete);
        }
        storedFileRepository.delete(node);
        counters[0]++;
    }

    // ===== 下载 =====

    public String downloadUrl(Long fileId, boolean download, long expireSeconds, Long actorId) {
        StoredFile f = storedFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));
        if (!StoredFile.TYPE_FILE.equals(f.getType())) {
            throw new IllegalArgumentException("文件夹不支持下载");
        }
        // 非 owner 不能下载（actorId=null 表示 SUPER，不限）
        if (actorId != null && f.getUploaderId() != null && !actorId.equals(f.getUploaderId())) {
            throw new IllegalArgumentException("无权下载该文件");
        }
        return resolveForSource(f.getStorageSource())
                .downloadUrl(f.getStorageKey(), download, expireSeconds, f.getOriginalName());
    }

    // ===== 分享（复用 ShareLinkService） =====

    public ShareLinkService.CreatedShare createShare(List<Long> nodeIds, Long expireSeconds, String filename, Long actorId) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个文件或文件夹");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean permanent = expireSeconds != null && expireSeconds <= 0;
        long exp = permanent ? 0 : (expireSeconds != null && expireSeconds > 0 ? expireSeconds : 300);
        for (Long nid : nodeIds) {
            StoredFile node = storedFileRepository.findById(nid)
                    .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + nid));
            // 非 owner 不能分享（actorId=null 表示 SUPER）
            if (actorId != null && node.getUploaderId() != null && !actorId.equals(node.getUploaderId())) {
                throw new IllegalArgumentException("无权分享该文件: " + node.getName());
            }
            if (StoredFile.TYPE_FOLDER.equals(node.getType())) {
                collectShareEntries(node, node.getName(), entries, exp, permanent);
            } else {
                entries.add(buildShareEntry(node, "", exp, permanent));
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("所选文件夹下没有文件");
        }
        String name = (filename != null && !filename.isBlank()) ? filename : "files.zip";
        return shareLinkService.create(null, name, entries, permanent ? null : exp);
    }

    /** 构造单文件的分享 entry；permanent 时 u 留空（读取时现签），额外存 storageSource + storageKey */
    private Map<String, Object> buildShareEntry(StoredFile node, String pathPrefix, long exp, boolean permanent) {
        Map<String, Object> entry = new HashMap<>();
        if (permanent) {
            entry.put("u", ""); // 永久分享：读取时现签
        } else {
            entry.put("u", resolveForSource(node.getStorageSource())
                    .downloadUrl(node.getStorageKey(), true, exp, node.getOriginalName()));
        }
        entry.put("f", StorageKeys.baseName(node.getOriginalName()));
        entry.put("p", pathPrefix);
        entry.put("s", node.getSize());
        // 后端内部用于永久分享现签；getShare 返回前端时过滤掉
        entry.put("storageSource", node.getStorageSource());
        entry.put("storageKey", node.getStorageKey());
        return entry;
    }

    /** 递归收集文件夹下的文件，构造带相对路径 p 的分享 entry */
    private void collectShareEntries(StoredFile folder, String pathPrefix, List<Map<String, Object>> entries, long exp, boolean permanent) {
        List<StoredFile> children = storedFileRepository.findByParentId(folder.getId());
        for (StoredFile c : children) {
            if (StoredFile.TYPE_FOLDER.equals(c.getType())) {
                collectShareEntries(c, pathPrefix + "/" + c.getName(), entries, exp, permanent);
            } else {
                entries.add(buildShareEntry(c, pathPrefix, exp, permanent));
            }
        }
    }

    // ===== helpers =====

    /** 从根到 parentId 的虚拟文件夹名字链（不含根前缀），根目录返回 ""。 */
    public String resolveFolderPath(Long parentId) {
        if (parentId == null) return "";
        // 复用 breadcrumb：从根到当前节点的名字列表
        List<PathCrumb> crumbs = breadcrumb(parentId);
        if (crumbs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (PathCrumb c : crumbs) {
            if (sb.length() > 0) sb.append('/');
            sb.append(c.name());
        }
        return sb.toString();
    }

    public void validateParentExists(Long parentId, Long uploaderId) {
        if (parentId == null) return;
        StoredFile parent = storedFileRepository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("目标文件夹不存在: " + parentId));
        if (!StoredFile.TYPE_FOLDER.equals(parent.getType())) {
            throw new IllegalArgumentException("目标节点不是文件夹");
        }
        // 非 owner 不能在他人文件夹内操作（uploaderId=null 表示 SUPER，不限）
        if (uploaderId != null && parent.getUploaderId() != null && !uploaderId.equals(parent.getUploaderId())) {
            throw new IllegalArgumentException("无权操作该文件夹");
        }
    }

    /** ADMIN 配额校验：incBytes 为本次新增字节数；SUPER 不限。
     *  usedSpace = 文件管理 StoredFile 总size + 项目提交文件总size（共用额度） */
    public void checkQuota(Long uploaderId, long incBytes) {
        if (uploaderId == null) return;
        com.kk.security.entity.AdminUser user = adminUserRepository.findById(uploaderId).orElse(null);
        if (user == null) return;
        if ("SUPER".equalsIgnoreCase(user.getRole())) return; // SUPER 不受限
        Long quota = resolveQuota(user);
        if (quota == null || quota <= 0) return; // 未设配额 = 不限
        long used = usedSpace(uploaderId);
        if (used + incBytes > quota) {
            throw new IllegalArgumentException("存储空间不足：已用 " + formatBytes(used) + "，配额 " + formatBytes(quota));
        }
    }

    /** 返回某用户的已用空间（字节）= 文件管理 + 项目提交 */
    public long usedSpace(Long uploaderId) {
        if (uploaderId == null) return 0;
        long fileMgmt = storedFileRepository.sumSizeByUploader(uploaderId);
        long submissions = submissionRepository.sumTotalSizeByProjectOwner(uploaderId);
        return fileMgmt + submissions;
    }

    /** 解析有效配额：个人 quotaBytes 优先，null 时 fallback 全局 KEY_USER_TOTAL_QUOTA_BYTES */
    private Long resolveQuota(com.kk.security.entity.AdminUser user) {
        if (user.getQuotaBytes() != null) return user.getQuotaBytes();
        return appConfigService.getLong(com.kk.common.service.AppConfigService.KEY_USER_TOTAL_QUOTA_BYTES);
    }

    private static String formatBytes(long b) {
        if (b >= 1024 * 1024 * 1024) return String.format("%.1f GB", b / 1024.0 / 1024 / 1024);
        if (b >= 1024 * 1024) return String.format("%.1f MB", b / 1024.0 / 1024);
        if (b >= 1024) return String.format("%.1f KB", b / 1024.0);
        return b + " B";
    }

    /** 解析上传源；未启用/未配置抛 IllegalArgumentException（Controller 转 400） */
    private StorageBrowserService resolveUploadService(String source) {
        try {
            return registry.get(source);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("上传存储源不可用或未启用: " + source);
        }
    }

    private StorageBrowserService resolveForSource(String storageSource) {
        if (storageSource == null) return null;
        try {
            return registry.get(storageSource);
        } catch (IllegalArgumentException e) {
            log.warn("存储源未启用: {}", storageSource);
            return null;
        }
    }

    // ===== DTOs =====

    public record PathCrumb(Long id, String name, String type) {}

    public record DeleteResult(int deletedDb, int failedObjects) {}

    /** 浏览器直传初始化结果：预生成的 storageKey + 直传 PUT 直链 */
    public record DirectUploadInit(String storageKey, String storageSource, String putUrl, long expireSeconds) {}

    /** 同名冲突（Controller 转 409） */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String msg) { super(msg); }
    }
}
