package com.kk.admin.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.oss.OssService;
import com.kk.config.OssProperties;
import com.kk.project.entity.Project;
import com.kk.project.entity.Submission;
import com.kk.project.repo.ProjectRepository;
import com.kk.project.repo.SubmissionRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArchiveTaskService {
    private final SubmissionRepository submissionRepository;
    private final ProjectRepository projectRepository;
    private final OssService ossService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OssProperties ossProperties;

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Data
    public static class Task {
        private String id;
        private Long projectId;
        private String projectName;
        private String status; // PENDING, RUNNING, COMPLETED, FAILED
        private int totalEntries;
        private int processedEntries;
        private long bytesWritten;
        private String filename;
        private String filePath;
        private long startedAt;
        private Long endedAt;
        private String message;
        private String fieldKey;
        private String fieldValue;
    }

    @Data
    public static class ManifestEntry {
        // OSS 对象 key（不含 host）
        private String key;
        // 预签名直链
        private String url;
        // 在 ZIP 中显示的路径/文件名
        private String filename;
    }

    public Task get(String id) { return tasks.get(id); }

    public Task start(Long projectId, String fieldKey, String fieldValue) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        Task t = new Task();
        t.setId(UUID.randomUUID().toString());
        t.setProjectId(projectId);
        t.setProjectName(p.getName());
        t.setStatus("PENDING");
        t.setStartedAt(Instant.now().toEpochMilli());
        t.setFieldKey(fieldKey);
        t.setFieldValue(fieldValue);
        String baseName = (p.getName() == null || p.getName().isBlank()) ? ("project-" + projectId) : p.getName().trim();
        if (fieldKey != null && !fieldKey.isBlank() && fieldValue != null && !fieldValue.isBlank()) {
            baseName = baseName + "-" + fieldKey + "-" + fieldValue;
        }
        t.setFilename(baseName + ".zip");
        tasks.put(t.getId(), t);
        executor.submit(() -> runTask(t));
        return t;
    }

    public FileSystemResource file(String taskId) {
        Task t = tasks.get(taskId);
        if (t == null || t.getFilePath() == null) throw new IllegalArgumentException("Task not found or not completed");
        return new FileSystemResource(new File(t.getFilePath()));
    }

    /**
     * 仅构建清单：不在服务端打包 ZIP，而是返回
     * 每个对象的 OSS key、预签名直链以及在压缩包中的文件名，由前端自行并发下载与打包。
     */
    public java.util.List<ManifestEntry> buildManifest(Long projectId, String fieldKey, String fieldValue, long urlExpireSeconds) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        List<Submission> all = submissionRepository.findVisibleByProjectOrderByCreatedAtDesc(project);
        LinkedHashMap<String, Submission> latestMap = new LinkedHashMap<>();
        boolean doFilter = fieldKey != null && !fieldKey.isBlank() && fieldValue != null && !fieldValue.isBlank();
        for (Submission s : all) {
            if (doFilter) {
                try {
                    JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                    JsonNode v = node.get(fieldKey);
                    String val = v == null || v.isNull() ? "" : v.asText("");
                    if (val == null || !val.startsWith(fieldValue)) continue;
                } catch (Exception ignored) { continue; }
            }
            String key = s.getSubmitterFingerprint();
            if (key == null || key.isBlank()) key = String.valueOf(s.getId());
            if (!latestMap.containsKey(key)) latestMap.put(key, s);
        }
        List<Submission> list = new ArrayList<>(latestMap.values());
        java.util.Set<String> usedNames = new java.util.HashSet<>();
        java.util.List<ManifestEntry> out = new java.util.ArrayList<>();
        for (Submission s : list) {
            List<String> urls;
            try {
                urls = objectMapper.readValue(s.getFileUrls(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception e) { urls = null; }
            if (urls == null) { continue; }
            List<String> toPack = urls;
            if (!Boolean.TRUE.equals(project.getAllowMultiFiles()) && !urls.isEmpty()) {
                toPack = List.of(urls.get(urls.size() - 1));
            }
            for (String url : toPack) {
                if (url == null || url.isBlank()) continue;
                String key = ossService.extractObjectKey(url);
                if (key == null || key.isBlank()) continue;
                String entryName = buildEntryName(key, usedNames);
                try {
                    String signedUrl = ossService.generatePresignedUrlByKey(key, true, urlExpireSeconds, false);
                    ManifestEntry me = new ManifestEntry();
                    me.setKey(key);
                    me.setUrl(signedUrl);
                    me.setFilename(entryName);
                    out.add(me);
                } catch (Exception ex) {
                    log.warn("failed to generate presigned url for key {}: {}", key, ex.getMessage());
                }
            }
        }
        return out;
    }

    private void runTask(Task t) {
        t.setStatus("RUNNING");
        try {
            Optional<Project> po = projectRepository.findById(t.getProjectId());
            if (po.isEmpty()) throw new IllegalStateException("Project not found");
            Project project = po.get();
            List<Submission> all = submissionRepository.findVisibleByProjectOrderByCreatedAtDesc(project);
            LinkedHashMap<String, Submission> latestMap = new LinkedHashMap<>();
            boolean doFilter = t.getFieldKey() != null && !t.getFieldKey().isBlank() && t.getFieldValue() != null && !t.getFieldValue().isBlank();
            for (Submission s : all) {
                if (doFilter) {
                    try {
                        JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                        JsonNode v = node.get(t.getFieldKey());
                        String val = v == null || v.isNull() ? "" : v.asText("");
                        if (val == null || !val.startsWith(t.getFieldValue())) continue;
                    } catch (Exception ignored) { continue; }
                }
                String key = s.getSubmitterFingerprint();
                if (key == null || key.isBlank()) key = String.valueOf(s.getId());
                if (!latestMap.containsKey(key)) latestMap.put(key, s);
            }
            List<Submission> list = new ArrayList<>(latestMap.values());
            t.setTotalEntries(list.size());

            File tmp = File.createTempFile("kfile-archive-", ".zip");
            tmp.deleteOnExit();
            t.setFilePath(tmp.getAbsolutePath());
            try (FileOutputStream fos = new FileOutputStream(tmp);
                 java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos)) {
                int processed = 0;
                java.util.Set<String> usedNames = new java.util.HashSet<>();
                for (Submission s : list) {
                    // 提前更新已处理条目，避免首条耗时较长时前端一直显示 0%
                    processed++;
                    t.setProcessedEntries(processed);
                    List<String> urls;
                    try {
                        urls = objectMapper.readValue(s.getFileUrls(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    } catch (Exception e) { urls = null; }
                    if (urls == null) { continue; }
                    List<String> toPack = urls;
                    if (!Boolean.TRUE.equals(project.getAllowMultiFiles()) && !urls.isEmpty()) {
                        toPack = List.of(urls.get(urls.size() - 1));
                    }
                    for (String url : toPack) {
                        String key = ossService.extractObjectKey(url);
                        String entryName = buildEntryName(key, usedNames);
                        try (java.io.InputStream in = ossService.openByKey(key)) {
                            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                            long written = in.transferTo(zos);
                            t.setBytesWritten(t.getBytesWritten() + Math.max(0, written));
                            zos.closeEntry();
                        } catch (Exception e) {
                            log.warn("archive pack entry failed: {} - {}", key, e.getMessage());
                        }
                    }
                }
                zos.finish();
            }
            t.setStatus("COMPLETED");
            t.setEndedAt(Instant.now().toEpochMilli());
            t.setMessage("ok");
        } catch (Exception e) {
            t.setStatus("FAILED");
            t.setEndedAt(Instant.now().toEpochMilli());
            t.setMessage(e.getMessage());
        }
    }

    private String buildEntryName(String objectKey, java.util.Set<String> used) {
        // 1) 规范化 & 去掉 oss.prefix
        String key = objectKey == null ? "" : objectKey;
        key = key.replace('\\', '/');
        String pre = ossProperties.getPrefix();
        if (pre != null && !pre.isEmpty()) {
            String p = pre.endsWith("/") ? pre : pre + "/";
            if (key.startsWith(p)) key = key.substring(p.length());
        }

        // 2) 拆分目录与文件名，并移除一次性子目录
        String[] parts = key.split("/");
        java.util.List<String> dirSegs = new java.util.ArrayList<>();
        String name;
        if (parts.length == 0) {
            name = "file";
        } else {
            name = parts[parts.length - 1];
            for (int i = 0; i < parts.length - 1; i++) {
                String seg = parts[i];
                if (seg == null || seg.isEmpty()) continue;
                seg = seg.replace("..", "");
                if (seg.isEmpty()) continue;
                if (isOneTimeDirSegment(seg)) continue;
                dirSegs.add(seg);
            }
        }
        String dir = dirSegs.isEmpty() ? "" : (String.join("/", dirSegs) + "/");

        // 3) 清理文件名中的路径穿越符
        name = (name == null ? "" : name).replace("..", "");

        // 4) 重名去重（针对完整路径，改写文件名部分）
        String baseName = name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String candidate = dir + baseName;
        int i = 2;
        while (used.contains(candidate)) {
            name = stem + " (" + i + ")" + ext;
            candidate = dir + name;
            i++;
        }
        used.add(candidate);
        return candidate;
    }

    // 判断是否为一次性子目录：形如 yyyyMMddHHmmssSSS-xxxxxxxx
    private boolean isOneTimeDirSegment(String seg) {
        if (seg == null) return false;
        String s = seg.trim();
        int dash = s.indexOf('-');
        if (dash <= 0) return false;
        String ts = s.substring(0, dash);
        String suf = s.substring(dash + 1);
        if (ts.length() != 17) return false;
        for (int i = 0; i < ts.length(); i++) {
            if (!Character.isDigit(ts.charAt(i))) return false;
        }
        if (suf.length() != 8) return false;
        for (int i = 0; i < suf.length(); i++) {
            char c = suf.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}
