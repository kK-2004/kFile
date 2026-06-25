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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final com.kk.security.repo.AdminUserRepository adminUserRepository;
    private final com.kk.oss.OssService ossService;
    private final com.kk.common.service.AppConfigService appConfigService;
    private final com.kk.share.repo.ShareLinkRepository shareLinkRepository;
    private final com.kk.share.repo.ShareLinkItemRepository shareLinkItemRepository;

    /** 截止提醒调度（仅 app.kmessage.enabled=true 时装配） */
    @Autowired(required = false)
    private ProjectDeadlineReminderService deadlineReminderService;

    @Value("${app.project.monthly-limit.user:3}")
    private int userMonthlyCreateLimitDefault;

    @Transactional
    public Project create(CreateProjectRequest req, Authentication authentication) {
        // 对普通站点用户施加每月创建上限（默认3个）
        boolean isAdmin = false;
        boolean isSuper = false;
        if (authentication != null) {
            for (GrantedAuthority ga : authentication.getAuthorities()) {
                String a = ga.getAuthority();
                if ("ROLE_SUPER".equals(a)) { isAdmin = true; isSuper = true; break; }
                if ("ROLE_ADMIN".equals(a)) { isAdmin = true; break; }
            }
        }
        // 非 SUPER 的 ADMIN 校验当月项目数配额
        if (isAdmin && !isSuper) {
            int monthlyLimit = java.util.Optional.ofNullable(appConfigService.getInt(com.kk.common.service.AppConfigService.KEY_USER_MONTHLY_LIMIT))
                    .orElse(userMonthlyCreateLimitDefault);
            if (monthlyLimit > 0) {
                com.kk.security.entity.AdminUser user = adminUserRepository.findByUsername(authentication.getName()).orElse(null);
                if (user != null) {
                    java.time.ZonedDateTime n = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
                    java.time.ZonedDateTime st = n.withDayOfMonth(1).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC);
                    java.time.ZonedDateTime en = st.plusMonths(1);
                    long used = projectRepository.countByOwnerUserIdAndCreatedAtBetween(user.getId(), st.toInstant(), en.toInstant());
                    if (used >= monthlyLimit) {
                        throw new IllegalStateException("本月项目创建数已达上限（" + monthlyLimit + "），请下月再试或联系管理员");
                    }
                }
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
        p.setDeadlineNotifyEnabled(Boolean.TRUE.equals(req.getDeadlineNotifyEnabled()));
        p.setDeadlineNotifyHours(req.getDeadlineNotifyHours());
        p.setPathFieldKey(req.getPathFieldKey());
        p.setUserSubmitStatusType(req.getUserSubmitStatusType());
        p.setUserSubmitStatusText(req.getUserSubmitStatusText());
        p.setQueryFieldKey(req.getQueryFieldKey());
        p.setTotalSubmitters(0);
        // 记录项目所属 ADMIN（用于配额归属）；SUPER 创建为 null
        if (isAdmin && !isSuper) {
            com.kk.security.entity.AdminUser creator = adminUserRepository.findByUsername(authentication.getName()).orElse(null);
            if (creator != null) p.setOwnerUserId(creator.getId());
        }
        Project saved = projectRepository.save(p);
        grantCreatorPermissionIfAdmin(saved, authentication);
        // 截止时间提醒调度（未启用时 deadlineReminderService 为 null，直接跳过）
        // 项目级开关 deadlineNotifyEnabled 默认关；只有显式开启才创建调度任务
        if (deadlineReminderService != null && Boolean.TRUE.equals(saved.getDeadlineNotifyEnabled())
                && saved.getEndAt() != null) {
            deadlineReminderService.scheduleFor(saved);
        }
        log.info("BIZ action=PROJECT_CREATE projectId={} projectName={} actor={} roles={} isAdmin={}",
                saved.getId(),
                com.kk.common.logging.AuditLogUtil.safe(saved.getName()),
                com.kk.common.logging.AuditLogUtil.actor(authentication),
                com.kk.common.logging.AuditLogUtil.roles(authentication),
                isAdmin);
        return saved;
    }

    private void grantCreatorPermissionIfAdmin(Project project, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return;
        boolean isAdmin = false;
        boolean isSuper = false;
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            String authority = ga.getAuthority();
            if ("ROLE_SUPER".equals(authority)) isSuper = true;
            if ("ROLE_ADMIN".equals(authority)) isAdmin = true;
        }
        if (!isAdmin || isSuper) return;

        adminUserRepository.findByUsername(authentication.getName()).ifPresent(user -> {
            if (Boolean.FALSE.equals(user.getEnabled())) return;
            if (permRepo.findByUserAndProject(user, project).isPresent()) return;
            com.kk.security.entity.ProjectPermission permission = new com.kk.security.entity.ProjectPermission();
            permission.setUser(user);
            permission.setProject(project);
            // ADMIN 自己创建的项目具备全部权限（编辑 + 删除）
            permission.setCanEdit(true);
            permission.setCanDelete(true);
            permRepo.save(permission);
        });
    }

    public java.util.Map<String, Object> getCreationQuota(org.springframework.security.core.Authentication authentication) {
        boolean isSuper = false;
        if (authentication != null) {
            for (GrantedAuthority ga : authentication.getAuthorities()) {
                if ("ROLE_SUPER".equals(ga.getAuthority())) { isSuper = true; break; }
            }
        }

        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
        java.time.ZonedDateTime start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC);
        java.time.ZonedDateTime end = start.plusMonths(1);

        int monthlyLimit = java.util.Optional.ofNullable(appConfigService.getInt(com.kk.common.service.AppConfigService.KEY_USER_MONTHLY_LIMIT))
                .orElse(userMonthlyCreateLimitDefault);
        boolean unlimited = isSuper || monthlyLimit <= 0;
        // 真实已用：当前 ADMIN 本月归属项目数（ProjectPermission + project.createdAt）
        int used = 0;
        if (!isSuper) {
            com.kk.security.entity.AdminUser user = authentication == null ? null
                    : adminUserRepository.findByUsername(authentication.getName()).orElse(null);
            if (user != null) {
                used = (int) projectRepository.countByOwnerUserIdAndCreatedAtBetween(user.getId(), start.toInstant(), end.toInstant());
            }
        }
        Integer limit = unlimited ? null : monthlyLimit;
        Integer remaining = unlimited ? null : Math.max(monthlyLimit - used, 0);
        Long totalQuota = appConfigService.getLong(com.kk.common.service.AppConfigService.KEY_USER_TOTAL_QUOTA_BYTES);
        if (totalQuota == null) totalQuota = 1024L * 1024L * 1024L; // 默认 1GB
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
        Instant oldEndAt = p.getEndAt();
        boolean oldNotify = Boolean.TRUE.equals(p.getDeadlineNotifyEnabled());
        Integer oldHours = p.getDeadlineNotifyHours();
        boolean isAdmin = false;
        if (authentication != null) {
            for (GrantedAuthority ga : authentication.getAuthorities()) {
                String a = ga.getAuthority();
                if ("ROLE_SUPER".equals(a) || "ROLE_ADMIN".equals(a)) { isAdmin = true; break; }
            }
        }
        if (req.getName() != null) p.setName(req.getName());
        if (req.getFileSizeLimitBytes() != null) p.setFileSizeLimitBytes(req.getFileSizeLimitBytes());
        // startAt / endAt 始终应用：项目编辑表单为全量提交，需支持清空截止日期（null 表示无截止）
        p.setStartAt(req.getStartAt() == null ? null : Instant.ofEpochMilli(req.getStartAt()));
        Instant newEndAt = req.getEndAt() == null ? null : Instant.ofEpochMilli(req.getEndAt());
        p.setEndAt(newEndAt);
        if (req.getDeadlineNotifyEnabled() != null) {
            p.setDeadlineNotifyEnabled(Boolean.TRUE.equals(req.getDeadlineNotifyEnabled()));
        }
        if (req.getDeadlineNotifyHours() != null) {
            p.setDeadlineNotifyHours(req.getDeadlineNotifyHours());
        }
        boolean newNotify = Boolean.TRUE.equals(p.getDeadlineNotifyEnabled());
        Integer newHours = p.getDeadlineNotifyHours();
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
        Project updated = projectRepository.save(p);
        // 截止提醒调度同步：
        //  - 开关关、或开关开但 endAt 缺：取消已有任务
        //  - 开关开且 endAt 存在：endAt / 开关 / 提前小时数 任一相对旧值有变动才重新调度
        if (deadlineReminderService != null) {
            boolean endAtChanged = (oldEndAt == null) != (newEndAt == null)
                    || (oldEndAt != null && !oldEndAt.equals(newEndAt));
            boolean notifyChanged = oldNotify != newNotify;
            boolean hoursChanged = (oldHours == null) ? (newHours != null) : !oldHours.equals(newHours);
            if (!newNotify || newEndAt == null) {
                if (notifyChanged || endAtChanged || hoursChanged) {
                    deadlineReminderService.cancelFor(updated);
                }
            } else if (endAtChanged || notifyChanged || hoursChanged) {
                deadlineReminderService.scheduleFor(updated);
            }
        }
        return updated;
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
        // 删除项目前先取消截止提醒任务
        if (deadlineReminderService != null) {
            deadlineReminderService.cancelFor(p);
        }
        // 删除项目权限，避免外键约束错误
        permRepo.deleteByProject(p);
        // 级联删除项目下的全部分享链接（先清子表 share_link_item，避免外键约束失败）
        shareLinkItemRepository.deleteByProjectId(p.getId());
        shareLinkRepository.deleteByProjectId(p.getId());
        submissionRepository.deleteByProject(p);
        projectRepository.delete(p);
    }
}
