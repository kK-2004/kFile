package com.kk.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.dto.CreateProjectRequest;
import com.kk.project.entity.Project;
import com.kk.project.dto.UpdateProjectRequest;
import com.kk.project.repo.ProjectRepository;
import com.kk.project.repo.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SubmissionRepository submissionRepository;
    private final com.kk.oss.OssService ossService;
    private final com.kk.common.service.AppConfigService appConfigService;
    @Value("${app.project.monthly-limit.user:3}")
    private int userMonthlyCreateLimitDefault;

    @Transactional
    public Project create(CreateProjectRequest req, Authentication authentication) {
        // 对普通站点用户施加每月创建上限（默认3个）
        Long creatorSiteUserId = null;
        boolean isAdmin = false;
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            for (GrantedAuthority ga : jwtAuth.getAuthorities()) {
                String a = ga.getAuthority();
                if ("ROLE_SUPER".equals(a) || "ROLE_SITE_ADMIN".equals(a)) { isAdmin = true; break; }
            }
            try {
                creatorSiteUserId = Long.parseLong(jwtAuth.getToken().getSubject());
            } catch (Exception ignored) {}
        }
        if (!isAdmin && creatorSiteUserId != null) {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
            java.time.ZonedDateTime start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC);
            java.time.ZonedDateTime end = start.plusMonths(1);
            int createdCount = projectRepository.countByCreatorSiteUserIdAndCreatedAtBetween(
                    creatorSiteUserId,
                    start.toInstant(),
                    end.toInstant()
            );
            int monthlyLimit = java.util.Optional.ofNullable(appConfigService.getInt(com.kk.common.service.AppConfigService.KEY_USER_MONTHLY_LIMIT))
                    .orElse(userMonthlyCreateLimitDefault);
            if (monthlyLimit > 0 && createdCount >= monthlyLimit) {
                throw new IllegalStateException("普通用户每月最多创建 " + monthlyLimit + " 个项目");
            }
        }
        Project p = new Project();
        p.setName(req.getName());
        // 非管理员：类型白名单约束（不再限制单文件大小）
        if (!isAdmin) {
            java.util.List<String> allowed = appConfigService.getStringList(com.kk.common.service.AppConfigService.KEY_USER_ALLOWED_FILE_TYPES);
            if (allowed != null && !allowed.isEmpty()) {
                java.util.List<String> reqTypes = req.getAllowedFileTypes();
                if (reqTypes == null || reqTypes.isEmpty()) {
                    // 默认限制为全局白名单
                    req.setAllowedFileTypes(allowed);
                } else {
                    for (String t : reqTypes) {
                        if (t == null) continue;
                        String x = t.trim().toLowerCase();
                        boolean ok = allowed.stream().anyMatch(a -> x.equals(String.valueOf(a).trim().toLowerCase()))
                                || allowed.stream().anyMatch(a -> ("."+x).equals(String.valueOf(a).trim().toLowerCase()));
                        if (!ok) {
                            throw new IllegalArgumentException("不允许的文件类型: " + t + "（仅允许: " + String.join(", ", allowed) + ")");
                        }
                    }
                }
            }
        }
        try {
            p.setAllowedFileTypes(req.getAllowedFileTypes() == null ? null : objectMapper.writeValueAsString(req.getAllowedFileTypes()));
            p.setExpectedUserFields(req.getExpectedUserFields() == null ? null : objectMapper.writeValueAsString(req.getExpectedUserFields()));
            if (req.getPathSegments() != null) {
                p.setPathSegments(objectMapper.writeValueAsString(req.getPathSegments()));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON for project fields", e);
        }
        p.setFileSizeLimitBytes(req.getFileSizeLimitBytes());
        p.setAllowResubmit(Boolean.TRUE.equals(req.getAllowResubmit()));
        p.setAllowMultiFiles(req.getAllowMultiFiles() == null ? true : Boolean.TRUE.equals(req.getAllowMultiFiles()));
        p.setAllowOverdue(Boolean.TRUE.equals(req.getAllowOverdue()));
        p.setStartAt(req.getStartAt() == null ? null : Instant.ofEpochMilli(req.getStartAt()));
        p.setEndAt(req.getEndAt() == null ? null : Instant.ofEpochMilli(req.getEndAt()));
        p.setPathFieldKey(req.getPathFieldKey());
        p.setUserSubmitStatusType(req.getUserSubmitStatusType());
        p.setUserSubmitStatusText(req.getUserSubmitStatusText());
        p.setQueryFieldKey(req.getQueryFieldKey());
        p.setTotalSubmitters(0);
        if (creatorSiteUserId != null) {
            p.setCreatorSiteUserId(creatorSiteUserId);
        }
        return projectRepository.save(p);
    }

    public java.util.Map<String, Object> getCreationQuota(org.springframework.security.core.Authentication authentication) {
        boolean isAdmin = false;
        Long siteUserId = null;
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            for (GrantedAuthority ga : jwtAuth.getAuthorities()) {
                String a = ga.getAuthority();
                if ("ROLE_SUPER".equals(a) || "ROLE_SITE_ADMIN".equals(a)) { isAdmin = true; break; }
            }
            try { siteUserId = Long.parseLong(jwtAuth.getToken().getSubject()); } catch (Exception ignored) {}
        } else if (authentication != null) {
            // 本地管理员会话视为管理员
            for (GrantedAuthority ga : authentication.getAuthorities()) {
                if ("ROLE_SUPER".equals(ga.getAuthority())) { isAdmin = true; break; }
            }
        }

        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
        java.time.ZonedDateTime start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC);
        java.time.ZonedDateTime end = start.plusMonths(1);

        int used = 0;
        if (siteUserId != null) {
            used = projectRepository.countByCreatorSiteUserIdAndCreatedAtBetween(siteUserId, start.toInstant(), end.toInstant());
        }
        int monthlyLimit = java.util.Optional.ofNullable(appConfigService.getInt(com.kk.common.service.AppConfigService.KEY_USER_MONTHLY_LIMIT))
                .orElse(userMonthlyCreateLimitDefault);
        boolean unlimited = isAdmin || monthlyLimit <= 0;
        Integer limit = unlimited ? null : monthlyLimit;
        Integer remaining = unlimited ? null : Math.max(monthlyLimit - used, 0);
        Long totalQuota = appConfigService.getLong(com.kk.common.service.AppConfigService.KEY_USER_TOTAL_QUOTA_BYTES);
        if (totalQuota == null) totalQuota = 1024L * 1024L * 1024L; // 默认 1GB
        return java.util.Map.of(
                "limit", limit,
                "used", used,
                "remaining", remaining,
                "resetAt", end.toInstant().toEpochMilli(),
                "unlimited", unlimited,
                "userTotalQuotaBytes", totalQuota
        );
    }

    public Project get(Long id) {
        return projectRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
    }

    public List<Project> list() {
        return projectRepository.findAll();
    }

    public List<String> parseTypes(Project p) {
        try {
            if (p.getAllowedFileTypes() == null) return null;
            return objectMapper.readValue(p.getAllowedFileTypes(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse allowedFileTypes", e);
        }
    }

    @Transactional
    public Project update(Long id, UpdateProjectRequest req, Authentication authentication) {
        Project p = get(id);
        boolean isAdmin = false;
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            for (GrantedAuthority ga : jwtAuth.getAuthorities()) {
                String a = ga.getAuthority();
                if ("ROLE_SUPER".equals(a) || "ROLE_SITE_ADMIN".equals(a)) { isAdmin = true; break; }
            }
        } else if (authentication != null) {
            for (GrantedAuthority ga : authentication.getAuthorities()) {
                if ("ROLE_SUPER".equals(ga.getAuthority())) { isAdmin = true; break; }
            }
        }
        if (req.getName() != null) p.setName(req.getName());
        if (req.getFileSizeLimitBytes() != null) p.setFileSizeLimitBytes(req.getFileSizeLimitBytes());
        if (req.getStartAt() != null) p.setStartAt(Instant.ofEpochMilli(req.getStartAt()));
        if (req.getEndAt() != null) p.setEndAt(Instant.ofEpochMilli(req.getEndAt()));
        if (req.getAllowResubmit() != null) p.setAllowResubmit(req.getAllowResubmit());
        if (req.getAllowMultiFiles() != null) p.setAllowMultiFiles(req.getAllowMultiFiles());
        if (req.getAllowOverdue() != null) p.setAllowOverdue(req.getAllowOverdue());
        if (req.getOffline() != null) p.setOffline(req.getOffline());
        if (req.getPathFieldKey() != null) p.setPathFieldKey(req.getPathFieldKey());
        if (req.getUserSubmitStatusType() != null) p.setUserSubmitStatusType(req.getUserSubmitStatusType());
        if (req.getUserSubmitStatusText() != null) p.setUserSubmitStatusText(req.getUserSubmitStatusText());
        if (req.getQueryFieldKey() != null) p.setQueryFieldKey(req.getQueryFieldKey());
        // 非管理员约束文件类型（不再限制单文件大小）
        if (!isAdmin) {
            java.util.List<String> allowed = appConfigService.getStringList(com.kk.common.service.AppConfigService.KEY_USER_ALLOWED_FILE_TYPES);
            if (allowed != null && !allowed.isEmpty() && req.getAllowedFileTypes() != null) {
                for (String t : req.getAllowedFileTypes()) {
                    if (t == null) continue;
                    String x = t.trim().toLowerCase();
                    boolean ok = allowed.stream().anyMatch(a -> x.equals(String.valueOf(a).trim().toLowerCase()))
                            || allowed.stream().anyMatch(a -> ("."+x).equals(String.valueOf(a).trim().toLowerCase()));
                    if (!ok) throw new IllegalArgumentException("不允许的文件类型: " + t + "（仅允许: " + String.join(", ", allowed) + ")");
                }
            }
        }

        try {
            if (req.getAllowedFileTypes() != null) p.setAllowedFileTypes(objectMapper.writeValueAsString(req.getAllowedFileTypes()));
            if (req.getExpectedUserFields() != null) p.setExpectedUserFields(objectMapper.writeValueAsString(req.getExpectedUserFields()));
            if (req.getPathSegments() != null) p.setPathSegments(objectMapper.writeValueAsString(req.getPathSegments()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON for project fields", e);
        }
        // 确保立刻持久化（避免某些环境下的延迟刷新导致前端看到旧值）
        return projectRepository.save(p);
    }

    public List<String> parsePathSegments(Project p) {
        try {
            if (p.getPathSegments() != null) {
                return objectMapper.readValue(p.getPathSegments(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            }
            if (p.getPathFieldKey() != null) {
                return java.util.List.of(p.getPathFieldKey());
            }
            return java.util.Collections.emptyList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse pathSegments", e);
        }
    }

    @Transactional
    public void delete(Long id) {
        Project p = get(id);
        // 先收集并删除 OSS 对象，再删除关联提交记录与项目
        try {
            java.util.List<com.kk.project.entity.Submission> list = submissionRepository.findByProject(p);
            java.util.List<String> urls = new java.util.ArrayList<>();
            for (com.kk.project.entity.Submission s : list) {
                try {
                    java.util.List<String> u = objectMapper.readValue(s.getFileUrls(), objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
                    if (u != null) urls.addAll(u);
                } catch (Exception ignored) {}
            }
            if (!urls.isEmpty()) {
                try { ossService.deleteByUrls(urls); } catch (Exception e) { /* 忽略OSS删除失败，继续删除DB */ }
            }
        } catch (Exception ignore) {}
        submissionRepository.deleteByProject(p);
        projectRepository.delete(p);
    }
}
