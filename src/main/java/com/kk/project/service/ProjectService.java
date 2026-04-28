package com.kk.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.dto.CreateProjectRequest;
import com.kk.project.entity.Project;
import com.kk.project.dto.UpdateProjectRequest;
import com.kk.project.repo.ProjectRepository;
import com.kk.project.repo.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SubmissionRepository submissionRepository;
    private final com.kk.security.repo.ProjectPermissionRepository permRepo;
    private final com.kk.oss.OssService ossService;
    private final com.kk.common.service.AppConfigService appConfigService;
    @Value("${app.project.monthly-limit.user:3}")
    private int userMonthlyCreateLimitDefault;

    @Transactional
    public Project create(CreateProjectRequest req, Authentication authentication) {
        // 对普通站点用户施加每月创建上限（默认3个）
        boolean isAdmin = false;
        if (authentication != null) {
            for (GrantedAuthority ga : authentication.getAuthorities()) {
                String a = ga.getAuthority();
                if ("ROLE_SUPER".equals(a) || "ROLE_ADMIN".equals(a)) { isAdmin = true; break; }
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
            if (req.getAllowedSubmitterKeys() != null) {
                p.setAllowedSubmitterKeys(objectMapper.writeValueAsString(req.getAllowedSubmitterKeys()));
            }
            if (req.getAllowedSubmitterList() != null) {
                p.setAllowedSubmitterList(objectMapper.writeValueAsString(req.getAllowedSubmitterList()));
            }
            // 自动命名文件配置
            p.setAutoFileNamingEnabled(Boolean.TRUE.equals(req.getAutoFileNamingEnabled()));
            if (req.getAutoFileNamingConfig() != null) {
                p.setAutoFileNamingConfig(objectMapper.writeValueAsString(req.getAutoFileNamingConfig()));
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
        Project saved = projectRepository.save(p);
        log.info("BIZ action=PROJECT_CREATE projectId={} projectName={} actor={} roles={} isAdmin={}",
                saved.getId(),
                com.kk.common.logging.AuditLogUtil.safe(saved.getName()),
                com.kk.common.logging.AuditLogUtil.actor(authentication),
                com.kk.common.logging.AuditLogUtil.roles(authentication),
                isAdmin);
        return saved;
    }

    public java.util.Map<String, Object> getCreationQuota(org.springframework.security.core.Authentication authentication) {
        boolean isAdmin = false;
        if (authentication != null) {
            for (GrantedAuthority ga : authentication.getAuthorities()) {
                if ("ROLE_SUPER".equals(ga.getAuthority())) { isAdmin = true; break; }
            }
        }

        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
        java.time.ZonedDateTime start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC);
        java.time.ZonedDateTime end = start.plusMonths(1);

        int used = 0;
        int monthlyLimit = java.util.Optional.ofNullable(appConfigService.getInt(com.kk.common.service.AppConfigService.KEY_USER_MONTHLY_LIMIT))
                .orElse(userMonthlyCreateLimitDefault);
        boolean unlimited = isAdmin || monthlyLimit <= 0;
        Integer limit = unlimited ? null : monthlyLimit;
        Integer remaining = unlimited ? null : Math.max(monthlyLimit - used, 0);
        Long totalQuota = appConfigService.getLong(com.kk.common.service.AppConfigService.KEY_USER_TOTAL_QUOTA_BYTES);
        if (totalQuota == null) totalQuota = 1024L * 1024L * 1024L; // 默认 1GB
        // Map.of 不允许 null 值，这里需要兼容 unlimited 场景下的 null limit/remaining
        java.util.Map<String,Object> resp = new java.util.HashMap<>();
        resp.put("limit", limit);
        resp.put("used", used);
        resp.put("remaining", remaining);
        resp.put("resetAt", end.toInstant().toEpochMilli());
        resp.put("unlimited", unlimited);
        resp.put("userTotalQuotaBytes", totalQuota);
        return resp;
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
        if (authentication != null) {
            for (GrantedAuthority ga : authentication.getAuthorities()) {
                String a = ga.getAuthority();
                if ("ROLE_SUPER".equals(a) || "ROLE_ADMIN".equals(a)) { isAdmin = true; break; }
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
            if (req.getAllowedSubmitterKeys() != null) p.setAllowedSubmitterKeys(objectMapper.writeValueAsString(req.getAllowedSubmitterKeys()));
            if (req.getAllowedSubmitterList() != null) p.setAllowedSubmitterList(objectMapper.writeValueAsString(req.getAllowedSubmitterList()));
            if (req.getAutoFileNamingEnabled() != null) p.setAutoFileNamingEnabled(Boolean.TRUE.equals(req.getAutoFileNamingEnabled()));
            if (req.getAutoFileNamingConfig() != null) p.setAutoFileNamingConfig(objectMapper.writeValueAsString(req.getAutoFileNamingConfig()));
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
        int submissionCount = 0;
        int fileCount = 0;
        // 先收集并删除 OSS 对象，再删除关联提交记录与项目
        try {
            java.util.List<com.kk.project.entity.Submission> list = submissionRepository.findByProject(p);
            submissionCount = list == null ? 0 : list.size();
            java.util.List<String> urls = new java.util.ArrayList<>();
            for (com.kk.project.entity.Submission s : list) {
                try {
                    java.util.List<String> u = objectMapper.readValue(s.getFileUrls(), objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
                    if (u != null) urls.addAll(u);
                } catch (Exception ignored) {}
            }
            fileCount = urls.size();
            if (!urls.isEmpty()) {
                try { ossService.deleteByUrls(urls); } catch (Exception e) { /* 忽略OSS删除失败，继续删除DB */ }
            }
        } catch (Exception ignore) {}
        log.info("BIZ action=PROJECT_DELETE projectId={} projectName={} submissions={} files={}",
                p.getId(), com.kk.common.logging.AuditLogUtil.safe(p.getName()), submissionCount, fileCount);
        // 删除项目权限，避免外键约束错误
        permRepo.deleteByProject(p);
        submissionRepository.deleteByProject(p);
        projectRepository.delete(p);
    }
}
