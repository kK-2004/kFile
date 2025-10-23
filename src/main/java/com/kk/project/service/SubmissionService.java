package com.kk.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.oss.OssService;
import com.kk.config.OssProperties;
import com.kk.common.UserAgentUtil;
import com.kk.geoip.GeoInfo;
import com.kk.geoip.GeoIpService;
import com.kk.project.entity.Project;
import com.kk.project.entity.Submission;
import com.kk.project.repo.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
    public class SubmissionService {
    private final SubmissionRepository submissionRepository;
    private final ProjectService projectService;
    private final OssService ossService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeoIpService geoIpService;
    private final OssProperties ossProperties;
    private final com.kk.common.FileNameCodec fileNameCodec;

    @Transactional
    public Submission submit(Long projectId, String submitterJson, List<MultipartFile> files,
                             String ipAddress, String userAgent) {
        Project project = projectService.get(projectId);
        validateWindow(project);
        List<String> types = projectService.parseTypes(project);
        // validate files before upload
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少上传一个文件");
        }
        if (!Boolean.TRUE.equals(project.getAllowMultiFiles()) && files.size() > 1) {
            throw new IllegalArgumentException("该项目不允许一次上传多个文件");
        }
        for (MultipartFile f : files) {
            if (project.getFileSizeLimitBytes() != null && f.getSize() > project.getFileSizeLimitBytes()) {
                throw new IllegalArgumentException("文件超出大小限制: " + f.getOriginalFilename());
            }
            if (types != null && !types.isEmpty()) {
                String ext = getExtension(f.getOriginalFilename());
                if (!StringUtils.hasText(ext) || types.stream().noneMatch(t -> t.equalsIgnoreCase(ext))) {
                    throw new IllegalArgumentException("文件类型不允许: " + f.getOriginalFilename());
                }
            }
        }

        String canonicalSubmitter = canonicalizeSubmitter(submitterJson);
        String fingerprint = DigestUtils.md5DigestAsHex(canonicalSubmitter.getBytes(StandardCharsets.UTF_8));

        if (!Boolean.TRUE.equals(project.getAllowResubmit())) {
            long exist = submissionRepository.countByProjectAndSubmitterFingerprint(project, fingerprint);
            if (exist > 0) {
                throw new IllegalStateException("该项目不允许重复提交");
            }
        }

        String keyPrefix = buildUploadPrefix(project, submitterJson);

        List<String> urls = ossService.uploadWithPrefix(files, keyPrefix);

        Submission s = new Submission();
        s.setProject(project);
        s.setSubmitterInfo(canonicalSubmitter);
        s.setSubmitterFingerprint(fingerprint);
        s.setIpAddress(ipAddress);
        s.setUserAgent(userAgent);
        // parse UA
        UserAgentUtil.ParsedUA parsedUA = UserAgentUtil.parse(userAgent);
        s.setOsName(parsedUA.osName());
        s.setOsVersion(parsedUA.osVersion());
        s.setBrowserName(parsedUA.browserName());
        s.setBrowserVersion(parsedUA.browserVersion());
        s.setDeviceType(parsedUA.deviceType());
        // geoip
        GeoInfo geo = geoIpService.lookup(ipAddress);
        s.setIpCountry(geo.getCountry());
        s.setIpProvince(geo.getProvince());
        s.setIpCity(geo.getCity());
        try {
            s.setFileUrls(objectMapper.writeValueAsString(urls));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize file urls", e);
        }

        long countForSubmitter = submissionRepository.countByProjectAndSubmitterFingerprint(project, fingerprint) + 1;
        s.setSubmitCount((int) countForSubmitter);

        s.setValid(true);
        // 若已超过截止时间，标记该提交为逾期
        try {
            java.time.Instant now = java.time.Instant.now();
            if (project.getEndAt() != null && now.isAfter(project.getEndAt())) {
                s.setExpired(true);
            }
        } catch (Exception ignored) {}
        Submission saved = submissionRepository.save(s);

        // update project's total submitters metric
        long distinctSubmitters = submissionRepository.countDistinctSubmitters(project);
        project.setTotalSubmitters((int) distinctSubmitters);
        // 保留至多10条：仅标记超出记录为无效（后台任务清理OSS）
        enforceRetentionMax(project, fingerprint, 10);

        return saved;
    }

    // 直传：返回的 key 已经上传成功，这里校验并入库
    @Transactional
    public Submission submitDirectCompleted(Project project, String submitterJson, java.util.List<String> keys,
                                            String ipAddress, String userAgent) {
        validateWindow(project);
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("请至少上传一个文件");
        if (!Boolean.TRUE.equals(project.getAllowMultiFiles()) && keys.size() > 1) {
            throw new IllegalArgumentException("该项目不允许一次上传多个文件");
        }

        // 校验大小与类型
        java.util.List<String> types = projectService.parseTypes(project);
        java.util.List<String> normalizedKeys = new java.util.ArrayList<>();
        for (String key : keys) {
            if (!org.springframework.util.StringUtils.hasText(key)) continue;
            // stat for size
            com.kk.oss.OssService.ObjectStat stat = ossService.statByKey(key);
            if (project.getFileSizeLimitBytes() != null && stat.length > project.getFileSizeLimitBytes()) {
                throw new IllegalArgumentException("文件超出大小限制: " + key);
            }
            // type by decrypting name
            int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
            String enc = slash >= 0 ? key.substring(slash + 1) : key;
            String name = fileNameCodec.decrypt(enc);
            String ext = getExtension(name);
            if (types != null && !types.isEmpty()) {
                if (!org.springframework.util.StringUtils.hasText(ext) || types.stream().noneMatch(t -> t.equalsIgnoreCase(ext))) {
                    throw new IllegalArgumentException("文件类型不允许: " + name);
                }
            }
            normalizedKeys.add(key);
        }

        String canonicalSubmitter = canonicalizeSubmitter(submitterJson);
        String fingerprint = DigestUtils.md5DigestAsHex(canonicalSubmitter.getBytes(StandardCharsets.UTF_8));

        if (!Boolean.TRUE.equals(project.getAllowResubmit())) {
            long exist = submissionRepository.countByProjectAndSubmitterFingerprint(project, fingerprint);
            if (exist > 0) throw new IllegalStateException("该项目不允许重复提交");
        }

        java.util.List<String> urls = normalizedKeys.stream().map(ossService::proxyUrlByKey).toList();

        Submission s = new Submission();
        s.setProject(project);
        s.setSubmitterInfo(canonicalSubmitter);
        s.setSubmitterFingerprint(fingerprint);
        s.setIpAddress(ipAddress);
        s.setUserAgent(userAgent);
        // parse UA
        com.kk.common.UserAgentUtil.ParsedUA parsedUA = com.kk.common.UserAgentUtil.parse(userAgent);
        s.setOsName(parsedUA.osName());
        s.setOsVersion(parsedUA.osVersion());
        s.setBrowserName(parsedUA.browserName());
        s.setBrowserVersion(parsedUA.browserVersion());
        s.setDeviceType(parsedUA.deviceType());
        // geoip
        GeoInfo geo = geoIpService.lookup(ipAddress);
        s.setIpCountry(geo.getCountry());
        s.setIpProvince(geo.getProvince());
        s.setIpCity(geo.getCity());
        try { s.setFileUrls(objectMapper.writeValueAsString(urls)); }
        catch (JsonProcessingException e) { throw new RuntimeException("Failed to serialize file urls", e); }

        long countForSubmitter = submissionRepository.countByProjectAndSubmitterFingerprint(project, fingerprint) + 1;
        s.setSubmitCount((int) countForSubmitter);
        s.setValid(true);
        try {
            java.time.Instant now = java.time.Instant.now();
            if (project.getEndAt() != null && now.isAfter(project.getEndAt())) s.setExpired(true);
        } catch (Exception ignored) {}
        Submission saved = submissionRepository.save(s);
        long distinctSubmitters = submissionRepository.countDistinctSubmitters(project);
        project.setTotalSubmitters((int) distinctSubmitters);
        enforceRetentionMax(project, fingerprint, 10);
        return saved;
    }

    // 构造上传前缀（含项目/提交者字段），末尾带 '/'
    public String buildUploadPrefix(Project project, String submitterJson) {
        java.util.List<String> segKeys = projectService.parsePathSegments(project);
        java.util.List<String> segValues = new java.util.ArrayList<>();
        if (segKeys == null || segKeys.isEmpty()) segKeys = java.util.List.of("$project");
        for (String k : segKeys) {
            String v = "$project".equals(k) ? project.getName() : extractFieldValue(submitterJson, k);
            v = safeSegment(v);
            if (v == null || v.isEmpty()) v = "unknown";
            segValues.add(v);
        }
        return String.join("/", segValues) + "/";
    }

    // 组合为完整 key（包含 oss.prefix 与调用方传入的业务前缀）
    public String normalizeFullKey(String keyPrefix, String encName) {
        String p = keyPrefix == null ? "" : keyPrefix;
        if (org.springframework.util.StringUtils.hasText(ossProperties.getPrefix())) {
            String pre = ossProperties.getPrefix();
            if (!pre.endsWith("/")) pre += "/";
            p = pre + p;
        }
        if (org.springframework.util.StringUtils.hasText(p) && !p.endsWith("/")) p += "/";
        return p + encName;
    }

    public List<Submission> list(Long projectId) {
        Project p = projectService.get(projectId);
        return submissionRepository.findByProject(p);
    }

    public Page<Submission> page(Project project, Pageable pageable) {
        return submissionRepository.findVisibleByProject(project, pageable);
    }

    public String exportCsv(Project project) {
        // 仅导出每个提交者的“最新一次有效提交”
        List<Submission> all = submissionRepository.findVisibleByProjectOrderByCreatedAtDesc(project);
        java.util.LinkedHashMap<String, Submission> latestMap = new java.util.LinkedHashMap<>();
        for (Submission s : all) {
            String key = s.getSubmitterFingerprint();
            if (key == null || key.isBlank()) key = String.valueOf(s.getId());
            if (!latestMap.containsKey(key)) latestMap.put(key, s);
        }
        List<Submission> list = new java.util.ArrayList<>(latestMap.values());
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM to help Excel recognize encoding
        sb.append('\uFEFF');

        // build expected keys
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        try {
            if (project.getExpectedUserFields() != null) {
                java.util.List<java.util.Map<String,Object>> defs = objectMapper.readValue(
                        project.getExpectedUserFields(),
                        objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, java.util.Map.class));
                for (java.util.Map<String,Object> def : defs) {
                    Object k = def.get("key");
                    if (k != null) keys.add(String.valueOf(k));
                }
            }
        } catch (Exception ignored) {}
        // fallback: union of keys in submissions（仅遍历最新一次）
        if (keys.isEmpty()) {
            for (Submission s : list) {
                try {
                    JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                    if (node != null && node.isObject()) {
                        node.fieldNames().forEachRemaining(keys::add);
                    }
                } catch (Exception ignored) {}
            }
        }

        // counts (non-empty per field)
        java.util.Map<String,Integer> counts = new java.util.LinkedHashMap<>();
        for (String k : keys) counts.put(k, 0);
        // value counts per field
        java.util.Map<String, java.util.Map<String,Integer>> valueCounts = new java.util.LinkedHashMap<>();
        for (String k : keys) valueCounts.put(k, new java.util.LinkedHashMap<>());
        for (Submission s : list) {
            try {
                JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                for (String k : keys) {
                    String val = node != null && node.has(k) && !node.get(k).isNull() ? node.get(k).asText("").trim() : "";
                    if (!val.isEmpty()) {
                        counts.put(k, counts.getOrDefault(k, 0) + 1);
                        var map = valueCounts.get(k);
                        map.put(val, map.getOrDefault(val, 0) + 1);
                    }
                }
            } catch (Exception ignored) {}
        }

        // summary at top
        sb.append("totalSubmissions,").append(list.size()).append('\n');
        for (String k : keys) {
            sb.append("field,").append(k).append(',').append(counts.getOrDefault(k, 0)).append('\n');
            // per value counts
            var map = valueCounts.get(k);
            if (map != null && !map.isEmpty()) {
                for (var e : map.entrySet()) {
                    sb.append("fieldValue,").append(k).append(',')
                      .append(safeCsv(e.getKey())).append(',')
                      .append(e.getValue()).append('\n');
                }
            }
        }
        sb.append('\n');

        // header (expand submitter fields as columns)
        sb.append("id,submitter,fileUrls,submitCount,expired,ipAddress,userAgent,osName,osVersion,browserName,browserVersion,deviceType,ipCountry,ipProvince,ipCity,createdAt,updatedAt");
        for (String k : keys) {
            sb.append(',').append(k);
        }
        sb.append('\n');
        for (Submission s : list) {
            String submitter = safeCsv(s.getSubmitterInfo());
            String urls = safeCsv(s.getFileUrls());
            sb.append(s.getId()).append(',')
              .append(submitter).append(',')
              .append(urls).append(',')
              .append(s.getSubmitCount() == null ? 1 : s.getSubmitCount()).append(',')
              .append(Boolean.TRUE.equals(s.getExpired())).append(',')
              .append(s.getIpAddress() == null ? "" : s.getIpAddress()).append(',')
              .append(safeCsv(s.getUserAgent())).append(',')
              .append(safeCsv(s.getOsName())).append(',')
              .append(safeCsv(s.getOsVersion())).append(',')
              .append(safeCsv(s.getBrowserName())).append(',')
              .append(safeCsv(s.getBrowserVersion())).append(',')
              .append(safeCsv(s.getDeviceType())).append(',')
              .append(safeCsv(s.getIpCountry())).append(',')
              .append(safeCsv(s.getIpProvince())).append(',')
              .append(safeCsv(s.getIpCity())).append(',')
              .append(s.getCreatedAt()).append(',')
              .append(s.getUpdatedAt());
            // append expanded submitter columns
            try {
                JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                for (String k : keys) {
                    String val = (node != null && node.has(k) && !node.get(k).isNull()) ? node.get(k).asText("") : "";
                    sb.append(',').append(safeCsv(val));
                }
            } catch (Exception e) {
                for (int i = 0; i < keys.size(); i++) sb.append(',');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // 获取某个提交者的最新一条提交（根据 canonicalized submitterJson 计算 fingerprint）
    public java.util.List<Submission> listLatestBySubmitter(Project project, String submitterJson) {
        String canonical = canonicalizeSubmitter(submitterJson);
        String fingerprint = org.springframework.util.DigestUtils.md5DigestAsHex(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.List<Submission> listDesc = submissionRepository
                .findByProjectAndSubmitterFingerprintOrderByCreatedAtDesc(project, fingerprint);
        return listDesc == null ? java.util.List.of() : listDesc;
    }

    // 通过指定字段值查询最新一条提交（用于无需登录的状态查询）
    public java.util.List<Submission> listLatestByFieldValue(Project project, String fieldKey, String fieldValue) {
        // 取所有可见提交，按时间倒序逐项匹配，返回第一个匹配的提交（即最新的一条）
        java.util.List<Submission> all = submissionRepository.findVisibleByProjectOrderByCreatedAtDesc(project);
        for (Submission s : all) {
            try {
                JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                JsonNode v = node == null ? null : node.get(fieldKey);
                String val = v == null || v.isNull() ? "" : v.asText("");
                if (val != null && val.equals(fieldValue)) {
                    return java.util.List.of(s);
                }
            } catch (Exception ignored) {}
        }
        return java.util.List.of();
    }

    // 返回该字段值对应的所有提交（按时间倒序，用于版本链）
    public java.util.List<Submission> listAllByFieldValue(Project project, String fieldKey, String fieldValue) {
        java.util.List<Submission> out = new java.util.ArrayList<>();
        java.util.List<Submission> all = submissionRepository.findVisibleByProjectOrderByCreatedAtDesc(project);
        for (Submission s : all) {
            try {
                JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                JsonNode v = node == null ? null : node.get(fieldKey);
                String val = v == null || v.isNull() ? "" : v.asText("");
                if (val != null && val.equals(fieldValue)) {
                    out.add(s);
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    public org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody archive(Project project, String fieldKey, String fieldValue, String fileNameHint) {
        // 统一逻辑：无论是否带筛选，均仅导出每位提交者“最新一次有效提交”的文件
        List<Submission> all = submissionRepository.findVisibleByProjectOrderByCreatedAtDesc(project);
        java.util.LinkedHashMap<String, Submission> latestMap = new java.util.LinkedHashMap<>();
        boolean doFilter = StringUtils.hasText(fieldKey) && StringUtils.hasText(fieldValue);
        for (Submission s : all) {
            // 若需要筛选，则先根据提交者信息进行前缀匹配过滤
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
        List<Submission> baseList = new java.util.ArrayList<>(latestMap.values());

        String zipName = (fileNameHint == null || fileNameHint.isBlank()) ? ("project-" + project.getId() + ".zip") : fileNameHint;

        return outputStream -> {
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(outputStream)) {
                for (Submission s : baseList) {
                    List<String> urls;
                    try {
                        urls = objectMapper.readValue(s.getFileUrls(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    } catch (Exception e) {
                        continue;
                    }
                    java.util.List<String> toPack = urls;
                    if (!Boolean.TRUE.equals(project.getAllowMultiFiles()) && urls != null && !urls.isEmpty()) {
                        toPack = java.util.List.of(urls.get(urls.size() - 1));
                    }
                    for (String url : toPack) {
                        String key = ossService.extractObjectKey(url);
                        String entryName = decryptFilenameInPath(trimPrefixForZip(key));
                        try (java.io.InputStream in = ossService.openByKey(key)) {
                            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                            in.transferTo(zos);
                            zos.closeEntry();
                        } catch (Exception e) {
                            // skip broken entry
                        }
                    }
                }
                zos.finish();
            }
        };
    }

    private String trimPrefixForZip(String objectKey) {
        String pre = ossProperties.getPrefix();
        if (pre != null && !pre.isEmpty()) {
            String p = pre.endsWith("/") ? pre : pre + "/";
            if (objectKey.startsWith(p)) return objectKey.substring(p.length());
        }
        return objectKey;
    }

    private String decryptFilenameInPath(String path) {
        if (path == null || path.isEmpty()) return path;
        int slash = path.lastIndexOf('/');
        if (slash < 0) return fileNameCodec.decrypt(path);
        String dir = path.substring(0, slash + 1);
        String name = path.substring(slash + 1);
        String dec = fileNameCodec.decrypt(name);
        return dir + (dec == null || dec.isBlank() ? name : dec);
    }

    // Expose components for controllers needing filename/key operations
    public OssService getOssService() { return ossService; }
    public com.kk.common.FileNameCodec getFileNameCodec() { return fileNameCodec; }

    private String safeCsv(String json) {
        if (json == null) return "";
        String s = json.replace("\"", "\"\"");
        return '"' + s + '"';
    }

    private void validateWindow(Project p) {
        java.time.Instant now = java.time.Instant.now();
        if (Boolean.TRUE.equals(p.getOffline())) {
            throw new IllegalStateException("项目已下线");
        }
        if (p.getStartAt() != null && now.isBefore(p.getStartAt())) {
            throw new IllegalStateException("提交尚未开始");
        }
        if (p.getEndAt() != null && now.isAfter(p.getEndAt())) {
            // 若未允许逾期，则禁止提交；否则允许并在保存时标记逾期
            if (!Boolean.TRUE.equals(p.getAllowOverdue())) {
                throw new IllegalStateException("提交已截止");
            }
        }
    }

    private String canonicalizeSubmitter(String submitterJson) {
        try {
            if (!StringUtils.hasText(submitterJson)) return "{}";
            JsonNode node = objectMapper.readTree(submitterJson);
            // sort keys recursively for stable string
            return objectMapper.writeValueAsString(sortJson(node));
        } catch (Exception e) {
            throw new IllegalArgumentException("提交者信息JSON无效", e);
        }
    }

    private JsonNode sortJson(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> map = new TreeMap<>();
            node.fieldNames().forEachRemaining(fn -> map.put(fn, sortJson(node.get(fn))));
            return objectMapper.valueToTree(map);
        } else if (node.isArray()) {
            // keep array order
            List<JsonNode> list = new ArrayList<>();
            node.forEach(n -> list.add(sortJson(n)));
            return objectMapper.valueToTree(list);
        }
        return node;
    }

    private String getExtension(String filename) {
        if (!StringUtils.hasText(filename)) return "";
        int idx = filename.lastIndexOf('.')
                ;
        if (idx < 0 || idx == filename.length() - 1) return "";
        return filename.substring(idx + 1).toLowerCase();
    }

    private String extractFieldValue(String submitterJson, String key) {
        if (!StringUtils.hasText(key)) return "";
        try {
            JsonNode node = objectMapper.readTree(submitterJson);
            JsonNode v = node.get(key);
            return v == null || v.isNull() ? "" : v.asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String safeSegment(String s) {
        if (s == null) return "";
        String t = s.trim();
        // 仅保留常见字符：中文、字母、数字、- _ 空格 .
        t = t.replaceAll("[^\\p{IsHan}A-Za-z0-9 _.-]", "");
        // 替换多空格为单下划线
        t = t.replaceAll("\\s+", "_");
        return t;
    }

    private void enforceRetentionMax(Project project, String submitterFingerprint, int toKeep) {
        List<Submission> listDesc = submissionRepository
                .findByProjectAndSubmitterFingerprintOrderByCreatedAtDesc(project, submitterFingerprint);
        if (listDesc.size() <= toKeep) return;
        for (int i = 0; i < listDesc.size(); i++) {
            Submission s = listDesc.get(i);
            boolean shouldBeValid = i < toKeep;
            if (Boolean.TRUE.equals(shouldBeValid)) {
                if (Boolean.FALSE.equals(s.getValid())) {
                    s.setValid(true);
                    submissionRepository.save(s);
                }
            } else {
                if (!Boolean.FALSE.equals(s.getValid())) {
                    s.setValid(false);
                    submissionRepository.save(s);
                }
            }
        }
    }
}
