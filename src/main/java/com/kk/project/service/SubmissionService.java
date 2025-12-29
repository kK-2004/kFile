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

    @Transactional
    public Submission submit(Long projectId, String submitterJson, List<MultipartFile> files,
                             String ipAddress, String userAgent) {
        Project project = projectService.get(projectId);
        validateWindow(project);
        submitterJson = prepareSubmitterJson(project, submitterJson);
        validateSubmitterAllowed(project, submitterJson);
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

        // 自动命名文件：在服务端为 MultipartFile 提供新的 originalFilename
        List<MultipartFile> filesToUpload = files;
        if (Boolean.TRUE.equals(project.getAutoFileNamingEnabled())) {
            List<MultipartFile> renamed = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                MultipartFile f = files.get(i);
                String newName = computeAutoFileName(project, submitterJson, f.getOriginalFilename(), i, files.size());
                renamed.add(new RenamedMultipartFile(f, newName));
            }
            filesToUpload = renamed;
        }

        List<String> urls = ossService.uploadWithPrefix(filesToUpload, keyPrefix);

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
        submitterJson = prepareSubmitterJson(project, submitterJson);
        validateSubmitterAllowed(project, submitterJson);
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
            // type by filename in key
            int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
            String enc = slash >= 0 ? key.substring(slash + 1) : key;
            String name = enc;
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

    private void validateSubmitterAllowed(Project project, String submitterJson) {
        try {
            String keysJson = project.getAllowedSubmitterKeys();
            String listJson = project.getAllowedSubmitterList();
            if (keysJson == null || keysJson.isBlank() || listJson == null || listJson.isBlank()) return; // no restriction
            java.util.List<String> keys = objectMapper.readValue(
                    keysJson,
                    objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)
            );
            if (keys == null || keys.isEmpty()) return;

            // Normalize submitter values
            JsonNode submitter = objectMapper.readTree(submitterJson == null || submitterJson.isBlank() ? "{}" : submitterJson);
            JsonNode arr = objectMapper.readTree(listJson);

            if (keys.size() == 1) {
                String k = keys.get(0);
                String val = submitter != null && submitter.has(k) && !submitter.get(k).isNull() ? submitter.get(k).asText("").trim() : "";
                if (val.isEmpty()) throw new IllegalStateException("提交者不在允许名单：缺少 " + k);
                java.util.Set<String> allowed = new java.util.HashSet<>();
                if (arr != null && arr.isArray()) {
                    for (JsonNode n : arr) {
                        if (n.isTextual()) {
                            String v = n.asText("").trim();
                            if (!v.isEmpty()) allowed.add(v);
                        } else if (n.isObject()) {
                            JsonNode vv = n.get(k);
                            String v = vv == null || vv.isNull() ? "" : vv.asText("").trim();
                            if (!v.isEmpty()) allowed.add(v);
                        }
                    }
                }
                if (!allowed.isEmpty() && !allowed.contains(val)) {
                    throw new IllegalStateException("当前不在允许提交名单");
                }
            } else {
                // composite keys
                java.util.Set<String> allowed = new java.util.HashSet<>();
                if (arr != null && arr.isArray()) {
                    for (JsonNode n : arr) {
                        if (!n.isObject()) continue;
                        java.util.List<String> vals = new java.util.ArrayList<>();
                        boolean miss = false;
                        for (String k : keys) {
                            JsonNode vv = n.get(k);
                            String v = vv == null || vv.isNull() ? "" : vv.asText("").trim();
                            if (v.isEmpty()) { miss = true; break; }
                            vals.add(v);
                        }
                        if (!miss) allowed.add(String.join("\u0001", vals));
                    }
                }
                // build composite from submitter
                java.util.List<String> svals = new java.util.ArrayList<>();
                for (String k : keys) {
                    JsonNode vv = submitter.get(k);
                    String v = vv == null || vv.isNull() ? "" : vv.asText("").trim();
                    if (v.isEmpty()) { throw new IllegalStateException("提交者不在允许名单：缺少 " + k); }
                    svals.add(v);
                }
                if (!allowed.isEmpty() && !allowed.contains(String.join("\u0001", svals))) {
                    throw new IllegalStateException("当前不在允许提交名单");
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 若解析失败，放行（视为未启用限制）
        }
    }

    // Expose a safe checker for controller to validate without side effects
    public void assertSubmitterAllowed(Project project, String submitterJson) {
        validateSubmitterAllowed(project, submitterJson);
    }

    /**
     * When the project has an allowed submitter list imported from a table (object array),
     * enrich submitter JSON by matching allowedSubmitterKeys and filling missing fields
     * (e.g. only provide "studentId", auto-fill "name").
     */
    public String prepareSubmitterJson(Project project, String submitterJson) {
        try {
            if (project == null) return submitterJson;
            String keysJson = project.getAllowedSubmitterKeys();
            String listJson = project.getAllowedSubmitterList();
            if (keysJson == null || keysJson.isBlank() || listJson == null || listJson.isBlank()) return submitterJson;

            com.fasterxml.jackson.databind.JsonNode submitterNode = objectMapper.readTree(
                    (submitterJson == null || submitterJson.isBlank()) ? "{}" : submitterJson
            );
            if (submitterNode == null || !submitterNode.isObject()) return submitterJson;
            com.fasterxml.jackson.databind.node.ObjectNode submitterObj = (com.fasterxml.jackson.databind.node.ObjectNode) submitterNode;

            com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(listJson);
            if (arr == null || !arr.isArray()) return submitterJson;

            // Prefer matching by queryFieldKey (common: 学号/sid) so user can fill only one visible field.
            java.util.List<String> matchKeys = new java.util.ArrayList<>();
            if (org.springframework.util.StringUtils.hasText(project.getQueryFieldKey())) {
                matchKeys.add(project.getQueryFieldKey().trim());
            } else {
                java.util.List<String> keys = objectMapper.readValue(
                        keysJson,
                        objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)
                );
                if (keys != null) {
                    for (String k : keys) if (k != null && !k.isBlank()) matchKeys.add(k.trim());
                }
            }
            if (matchKeys.isEmpty()) return submitterJson;

            java.util.Map<String, String> match = new java.util.LinkedHashMap<>();
            for (String k : matchKeys) {
                com.fasterxml.jackson.databind.JsonNode v = submitterObj.get(k);
                String s = v == null || v.isNull() ? "" : v.asText("").trim();
                if (s.isEmpty()) return submitterJson;
                match.put(k, s);
            }

            for (com.fasterxml.jackson.databind.JsonNode row : arr) {
                if (row == null || !row.isObject()) continue;
                boolean ok = true;
                for (java.util.Map.Entry<String, String> e : match.entrySet()) {
                    com.fasterxml.jackson.databind.JsonNode rv = row.get(e.getKey());
                    String rs = rv == null || rv.isNull() ? "" : rv.asText("").trim();
                    if (!e.getValue().equals(rs)) { ok = false; break; }
                }
                if (!ok) continue;

                // Merge: only fill missing/blank fields on submitter
                row.fieldNames().forEachRemaining(fn -> {
                    if (fn == null || fn.isBlank()) return;
                    com.fasterxml.jackson.databind.JsonNode cur = submitterObj.get(fn);
                    String curText = cur == null || cur.isNull() ? "" : cur.asText("").trim();
                    if (!curText.isEmpty()) return;
                    com.fasterxml.jackson.databind.JsonNode rv = row.get(fn);
                    if (rv == null || rv.isNull()) return;
                    submitterObj.set(fn, rv);
                });
                return objectMapper.writeValueAsString(submitterObj);
            }

            // Fallback: if matching by queryFieldKey failed but allowed keys are configured, try them.
            try {
                java.util.List<String> keys = objectMapper.readValue(
                        keysJson,
                        objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)
                );
                java.util.Map<String, String> m2 = new java.util.LinkedHashMap<>();
                if (keys != null) {
                    for (String k : keys) {
                        if (k == null || k.isBlank()) continue;
                        com.fasterxml.jackson.databind.JsonNode v = submitterObj.get(k);
                        String s = v == null || v.isNull() ? "" : v.asText("").trim();
                        if (s.isEmpty()) { m2.clear(); break; }
                        m2.put(k.trim(), s);
                    }
                }
                if (!m2.isEmpty()) {
                    for (com.fasterxml.jackson.databind.JsonNode row : arr) {
                        if (row == null || !row.isObject()) continue;
                        boolean ok = true;
                        for (java.util.Map.Entry<String, String> e : m2.entrySet()) {
                            com.fasterxml.jackson.databind.JsonNode rv = row.get(e.getKey());
                            String rs = rv == null || rv.isNull() ? "" : rv.asText("").trim();
                            if (!e.getValue().equals(rs)) { ok = false; break; }
                        }
                        if (!ok) continue;
                        row.fieldNames().forEachRemaining(fn -> {
                            if (fn == null || fn.isBlank()) return;
                            com.fasterxml.jackson.databind.JsonNode cur = submitterObj.get(fn);
                            String curText = cur == null || cur.isNull() ? "" : cur.asText("").trim();
                            if (!curText.isEmpty()) return;
                            com.fasterxml.jackson.databind.JsonNode rv = row.get(fn);
                            if (rv == null || rv.isNull()) return;
                            submitterObj.set(fn, rv);
                        });
                        return objectMapper.writeValueAsString(submitterObj);
                    }
                }
            } catch (Exception ignored) {}
            return submitterJson;
        } catch (Exception e) {
            return submitterJson;
        }
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

    /**
     * 根据项目配置与提交者字段计算“存储文件名”（保留原扩展名）。
     * 配置结构示例：
     * {
     *   "fields": ["major","class","sid","course"],
     *   "separator": " ",
     *   "aliases": { "major": {"计算机":"计"} },
     *   "customFields": [{ "key":"course","label":"课程","value":"数据结构" }]
     * }
     */
    public String computeAutoFileName(Project project, String submitterJson, String originalFilename, int index, int total) {
        AutoFileNamingConfig cfg = parseAutoFileNamingConfig(project);
        if (cfg == null || cfg.fields().isEmpty()) {
            // 没有配置字段时：直接返回原文件名（保持兼容）
            return baseName(originalFilename);
        }
        List<String> parts = new ArrayList<>();
        for (String key : cfg.fields()) {
            if (!StringUtils.hasText(key)) continue;
            String raw = extractFieldValue(submitterJson, key);
            String v = raw == null ? "" : raw.trim();
            if (!StringUtils.hasText(v)) {
                String fromCustom = cfg.customFieldValues().getOrDefault(key, "");
                v = fromCustom == null ? "" : fromCustom.trim();
            }
            if (!StringUtils.hasText(v)) {
                throw new IllegalArgumentException("自动命名缺少字段: " + key);
            }
            Map<String, String> amap = cfg.aliases().getOrDefault(key, java.util.Map.of());
            String aliased = amap.get(v);
            if (!StringUtils.hasText(aliased)) aliased = v;
            String safe = safeSegment(aliased);
            if (!StringUtils.hasText(safe)) safe = "unknown";
            parts.add(safe);
        }
        String sep = cfg.separator() == null ? "" : cfg.separator();
        String stem = String.join(sep, parts);

        // 多文件提交时，为避免同名覆盖，附加原文件名（不含扩展名）或索引
        if (total > 1) {
            String origStem = safeSegment(stripExtension(baseName(originalFilename)));
            if (!StringUtils.hasText(origStem)) origStem = String.valueOf(index + 1);
            stem = stem + sep + origStem;
        }

        String ext = getExtension(originalFilename);
        if (StringUtils.hasText(ext)) return stem + "." + ext;
        return stem;
    }

    private AutoFileNamingConfig parseAutoFileNamingConfig(Project project) {
        if (project == null) return null;
        if (!Boolean.TRUE.equals(project.getAutoFileNamingEnabled())) return null;
        String json = project.getAutoFileNamingConfig();
        if (!StringUtils.hasText(json)) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = objectMapper.readValue(json, Map.class);
            Object fieldsRaw = m.get("fields");
            List<String> fields = new ArrayList<>();
            if (fieldsRaw instanceof List<?> arr) {
                for (Object o : arr) {
                    if (o == null) continue;
                    String s = String.valueOf(o).trim();
                    if (StringUtils.hasText(s)) fields.add(s);
                }
            }
            String separator = m.get("separator") == null ? "" : String.valueOf(m.get("separator"));

            Map<String, Map<String, String>> aliases = new HashMap<>();
            Object aliasesRaw = m.get("aliases");
            if (aliasesRaw instanceof Map<?, ?> amap) {
                for (Map.Entry<?, ?> e : amap.entrySet()) {
                    String fieldKey = e.getKey() == null ? "" : String.valueOf(e.getKey()).trim();
                    if (!StringUtils.hasText(fieldKey)) continue;
                    Map<String, String> one = new HashMap<>();
                    Object inner = e.getValue();
                    if (inner instanceof Map<?, ?> innerMap) {
                        for (Map.Entry<?, ?> ie : innerMap.entrySet()) {
                            if (ie.getKey() == null) continue;
                            String k = String.valueOf(ie.getKey());
                            String v = ie.getValue() == null ? "" : String.valueOf(ie.getValue());
                            one.put(k, v);
                        }
                    }
                    aliases.put(fieldKey, one);
                }
            }

            Map<String, String> customFieldValues = new HashMap<>();
            Object customRaw = m.get("customFields");
            if (customRaw instanceof Map<?, ?> cmap) {
                for (Map.Entry<?, ?> e : cmap.entrySet()) {
                    String k = e.getKey() == null ? "" : String.valueOf(e.getKey()).trim();
                    String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
                    if (StringUtils.hasText(k) && StringUtils.hasText(v)) customFieldValues.put(k, v);
                }
            } else if (customRaw instanceof List<?> arr) {
                for (Object o : arr) {
                    if (!(o instanceof Map<?, ?> obj)) continue;
                    Object kRaw = obj.get("key");
                    Object vRaw = obj.get("value");
                    String k = kRaw == null ? "" : String.valueOf(kRaw).trim();
                    String v = vRaw == null ? "" : String.valueOf(vRaw).trim();
                    if (StringUtils.hasText(k) && StringUtils.hasText(v)) customFieldValues.put(k, v);
                }
            }

            return new AutoFileNamingConfig(fields, separator, aliases, customFieldValues);
        } catch (Exception e) {
            // 配置损坏时：按未启用处理，避免影响提交
            return null;
        }
    }

    private String stripExtension(String filename) {
        if (!StringUtils.hasText(filename)) return "";
        int idx = filename.lastIndexOf('.');
        if (idx <= 0) return filename;
        return filename.substring(0, idx);
    }

    private String baseName(String filename) {
        if (!StringUtils.hasText(filename)) return "file";
        String fn = filename;
        int slash = Math.max(fn.lastIndexOf('/'), fn.lastIndexOf('\\'));
        if (slash >= 0) fn = fn.substring(slash + 1);
        // 防止路径穿越，移除 ..
        fn = fn.replace("..", "");
        return fn;
    }

    private record AutoFileNamingConfig(
            List<String> fields,
            String separator,
            Map<String, Map<String, String>> aliases,
            Map<String, String> customFieldValues
    ) {}

    private static class RenamedMultipartFile implements MultipartFile {
        private final MultipartFile delegate;
        private final String filename;

        private RenamedMultipartFile(MultipartFile delegate, String filename) {
            this.delegate = delegate;
            this.filename = filename;
        }

        @Override public String getName() { return delegate.getName(); }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return delegate.getContentType(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public long getSize() { return delegate.getSize(); }
        @Override public byte[] getBytes() throws java.io.IOException { return delegate.getBytes(); }
        @Override public java.io.InputStream getInputStream() throws java.io.IOException { return delegate.getInputStream(); }
        @Override public void transferTo(java.io.File dest) throws java.io.IOException, IllegalStateException { delegate.transferTo(dest); }
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
                java.util.Set<String> usedNames = new java.util.HashSet<>();
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
                        String entryName = buildEntryNameForZip(project, trimPrefixForZip(key), usedNames);
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

    public java.io.File prepareArchiveFile(Project project, String fieldKey, String fieldValue, String fileNameHint) {
        try {
            java.io.File tmp = java.io.File.createTempFile("kfile-archive-", ".zip");
            tmp.deleteOnExit();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                 java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos)) {
                // 仅导出每位提交者的最新一次有效提交（与 archive 保持一致）
                java.util.List<Submission> all = submissionRepository.findVisibleByProjectOrderByCreatedAtDesc(project);
                java.util.LinkedHashMap<String, Submission> latestMap = new java.util.LinkedHashMap<>();
                boolean doFilter = org.springframework.util.StringUtils.hasText(fieldKey) && org.springframework.util.StringUtils.hasText(fieldValue);
                for (Submission s : all) {
                    if (doFilter) {
                        try {
                            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(s.getSubmitterInfo());
                            com.fasterxml.jackson.databind.JsonNode v = node.get(fieldKey);
                            String val = v == null || v.isNull() ? "" : v.asText("");
                            if (val == null || !val.startsWith(fieldValue)) continue;
                        } catch (Exception ignored) { continue; }
                    }
                    String key = s.getSubmitterFingerprint();
                    if (key == null || key.isBlank()) key = String.valueOf(s.getId());
                    if (!latestMap.containsKey(key)) latestMap.put(key, s);
                }
                java.util.List<Submission> baseList = new java.util.ArrayList<>(latestMap.values());
                java.util.Set<String> usedNames = new java.util.HashSet<>();
                for (Submission s : baseList) {
                    java.util.List<String> urls;
                    try {
                        urls = objectMapper.readValue(s.getFileUrls(), objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
                    } catch (Exception e) { continue; }
                    java.util.List<String> toPack = urls;
                    if (!Boolean.TRUE.equals(project.getAllowMultiFiles()) && urls != null && !urls.isEmpty()) {
                        toPack = java.util.List.of(urls.get(urls.size() - 1));
                    }
                    for (String url : toPack) {
                        String key = ossService.extractObjectKey(url);
                        String entryName = buildEntryNameForZip(project, trimPrefixForZip(key), usedNames);
                        try (java.io.InputStream in = ossService.openByKey(key)) {
                            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                            in.transferTo(zos);
                            zos.closeEntry();
                        } catch (Exception e) { /* skip */ }
                    }
                }
                zos.finish();
            }
            return tmp;
        } catch (Exception e) {
            throw new IllegalStateException("生成ZIP失败", e);
        }
    }

    private String trimPrefixForZip(String objectKey) {
        String pre = ossProperties.getPrefix();
        if (pre != null && !pre.isEmpty()) {
            String p = pre.endsWith("/") ? pre : pre + "/";
            if (objectKey.startsWith(p)) return objectKey.substring(p.length());
        }
        return objectKey;
    }

    private String buildEntryNameForZip(Project project, String path, java.util.Set<String> used) {
        if (path == null) path = "";
        String key = path.replace('\\', '/');
        // 拆分路径段
        String[] parts = key.split("/");
        java.util.List<String> dirSegs = new java.util.ArrayList<>();
        String name;
        String projectSeg = (project == null ? "" : safeSegment(project.getName()));
        if (parts.length == 0) {
            name = "file";
        } else {
            // 最后一段作为文件名
            name = parts[parts.length - 1];
            // 其余作为目录（去掉项目名段和一次性子目录等临时段）
            for (int i = 0; i < parts.length - 1; i++) {
                String seg = parts[i];
                if (seg == null || seg.isEmpty()) continue;
                // 清理路径穿越
                seg = seg.replace("..", "");
                if (seg.isEmpty()) continue;
                // 跳过项目名段（$project 对应的 safeSegment(project.getName())）
                if (!projectSeg.isEmpty() && projectSeg.equals(seg)) continue;
                // 跳过一次性子目录（例如 20251213152252143-e9d23165）
                if (isOneTimeDirSegment(seg)) continue;
                dirSegs.add(seg);
            }
        }
        String dir = dirSegs.isEmpty() ? "" : (String.join("/", dirSegs) + "/");

        // 清理文件名中的路径穿越符
        name = (name == null ? "" : name).replace("..", "");

        // 针对完整路径做重名去重：a/b/file, a/b/file (2) ...
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
        // 时间戳部分 17 位数字
        if (ts.length() != 17) return false;
        for (int i = 0; i < ts.length(); i++) {
            if (!Character.isDigit(ts.charAt(i))) return false;
        }
        // UUID 前缀部分 8 位十六进制
        if (suf.length() != 8) return false;
        for (int i = 0; i < suf.length(); i++) {
            char c = suf.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    // Expose component for controllers needing key operations
    public OssService getOssService() { return ossService; }

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
