package com.kk.share.controller;

import com.kk.project.entity.Project;
import com.kk.project.repo.ProjectRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import com.kk.share.entity.ShareLink;
import com.kk.share.entity.ShareLinkItem;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.storage.entity.CdnPreviewLink;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.CdnPreviewLinkRepository;
import com.kk.storage.repo.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * 分享链接管理（SUPER + ADMIN）。
 * SUPER 看所有；ADMIN 只看自己有权限的项目分享 + 文件管理分享(projectId=null)。
 * 支持按项目名搜索。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/shares")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER') or hasRole('ADMIN')")
public class ShareLinkAdminController {

    private final ShareLinkRepository shareLinkRepository;
    private final ShareLinkItemRepository shareLinkItemRepository;
    private final com.kk.share.service.ShareLinkService shareLinkService;
    private final ProjectRepository projectRepository;
    private final AdminUserRepository userRepo;
    private final ProjectPermissionRepository permRepo;
    private final CdnPreviewLinkRepository cdnPreviewLinkRepository;
    private final StoredFileRepository storedFileRepository;

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "pageSize", defaultValue = "15") int pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "shareType", required = false) String shareType,
            Authentication auth) {
        int size = Math.min(Math.max(pageSize, 1), 100);
        int p = Math.max(0, page);
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim().toLowerCase();
        String typeFilter = (shareType == null || shareType.isBlank() || "ALL".equalsIgnoreCase(shareType))
                ? null : shareType.trim().toUpperCase();

        // 判断角色 + 收集允许的 projectId
        AdminUser user = userRepo.findByUsername(auth.getName()).orElse(null);
        boolean isSuper = user != null && "SUPER".equalsIgnoreCase(user.getRole());

        // 拿所有项目（用于按名搜索 + 映射 projectId→name）
        List<Project> allProjects = projectRepository.findAll();
        Map<Long, String> projectNameById = new HashMap<>();
        Set<Long> allowedProjectIds = new HashSet<>();
        for (Project proj : allProjects) {
            projectNameById.put(proj.getId(), proj.getName());
            if (isSuper || proj.getOwnerUserId() != null && user != null && proj.getOwnerUserId().equals(user.getId())) {
                allowedProjectIds.add(proj.getId());
            }
        }
        // ADMIN 还能看被授权（ProjectPermission）的项目
        if (!isSuper && user != null) {
            for (var pp : permRepo.findByUser(user)) {
                allowedProjectIds.add(pp.getProject().getId());
            }
        }

        // 查普通分享链接。合并 CDN 后统一按创建时间分页，保证类型筛选和总数准确。
        List<ShareLink> ordinaryLinks = new ArrayList<>();
        if (isSuper) {
            ordinaryLinks.addAll(shareLinkRepository.findAllByOrderByCreatedAtDesc());
        } else {
            // ADMIN：自己项目的分享 + 文件管理分享(projectId=null)
            if (!allowedProjectIds.isEmpty()) {
                ordinaryLinks.addAll(shareLinkRepository.findByProjectIds(allowedProjectIds, Pageable.unpaged()).getContent());
            }
            // 文件管理分享 projectId=null 也属于该用户（历史行为允许 ADMIN 管理这类链接）。
            ordinaryLinks.addAll(shareLinkRepository.findByProjectIdIsNullOrderByCreatedAtDesc());
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (ShareLink link : ordinaryLinks) {
            String actualType = link.getShareType() == null ? "HISTORY" : link.getShareType();
            if (!matchesType(typeFilter, actualType)) continue;
            String projName = link.getProjectId() != null ? projectNameById.get(link.getProjectId()) : null;
            if (kw != null && (projName == null || !projName.toLowerCase().contains(kw))) continue;
            nodes.add(toShareNode(link, projName));
        }

        List<CdnPreviewLink> cdnLinks = isSuper
                ? cdnPreviewLinkRepository.findAllByOrderByCreatedAtDesc()
                : (user == null ? List.of() : cdnPreviewLinkRepository.findByCreatedByOrderByCreatedAtDesc(user.getId()));
        if (matchesType(typeFilter, "CDN")) {
            for (CdnPreviewLink link : cdnLinks) {
                StoredFile file = storedFileRepository.findById(link.getStoredFileId()).orElse(null);
                String filename = file == null ? "文件已删除" : displayName(file);
                if (kw != null && !filename.toLowerCase().contains(kw)) continue;
                nodes.add(toCdnNode(link, filename));
            }
        }

        nodes.sort(Comparator.comparing(
                node -> (Instant) node.get("createdAt"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        int total = nodes.size();
        int from = (int) Math.min((long) p * size, total);
        int to = Math.min(from + size, total);
        List<Map<String, Object>> pageNodes = nodes.subList(from, to);

        return Map.of(
                "nodes", pageNodes,
                "page", p,
                "pageSize", size,
                "total", total,
                "totalPages", total == 0 ? 0 : (total + size - 1) / size
        );
    }

    private Map<String, Object> toShareNode(ShareLink link, String projectName) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", link.getId());
        node.put("code", link.getCode());
        node.put("projectId", link.getProjectId());
        node.put("projectName", projectName != null ? projectName : "文件管理");
        node.put("shareType", link.getShareType());
        node.put("createdAt", link.getCreatedAt());
        node.put("expireAt", link.getExpireAt());
        node.put("expired", link.getExpireAt() != null && Instant.now().isAfter(link.getExpireAt()));
        node.put("permanent", link.getExpireAt() == null);
        node.put("downloadCount", link.getDownloadCount() == null ? 0 : link.getDownloadCount());

        List<Map<String, Object>> fileDownloads = new ArrayList<>();
        if (link.getShareType() != null) {
            List<ShareLinkItem> items = shareLinkItemRepository.findByShareLinkIdOrderByRelativePath(link.getId());
            node.put("filename", link.getFilename() != null ? link.getFilename() : "download.zip");
            node.put("fileCount", items.size());
            for (ShareLinkItem it : items) {
                String name = it.getRelativePath() == null || it.getRelativePath().isEmpty()
                        ? it.getFilename()
                        : it.getRelativePath() + "/" + it.getFilename();
                if (it.isDeleted()) name = name + "（已删除）";
                fileDownloads.add(Map.of("name", name, "count", it.getDownloadCount()));
            }
        } else {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> data = mapper.readValue(link.getData(), Map.class);
                node.put("filename", data.get("filename"));
                Object entries = data.get("entries");
                if (entries instanceof List<?> list) {
                    node.put("fileCount", list.size());
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> em)) continue;
                        String fname = em.get("f") == null ? "" : String.valueOf(em.get("f"));
                        int cnt = em.get("downloadCount") instanceof Number num ? num.intValue() : 0;
                        fileDownloads.add(Map.of("name", fname, "count", cnt));
                    }
                } else {
                    node.put("fileCount", 0);
                }
            } catch (Exception e) {
                node.put("filename", "未知");
                node.put("fileCount", 0);
            }
        }
        node.put("fileDownloads", fileDownloads);
        return node;
    }

    private Map<String, Object> toCdnNode(CdnPreviewLink link, String filename) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", link.getId());
        node.put("code", link.getToken());
        node.put("projectId", null);
        node.put("projectName", "文件管理");
        node.put("shareType", "CDN");
        node.put("filename", filename);
        node.put("fileCount", 1);
        node.put("downloadCount", 0);
        node.put("fileDownloads", List.of(Map.of("name", filename, "count", 0)));
        node.put("createdAt", link.getCreatedAt());
        node.put("expireAt", link.getExpireAt());
        node.put("expired", link.getExpireAt() != null && Instant.now().isAfter(link.getExpireAt()));
        node.put("permanent", link.getExpireAt() == null);
        return node;
    }

    private boolean matchesType(String filter, String actual) {
        return filter == null || filter.equals(actual);
    }

    private String displayName(StoredFile file) {
        if (file.getOriginalName() != null && !file.getOriginalName().isBlank()) return file.getOriginalName();
        return file.getName() == null || file.getName().isBlank() ? "媒体文件" : file.getName();
    }

    /** 删除分享链接（吊销） */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id, Authentication auth) {
        ShareLink link = shareLinkRepository.findById(id).orElse(null);
        if (link == null) {
            throw new IllegalArgumentException("分享链接不存在: " + id);
        }
        // ADMIN 权限校验：只能删自己项目的分享（projectId 在允许范围内）+ 文件管理分享(projectId=null)
        AdminUser user = userRepo.findByUsername(auth.getName()).orElse(null);
        boolean isSuper = user != null && "SUPER".equalsIgnoreCase(user.getRole());
        if (!isSuper) {
            if (link.getProjectId() != null) {
                boolean allowed = permRepo.findByUserAndProject(user,
                        projectRepository.findById(link.getProjectId()).orElse(null)).isPresent()
                        || (link.getProjectId() != null && projectRepository.findById(link.getProjectId())
                                .map(proj -> user != null && user.getId().equals(proj.getOwnerUserId()))
                                .orElse(false));
                if (!allowed) {
                    throw new IllegalArgumentException("无权删除该分享链接");
                }
            }
            // projectId=null（文件管理分享）ADMIN 可删（自己的）
        }
        // 先清理子表 share_link_item，再删除链接本身（同一事务内，避免外键约束失败）
        shareLinkService.deleteLink(link);
        return Map.of("ok", true);
    }

    /** 删除 CDN 预览链接（吊销）；ADMIN 只能删除自己创建的链接。 */
    @DeleteMapping("/cdn/{id}")
    public Map<String, Object> deleteCdn(@PathVariable Long id, Authentication auth) {
        CdnPreviewLink link = cdnPreviewLinkRepository.findById(id).orElse(null);
        if (link == null) {
            throw new IllegalArgumentException("CDN 链接不存在: " + id);
        }
        AdminUser user = userRepo.findByUsername(auth.getName()).orElse(null);
        boolean isSuper = user != null && "SUPER".equalsIgnoreCase(user.getRole());
        if (!isSuper && (user == null || !Objects.equals(user.getId(), link.getCreatedBy()))) {
            throw new IllegalArgumentException("无权删除该 CDN 链接");
        }
        cdnPreviewLinkRepository.delete(link);
        return Map.of("ok", true);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
