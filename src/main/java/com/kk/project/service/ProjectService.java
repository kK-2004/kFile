package com.kk.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.dto.CreateProjectRequest;
import com.kk.project.entity.Project;
import com.kk.project.dto.UpdateProjectRequest;
import com.kk.project.repo.ProjectRepository;
import com.kk.project.repo.SubmissionRepository;
import lombok.RequiredArgsConstructor;
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

    @Transactional
    public Project create(CreateProjectRequest req) {
        Project p = new Project();
        p.setName(req.getName());
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
        return projectRepository.save(p);
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
    public Project update(Long id, UpdateProjectRequest req) {
        Project p = get(id);
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
        // 删除关联提交记录，再删除项目
        submissionRepository.deleteByProject(p);
        projectRepository.delete(p);
    }
}
