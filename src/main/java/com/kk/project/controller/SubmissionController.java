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
            resp.put("fileUrls", objectMapper.readValue(s.getFileUrls(), new TypeReference<List<String>>(){}));
        } catch (Exception e) {
            resp.put("submitter", s.getSubmitterInfo());
            resp.put("fileUrls", s.getFileUrls());
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
        resp.put("createdAt", s.getCreatedAt());
        resp.put("updatedAt", s.getUpdatedAt());
        return resp;
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication.name, #projectId)")
    public Page<Submission> list(@PathVariable Long projectId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        Project p = projectService.get(projectId);
        return submissionService.page(p, PageRequest.of(page, size));
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
}
