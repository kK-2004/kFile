package com.kk.admin.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.entity.Project;
import com.kk.project.entity.Submission;
import com.kk.project.repo.ProjectRepository;
import com.kk.project.repo.SubmissionRepository;
import com.kk.project.service.ProjectService;
import com.kk.project.service.SubmissionService;
import com.kk.oss.OssService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

import java.util.*;

@RestController
@RequestMapping("/api/admin/submissions")
@RequiredArgsConstructor
public class AdminSubmissionController {
    private final SubmissionRepository submissionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final SubmissionService submissionService;
    private final OssService ossService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 手动上传（统一入口）：
    // - multipart/form-data: 与用户端一致（projectId, submitter, files）
    // - 非 multipart（如 application/octet-stream）: 作为单文件流处理，projectId & submitter 走 query 参数
    @PostMapping(path = "/manual-upload")
    @PreAuthorize("hasRole('SUPER') or @adminPermissionService.canManageProject(authentication, #projectId)")
    public Map<String, Object> manualUploadUnified(HttpServletRequest request,
                                                   @RequestParam(value = "projectId", required = false) Long projectId,
                                                   @RequestParam(value = "submitter", required = false) String submitterParam) {
        String ct = Optional.ofNullable(request.getContentType()).orElse("").toLowerCase();
        boolean isMultipart = ct.startsWith("multipart/");

        if (isMultipart) {
            // 直接从 Multipart 请求中取参数与文件
            String submitterJson;
            java.util.List<MultipartFile> files;
            if (request instanceof org.springframework.web.multipart.MultipartHttpServletRequest mreq) {
                String p = mreq.getParameter("projectId");
                try { projectId = p == null ? null : Long.parseLong(p); } catch (Exception e) { projectId = null; }
                submitterJson = mreq.getParameter("submitter");
                files = mreq.getFiles("files");
            } else {
                // 兼容某些容器未包裹的情况
                String p = request.getParameter("projectId");
                try { projectId = p == null ? null : Long.parseLong(p); } catch (Exception e) { projectId = null; }
                submitterJson = request.getParameter("submitter");
                files = java.util.List.of();
            }
            if (projectId == null) throw new IllegalArgumentException("projectId 不能为空");
            if (submitterJson == null) submitterJson = "{}";
            if (files == null || files.isEmpty()) throw new IllegalArgumentException("请至少上传一个文件");
            Project project = projectService.get(projectId);
            String keyPrefix = submissionService.buildUploadPrefix(project, submitterJson);
            List<String> urls = ossService.uploadWithPrefix(files, keyPrefix);
            return finalizeManualSubmission(project, submitterJson, urls, request);
        } else {
            // 流式单文件
            Long pid = projectId;
            String submitterJson = submitterParam;
            if (pid == null) {
                String p = request.getParameter("projectId");
                try { pid = p == null ? null : Long.parseLong(p); } catch (Exception ignored) {}
            }
            if (submitterJson == null) submitterJson = Optional.ofNullable(request.getParameter("submitter")).orElse("{}");
            if (pid == null) throw new IllegalArgumentException("projectId 不能为空");
            Project project = projectService.get(pid);
            String keyPrefix = submissionService.buildUploadPrefix(project, submitterJson);
            String contentType = Optional.ofNullable(request.getContentType()).orElse("application/octet-stream");
            String originalName = Optional.ofNullable(request.getHeader("X-Filename"))
                    .or(() -> Optional.ofNullable(request.getHeader("X-File-Name")))
                    .orElse("file");
            try (java.io.InputStream in = request.getInputStream()) {
                long size = request.getContentLengthLong();
                String url = ossService.uploadStreamWithPrefix(in, size, contentType, originalName, keyPrefix);
                return finalizeManualSubmission(project, submitterJson, java.util.List.of(url), request);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取上传数据失败", e);
            }
        }
    }

    private Map<String, Object> finalizeManualSubmission(Project project,
                                                         String submitterJson,
                                                         List<String> urls,
                                                         HttpServletRequest request) {
        String ip = com.kk.common.WebClientInfoUtil.getClientIp(request);
        String ua = request.getHeader("User-Agent");

        Submission s = new Submission();
        // 规范化提交者：尽力解析 JSON 并稳定排序；失败则回退为 {}
        String canonical;
        try {
            String raw = (submitterJson == null || submitterJson.isBlank()) ? "{}" : submitterJson;
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(raw);
            // 按键名排序，保持稳定字符串
            java.util.TreeMap<String, com.fasterxml.jackson.databind.JsonNode> map = new java.util.TreeMap<>();
            if (node != null && node.isObject()) {
                node.fieldNames().forEachRemaining(fn -> map.put(fn, node.get(fn)));
                canonical = objectMapper.writeValueAsString(map);
            } else {
                canonical = objectMapper.writeValueAsString(node);
            }
        } catch (Exception ignore) {
            canonical = "{}";
        }
        String fp = org.springframework.util.DigestUtils.md5DigestAsHex(
                canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        s.setSubmitterInfo(canonical);
        s.setSubmitterFingerprint(fp);
        s.setProject(project);
        s.setIpAddress(ip);
        s.setUserAgent(ua);
        // 尽力解析UA（非强制）
        try {
            com.kk.common.UserAgentUtil.ParsedUA parsedUA = com.kk.common.UserAgentUtil.parse(ua);
            s.setOsName(parsedUA.osName());
            s.setOsVersion(parsedUA.osVersion());
            s.setBrowserName(parsedUA.browserName());
            s.setBrowserVersion(parsedUA.browserVersion());
            s.setDeviceType(parsedUA.deviceType());
        } catch (Exception ignored) {}
        // GeoIP（非强制）
        try {
            com.kk.geoip.GeoInfo geo = ((com.kk.geoip.GeoIpService) getField(submissionService, "geoIpService")).lookup(ip);
            s.setIpCountry(geo.getCountry());
            s.setIpProvince(geo.getProvince());
            s.setIpCity(geo.getCity());
        } catch (Exception ignored) {}
        try { s.setFileUrls(objectMapper.writeValueAsString(urls)); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize file urls", e); }

        long exist = submissionRepository.countByProjectAndSubmitterFingerprint(project, s.getSubmitterFingerprint());
        s.setSubmitCount((int) (exist + 1));
        s.setValid(true);

        Submission saved = submissionRepository.save(s);
        long distinctSubmitters = submissionRepository.countDistinctSubmitters(project);
        project.setTotalSubmitters((int) distinctSubmitters);
        projectRepository.save(project);

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", saved.getId());
        try { resp.put("submitter", objectMapper.readValue(saved.getSubmitterInfo(), new TypeReference<Map<String,Object>>(){})); }
        catch (Exception e) { resp.put("submitter", saved.getSubmitterInfo()); }
        // 返回文件名
        try {
            List<String> names = new ArrayList<>();
            List<String> fs = objectMapper.readValue(saved.getFileUrls(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            var codec = ((com.kk.common.FileNameCodec) getField(submissionService, "fileNameCodec"));
            var oss = ((com.kk.oss.OssService) getField(submissionService, "ossService"));
            if (fs != null) {
                for (String u : fs) {
                    String key = oss.extractObjectKey(u);
                    int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
                    String enc = slash >= 0 ? key.substring(slash + 1) : key;
                    String name = codec.decrypt(enc);
                    names.add(name == null || name.isBlank() ? enc : name);
                }
            }
            resp.put("fileNames", names);
        } catch (Exception ignored) { resp.put("fileNames", java.util.List.of()); }
        return resp;
    }

    // 删除：按提交者字段删除（可选限制项目）。未指定项目则需要 SUPER 权限
    @PostMapping(path = "/delete-by-field", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('SUPER') or (#body['projectId'] != null and @adminPermissionService.canManageProject(authentication, #body['projectId']))")
    public ResponseEntity<?> deleteByField(@RequestBody Map<String, Object> body) {
        String fieldKey = String.valueOf(body.getOrDefault("fieldKey", "")).trim();
        String fieldValue = String.valueOf(body.getOrDefault("fieldValue", "")).trim();
        Long projectId = null;
        if (body.get("projectId") != null) {
            try { projectId = Long.parseLong(String.valueOf(body.get("projectId"))); } catch (Exception ignored) {}
        }
        if (fieldKey.isEmpty() || fieldValue.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "fieldKey/fieldValue 不能为空"));
        }

        List<Project> projects;
        if (projectId != null) {
            projects = projectRepository.findById(projectId).map(java.util.List::of).orElse(java.util.List.of());
            if (projects.isEmpty()) return ResponseEntity.badRequest().body(Map.of("message", "项目不存在"));
        } else {
            projects = projectRepository.findAll();
        }

        int deletedSubs = 0;
        int deletedFiles = 0;
        int affectedProjects = 0;

        for (Project p : projects) {
            List<Submission> list = submissionRepository.findByProject(p);
            List<Submission> toDelete = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            for (Submission s : list) {
                try {
                    var node = objectMapper.readTree(s.getSubmitterInfo());
                    var v = node == null ? null : node.get(fieldKey);
                    String val = v == null || v.isNull() ? "" : v.asText("");
                    if (fieldValue.equals(val)) {
                        toDelete.add(s);
                        List<String> u = objectMapper.readValue(s.getFileUrls(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                        if (u != null) urls.addAll(u);
                    }
                } catch (Exception ignored) {}
            }
            if (!toDelete.isEmpty()) {
                affectedProjects++;
                try { ossService.deleteByUrls(urls); } catch (Exception ignored) {}
                submissionRepository.deleteAllInBatch(toDelete);
                deletedSubs += toDelete.size();
                deletedFiles += urls.size();
                // 更新计数
                long distinct = submissionRepository.countDistinctSubmitters(p);
                p.setTotalSubmitters((int) distinct);
                projectRepository.save(p);
            }
        }

        return ResponseEntity.ok(Map.of(
                "deletedSubmissions", deletedSubs,
                "deletedFiles", deletedFiles,
                "affectedProjects", affectedProjects
        ));
    }

    private Object getField(Object target, String name) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) { return null; }
    }
}
