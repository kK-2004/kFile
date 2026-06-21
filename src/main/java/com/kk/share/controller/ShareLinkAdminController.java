package com.kk.share.controller;

import com.kk.project.entity.Project;
import com.kk.project.repo.ProjectRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import com.kk.share.entity.ShareLink;
import com.kk.share.repo.ShareLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
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
    private final ProjectRepository projectRepository;
    private final AdminUserRepository userRepo;
    private final ProjectPermissionRepository permRepo;

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "pageSize", defaultValue = "15") int pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            Authentication auth) {
        int size = Math.min(Math.max(pageSize, 1), 100);
        int p = Math.max(0, page);
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim().toLowerCase();

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

        // 查 ShareLink
        Page<ShareLink> rawPage;
        if (isSuper) {
            rawPage = shareLinkRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(p, size));
        } else {
            // ADMIN：自己项目的分享 + 文件管理分享(projectId=null)
            // 文件管理分享 projectId=null 也属于该用户（uploader），ADMIN 应该能看到
            rawPage = shareLinkRepository.findByProjectIds(allowedProjectIds, PageRequest.of(p, size));
            // 补充 projectId=null 的（文件管理分享）——单独查再合并（ADMIN 的文件分享也算自己的）
            // 简化：ADMIN 也能看所有 projectId=null 的（文件管理分享量不大）
        }

        // 转换为 DTO + 按项目名过滤 keyword
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (ShareLink link : rawPage.getContent()) {
            String projName = link.getProjectId() != null ? projectNameById.get(link.getProjectId()) : null;
            // 按 keyword 过滤（项目名匹配，或文件管理分享不参与搜索过滤除非无 keyword）
            if (kw != null) {
                if (projName == null || !projName.toLowerCase().contains(kw)) continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", link.getId());
            node.put("code", link.getCode());
            node.put("projectId", link.getProjectId());
            node.put("projectName", projName != null ? projName : "文件管理");
            node.put("createdAt", link.getCreatedAt());
            node.put("expireAt", link.getExpireAt());
            node.put("expired", link.getExpireAt() != null && Instant.now().isAfter(link.getExpireAt()));
            node.put("permanent", link.getExpireAt() == null);
            node.put("downloadCount", link.getDownloadCount() == null ? 0 : link.getDownloadCount());
            // 解析 filename + 文件维度下载量
            List<Map<String, Object>> fileDownloads = new ArrayList<>();
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
            node.put("fileDownloads", fileDownloads);
            nodes.add(node);
        }

        return Map.of(
                "nodes", nodes,
                "page", p,
                "pageSize", size,
                "total", rawPage.getTotalElements(),
                "totalPages", rawPage.getTotalPages()
        );
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
        shareLinkRepository.delete(link);
        return Map.of("ok", true);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
