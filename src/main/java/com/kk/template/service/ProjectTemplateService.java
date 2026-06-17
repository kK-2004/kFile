package com.kk.template.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import com.kk.template.dto.ProjectTemplateRequest;
import com.kk.template.entity.ProjectTemplate;
import com.kk.template.entity.ProjectTemplateAssignment;
import com.kk.template.repo.ProjectTemplateAssignmentRepository;
import com.kk.template.repo.ProjectTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectTemplateService {
    private final ProjectTemplateRepository templateRepository;
    private final ProjectTemplateAssignmentRepository assignmentRepository;
    private final AdminUserRepository userRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public ProjectTemplate save(ProjectTemplateRequest req, Long ownerId) {
        ProjectTemplate t = new ProjectTemplate();
        t.setName(req.getName());
        t.setOwnerId(ownerId);
        applyFields(t, req);
        return templateRepository.save(t);
    }

    @Transactional
    public ProjectTemplate update(Long id, ProjectTemplateRequest req, Long currentUserId) {
        ProjectTemplate t = get(id);
        if (!t.getOwnerId().equals(currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅模板创建者可修改");
        }
        if (req.getName() != null) t.setName(req.getName());
        applyFields(t, req);
        return templateRepository.save(t);
    }

    @Transactional
    public void delete(Long id, Long currentUserId) {
        ProjectTemplate t = get(id);
        if (!t.getOwnerId().equals(currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅模板创建者可删除");
        }
        // 级联清理分配关系（已用该模板创建的项目不受影响）
        assignmentRepository.deleteByTemplate(t);
        templateRepository.delete(t);
    }

    public ProjectTemplate get(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在: " + id));
    }

    public List<ProjectTemplate> findAll() {
        return templateRepository.findAll();
    }

    /**
     * 查询某用户可用的全部模板（owner=自己 ∪ 被分配给自己的）。
     * Web 创建项目下拉与 MCP list_my_templates 共用此方法。
     */
    public List<ProjectTemplate> listUsableForUser(AdminUser user) {
        // 使用 repository 的 union 查询，保证两者一致
        return assignmentRepository.findUsableByUserId(user.getId(), user.getId());
    }

    /**
     * 校验用户对模板可用（owner 或被分配）。
     */
    public boolean isUsable(AdminUser user, ProjectTemplate template) {
        if (template.getOwnerId().equals(user.getId())) return true;
        return assignmentRepository.findByUserAndTemplate(user, template).isPresent();
    }

    // ===== 模板分配 =====

    @Transactional
    public void grant(AdminUser user, ProjectTemplate template) {
        if (assignmentRepository.findByUserAndTemplate(user, template).isEmpty()) {
            ProjectTemplateAssignment a = new ProjectTemplateAssignment();
            a.setUser(user);
            a.setTemplate(template);
            assignmentRepository.save(a);
        }
    }

    @Transactional
    public void revoke(AdminUser user, ProjectTemplate template) {
        assignmentRepository.findByUserAndTemplate(user, template)
                .ifPresent(assignmentRepository::delete);
    }

    public List<Long> listAssignedTemplateIds(AdminUser user) {
        return assignmentRepository.findByUser(user).stream()
                .map(a -> a.getTemplate().getId())
                .toList();
    }

    public Set<ProjectTemplate> listAssignedTemplates(AdminUser user) {
        return new LinkedHashSet<>(
                assignmentRepository.findByUser(user).stream().map(ProjectTemplateAssignment::getTemplate).toList()
        );
    }

    // ===== 内部：可复用字段序列化（沿用 Project 的 ObjectMapper 模式）=====

    private void applyFields(ProjectTemplate t, ProjectTemplateRequest req) {
        try {
            if (req.getExpectedUserFields() != null)
                t.setExpectedUserFields(objectMapper.writeValueAsString(req.getExpectedUserFields()));
            if (req.getPathSegments() != null)
                t.setPathSegments(objectMapper.writeValueAsString(req.getPathSegments()));
            if (req.getAllowedSubmitterKeys() != null)
                t.setAllowedSubmitterKeys(objectMapper.writeValueAsString(req.getAllowedSubmitterKeys()));
            if (req.getAllowedSubmitterList() != null)
                t.setAllowedSubmitterList(objectMapper.writeValueAsString(req.getAllowedSubmitterList()));
            if (req.getAutoFileNamingConfig() != null)
                t.setAutoFileNamingConfig(objectMapper.writeValueAsString(req.getAutoFileNamingConfig()));
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板字段 JSON 无效");
        }
        if (req.getPathFieldKey() != null) t.setPathFieldKey(req.getPathFieldKey());
        if (req.getUserSubmitStatusType() != null) t.setUserSubmitStatusType(req.getUserSubmitStatusType());
        if (req.getUserSubmitStatusText() != null) t.setUserSubmitStatusText(req.getUserSubmitStatusText());
        if (req.getQueryFieldKey() != null) t.setQueryFieldKey(req.getQueryFieldKey());
        if (req.getAutoFileNamingEnabled() != null)
            t.setAutoFileNamingEnabled(Boolean.TRUE.equals(req.getAutoFileNamingEnabled()));
        if (req.getAllowResubmit() != null) t.setAllowResubmit(req.getAllowResubmit());
        if (req.getAllowMultiFiles() != null) t.setAllowMultiFiles(req.getAllowMultiFiles());
        if (req.getAllowOverdue() != null) t.setAllowOverdue(req.getAllowOverdue());
    }
}
