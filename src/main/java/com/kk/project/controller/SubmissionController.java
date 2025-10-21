package com.kk.project.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.entity.Submission;
import com.kk.project.entity.Project;
import com.kk.project.service.ProjectService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.kk.project.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import com.kk.common.WebClientInfoUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/submissions")
@RequiredArgsConstructor
public class SubmissionController {
    private final SubmissionService submissionService;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> submit(@PathVariable Long projectId,
                                      @RequestPart("submitter") String submitterJson,
                                      @RequestPart("files") List<MultipartFile> files,
                                      HttpServletRequest request) {
        String ip = WebClientInfoUtil.getClientIp(request);
        String ua = request.getHeader("User-Agent");
        Submission s = submissionService.submit(projectId, submitterJson, files, ip, ua);
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", s.getId());
        try {
            resp.put("submitter", objectMapper.readValue(s.getSubmitterInfo(), new TypeReference<Map<String,Object>>(){}));
        } catch (Exception e) {
            resp.put("submitter", s.getSubmitterInfo());
        }
        // 返回解密后的文件名列表，不返回文件URL
        try {
            List<String> urls = objectMapper.readValue(s.getFileUrls(), new TypeReference<List<String>>(){});
            java.util.List<String> names = new java.util.ArrayList<>();
            if (urls != null) {
                for (String u : urls) {
                    String key = submissionService.getOssService().extractObjectKey(u);
                    int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
                    String enc = slash >= 0 ? key.substring(slash + 1) : key;
                    String name = submissionService.getFileNameCodec().decrypt(enc);
                    names.add(name == null || name.isBlank() ? enc : name);
                }
            }
            resp.put("fileNames", names);
        } catch (Exception ignored) {
            resp.put("fileNames", java.util.List.of());
        }
        resp.put("submitCount", s.getSubmitCount());
        resp.put("expired", s.getExpired());
        resp.put("ipAddress", s.getIpAddress());
        resp.put("userAgent", s.getUserAgent());
        resp.put("osName", s.getOsName());
        resp.put("osVersion", s.getOsVersion());
        resp.put("browserName", s.getBrowserName());
        resp.put("browserVersion", s.getBrowserVersion());
        resp.put("deviceType", s.getDeviceType());
        resp.put("ipCountry", s.getIpCountry());
        resp.put("ipProvince", s.getIpProvince());
        resp.put("ipCity", s.getIpCity());
        resp.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toEpochMilli());
        resp.put("updatedAt", s.getUpdatedAt() == null ? null : s.getUpdatedAt().toEpochMilli());
        return resp;
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication.name, #projectId)")
    public Page<com.kk.project.dto.SubmissionResponse> list(@PathVariable Long projectId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        Project p = projectService.get(projectId);
        Page<Submission> pg = submissionService.page(p, PageRequest.of(page, size));
        java.util.List<com.kk.project.dto.SubmissionResponse> mapped = new java.util.ArrayList<>();
        for (Submission s : pg.getContent()) {
            com.kk.project.dto.SubmissionResponse r = com.kk.project.dto.SubmissionResponse.from(s);
            // add decoded file names aligned with fileUrls for admin display
            r.setFileNames(extractDecryptedNames(s));
            mapped.add(r);
        }
        return new org.springframework.data.domain.PageImpl<>(mapped, pg.getPageable(), pg.getTotalElements());
    }

    @GetMapping("/export")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication.name, #projectId)")
    public ResponseEntity<String> exportCsv(@PathVariable Long projectId) {
        Project p = projectService.get(projectId);
        String csv = submissionService.exportCsv(p);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=project-" + projectId + "-submissions.csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(csv);
    }

    @GetMapping("/archive")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication.name, #projectId)")
    public ResponseEntity<StreamingResponseBody> downloadZip(@PathVariable Long projectId,
                                                             @RequestParam(required = false) String fieldKey,
                                                             @RequestParam(required = false) String fieldValue) {
        Project p = projectService.get(projectId);
        StreamingResponseBody body = submissionService.archive(p, fieldKey, fieldValue, null);
        String name = "project-" + projectId + (fieldKey != null && fieldValue != null ? ("-"+fieldKey+"-"+fieldValue): "") + ".zip";
        // ASCII 回退：header 中的 filename 仅允许 ASCII
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_");
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        } catch (Exception e) {
            encoded = ascii;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .body(body);
    }

    @GetMapping("/status")
    public Map<String, Object> latestStatus(@PathVariable Long projectId,
                                            @RequestParam(required = false) String submitter,
                                            @RequestParam(required = false) String fieldValue) {
        Project p = projectService.get(projectId);
        List<Submission> list;
        if (submitter != null && !submitter.isBlank()) {
            // 兼容：直接传 submitter JSON
            list = submissionService.listLatestBySubmitter(p, submitter);
        } else if (fieldValue != null && !fieldValue.isBlank() && p.getQueryFieldKey() != null) {
            // 使用项目配置的查询字段，按值匹配，返回该值对应的最新记录
            list = submissionService.listLatestByFieldValue(p, p.getQueryFieldKey(), fieldValue);
        } else {
            list = java.util.List.of();
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("projectId", projectId);
        if (list.isEmpty()) {
            resp.put("exists", false);
            return resp;
        }
        Submission s = list.get(0);
        resp.put("exists", true);
        resp.put("id", s.getId());
        try {
            resp.put("submitter", objectMapper.readValue(s.getSubmitterInfo(), new TypeReference<Map<String,Object>>(){}));
        } catch (Exception e) {
            resp.put("submitter", s.getSubmitterInfo());
        }
        resp.put("submitCount", s.getSubmitCount());
        resp.put("expired", s.getExpired());
        resp.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toEpochMilli());
        // 返回解密后的最新一次提交的文件名
        resp.put("fileNames", extractDecryptedNames(s));

        // 版本链（从新到旧）
        List<Submission> versionsList;
        if (submitter != null && !submitter.isBlank()) {
            versionsList = submissionService.listLatestBySubmitter(p, submitter);
        } else if (fieldValue != null && !fieldValue.isBlank() && p.getQueryFieldKey() != null) {
            versionsList = submissionService.listAllByFieldValue(p, p.getQueryFieldKey(), fieldValue);
        } else {
            versionsList = java.util.List.of();
        }
        java.util.List<Map<String,Object>> versions = new java.util.ArrayList<>();
        for (Submission it : versionsList) {
            Map<String,Object> v = new java.util.HashMap<>();
            v.put("id", it.getId());
            v.put("createdAt", it.getCreatedAt() == null ? null : it.getCreatedAt().toEpochMilli());
            v.put("fileNames", extractDecryptedNames(it));
            versions.add(v);
        }
        resp.put("versions", versions);
        return resp;
    }

    private java.util.List<String> extractDecryptedNames(Submission s) {
        try {
            List<String> urls = objectMapper.readValue(s.getFileUrls(), new TypeReference<List<String>>(){});
            java.util.List<String> names = new java.util.ArrayList<>();
            if (urls != null) {
                for (String u : urls) {
                    String key = submissionService.getOssService().extractObjectKey(u);
                    int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
                    String enc = slash >= 0 ? key.substring(slash + 1) : key;
                    String name = submissionService.getFileNameCodec().decrypt(enc);
                    names.add(name == null || name.isBlank() ? enc : name);
                }
            }
            return names;
        } catch (Exception ignored) {
            return java.util.List.of();
        }
    }
}
