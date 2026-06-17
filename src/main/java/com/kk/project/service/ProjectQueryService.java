package com.kk.project.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.dto.ProjectResponse;
import com.kk.project.entity.Project;
import com.kk.project.entity.Submission;
import com.kk.project.repo.SubmissionRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.ProjectPermission;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目查询服务：把 AdminProjectController 中的 myProjects / missingAllowed 计算逻辑抽离，
 * 供 Web controller 与 MCP 工具共用，避免重复实现。
 */
@Service
@RequiredArgsConstructor
public class ProjectQueryService {
    private final ProjectService projectService;
    private final AdminUserRepository userRepo;
    private final ProjectPermissionRepository permRepo;
    private final SubmissionRepository submissionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 当前用户有权限查看的项目列表。
     * SUPER 返回全部；ADMIN 仅返回被分配给自己的。
     */
    public List<ProjectResponse> myProjects(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        }
        List<Project> projects;
        boolean isSuper = isSuper(auth);
        if (isSuper) {
            projects = projectService.list();
        } else {
            AdminUser user = userRepo.findByUsername(auth.getName()).orElse(null);
            List<ProjectPermission> list = user == null ? List.of() : permRepo.findByUser(user);
            projects = new ArrayList<>();
            for (ProjectPermission pp : list) projects.add(pp.getProject());
        }
        List<ProjectResponse> out = new ArrayList<>();
        for (Project p : projects) {
            out.add(toResponse(p, true));
        }
        return out;
    }

    /** 单个项目详情响应（admin 视图，含 allowedSubmitterList 敏感字段） */
    public ProjectResponse getOne(Long id, boolean includeAllowedList) {
        Project p = projectService.get(id);
        return toResponse(p, includeAllowedList);
    }

    private ProjectResponse toResponse(Project p, boolean includeAllowedList) {
        List<String> types = projectService.parseTypes(p);
        Object expected = null;
        try {
            expected = p.getExpectedUserFields() == null ? null
                    : objectMapper.readValue(p.getExpectedUserFields(), new TypeReference<>() {});
        } catch (Exception ignored) {}
        ProjectResponse response = ProjectResponse.from(p, types, expected, includeAllowedList);
        long totalSubmitters = submissionRepository.countDistinctSubmitters(p);
        response.setTotalSubmitters((int) totalSubmitters);
        return response;
    }

    /**
     * 返回配置了允许提交名单的项目中"尚未提交"的名单。
     * 逻辑与原 AdminProjectController.missingAllowed 完全一致。
     */
    public Map<String, Object> missingAllowed(Long projectId) {
        Project p = projectService.get(projectId);
        List<String> keys;
        Object rawList;
        try {
            keys = p.getAllowedSubmitterKeys() == null ? List.of()
                    : objectMapper.readValue(p.getAllowedSubmitterKeys(), new TypeReference<List<String>>() {});
        } catch (Exception e) { keys = List.of(); }
        try {
            rawList = p.getAllowedSubmitterList() == null ? null
                    : objectMapper.readValue(p.getAllowedSubmitterList(), Object.class);
        } catch (Exception e) { rawList = null; }
        if (keys == null || keys.isEmpty() || rawList == null) {
            return Map.of("enabled", false, "message", "未配置允许提交名单");
        }

        Set<String> allowedTokens = new LinkedHashSet<>();
        boolean composite = keys.size() > 1;
        if (!composite) {
            String k = keys.get(0);
            if (rawList instanceof List<?> list) {
                for (Object o : list) {
                    if (o == null) continue;
                    if (o instanceof String s) {
                        String v = s.trim(); if (!v.isEmpty()) allowedTokens.add(v);
                    } else if (o instanceof Map<?, ?> m) {
                        Object v = m.get(k); if (v != null) { String s = String.valueOf(v).trim(); if (!s.isEmpty()) allowedTokens.add(s); }
                    }
                }
            }
        } else {
            if (rawList instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    List<String> vals = new ArrayList<>();
                    boolean miss = false;
                    for (String k : keys) {
                        Object v = m.get(k);
                        String s = v == null ? "" : String.valueOf(v).trim();
                        if (s.isEmpty()) { miss = true; break; }
                        vals.add(s);
                    }
                    if (!miss) allowedTokens.add(String.join("\u0001", vals));
                }
            }
        }

        Set<String> submittedTokens = new HashSet<>();
        List<Submission> all = submissionRepository.findVisibleByProjectOrderByCreatedAtDesc(p);
        for (Submission s : all) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                if (node == null) continue;
                if (!composite) {
                    String k = keys.get(0);
                    String v = node.has(k) && !node.get(k).isNull() ? node.get(k).asText("").trim() : "";
                    if (!v.isEmpty()) submittedTokens.add(v);
                } else {
                    List<String> vals = new ArrayList<>();
                    boolean miss = false;
                    for (String k : keys) {
                        String v = node.has(k) && !node.get(k).isNull() ? node.get(k).asText("").trim() : "";
                        if (v.isEmpty()) { miss = true; break; }
                        vals.add(v);
                    }
                    if (!miss) submittedTokens.add(String.join("\u0001", vals));
                }
            } catch (Exception ignored) {}
        }

        List<Map<String, String>> missing = new ArrayList<>();
        for (String t : allowedTokens) {
            if (submittedTokens.contains(t)) continue;
            if (!composite) {
                missing.add(Map.of(keys.get(0), t));
            } else {
                String[] parts = t.split("\u0001", -1);
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < keys.size() && i < parts.length; i++) m.put(keys.get(i), parts[i]);
                missing.add(m);
            }
        }
        return Map.of(
                "enabled", true,
                "keys", keys,
                "composite", composite,
                "missingCount", missing.size(),
                "missing", missing
        );
    }

    public static boolean isSuper(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_SUPER".equals(ga.getAuthority())) return true;
        }
        return false;
    }
}
