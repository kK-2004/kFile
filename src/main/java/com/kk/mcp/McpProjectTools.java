package com.kk.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.kk.admin.task.ArchiveTaskService;
import com.kk.project.dto.CreateProjectRequest;
import com.kk.project.dto.ProjectResponse;
import com.kk.project.entity.Project;
import com.kk.project.service.ProjectQueryService;
import com.kk.project.service.ProjectService;
import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.service.AdminPermissionService;
import com.kk.share.service.ShareLinkService;
import com.kk.template.entity.ProjectTemplate;
import com.kk.template.service.ProjectTemplateService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * k-File MCP 工具集：供 AI agent 通过 SSE 调用。
 *
 * 鉴权：McpTokenAuthFilter 已在过滤器链中完成 token 鉴权，并把绑定 AdminUser 身份
 * 注入 SecurityContextHolder。因此工具内 SecurityContextHolder.getContext().getAuthentication()
 * 即真实用户，直接复用 ProjectService / ProjectQueryService / AdminPermissionService 的既有权限逻辑，
 * 零额外权限代码。
 *
 * Agent 提示词规范（见各工具 description）：
 * 凡需用户在确定选项中选择的场景（选模板/选项目/开关字段），优先调用 ask_user_choice 让用户选择，
 * 而非让用户在输入框手输。create_project 选了 templateId 时开关继承模板值不再提问；未选则每个开关
 * 用 ask_user_choice(是/否) 询问，不读默认值。
 */
@Component
public class McpProjectTools {
    private static final ObjectMapper M = new ObjectMapper();
    private final Map<String, String> projectCreationConfirmations = new ConcurrentHashMap<>();

    private final ProjectTemplateService templateService;
    private final ProjectService projectService;
    private final ProjectQueryService projectQueryService;
    private final AdminPermissionService adminPermissionService;
    private final AdminUserRepository userRepo;
    private final ArchiveTaskService archiveTaskService;
    private final ShareLinkService shareLinkService;

    @Value("${app.public-base-url:${env.cors:}}")
    private String publicBaseUrl;

    public McpProjectTools(ProjectTemplateService templateService,
                           ProjectService projectService,
                           ProjectQueryService projectQueryService,
                           AdminPermissionService adminPermissionService,
                           AdminUserRepository userRepo,
                           ArchiveTaskService archiveTaskService,
                           ShareLinkService shareLinkService) {
        this.templateService = templateService;
        this.projectService = projectService;
        this.projectQueryService = projectQueryService;
        this.adminPermissionService = adminPermissionService;
        this.userRepo = userRepo;
        this.archiveTaskService = archiveTaskService;
        this.shareLinkService = shareLinkService;
    }

    // ====================================================================
    // list_my_templates：查看自己有权限使用的模板
    // ====================================================================
    @Tool(name = "list_my_templates", description =
            "列出当前登录用户有权限使用的项目模板（owner 为自己 或 被分配给自己的），含可复用字段。" +
            "用于 create_project 前选定 templateId。返回模板列表（id/name/各可复用字段）。")
    public List<Map<String, Object>> listMyTemplates() {
        AdminUser user = currentUser();
        return templateService.listUsableForUser(user).stream()
                .map(t -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("name", t.getName());
                    m.put("pathFieldKey", t.getPathFieldKey());
                    m.put("userSubmitStatusType", t.getUserSubmitStatusType());
                    m.put("userSubmitStatusText", t.getUserSubmitStatusText());
                    m.put("queryFieldKey", t.getQueryFieldKey());
                    m.put("allowResubmit", t.getAllowResubmit());
                    m.put("allowMultiFiles", t.getAllowMultiFiles());
                    m.put("allowOverdue", t.getAllowOverdue());
                    m.put("autoFileNamingEnabled", t.getAutoFileNamingEnabled());
                    m.put("expectedUserFields", readJson(t.getExpectedUserFields()));
                    m.put("pathSegments", readJson(t.getPathSegments()));
                    m.put("allowedSubmitterKeys", readJson(t.getAllowedSubmitterKeys()));
                    m.put("allowedSubmitterList", readJson(t.getAllowedSubmitterList()));
                    m.put("autoFileNamingConfig", readJson(t.getAutoFileNamingConfig()));
                    return m;
                })
                .toList();
    }

    // ====================================================================
    // create_project：选定模板回填 + 入参覆盖，仅 SUPER 可创建
    // ====================================================================
    @Tool(name = "create_project", description =
            "创建一个项目。开始创建前必须先用 ask_user_choice 询问用户“是否使用模板”，并把结果传入 useTemplate。" +
            "(1) useTemplate=true：必须再让用户从 list_my_templates 或本工具返回的 templates 中选择 templateId；以该模板的可复用字段为基底，入参显式提供的字段覆盖基底，未提供则保留模板值；" +
            "此时开关字段（allowResubmit/allowMultiFiles/allowOverdue）继承模板值，不要再向用户提问。" +
            "(2) useTemplate=false：等价手填创建，开关字段需用 ask_user_choice(是/否) 向用户询问，不要读默认值。" +
            "项目特有字段（name 必填；startAt/endAt/fileSizeLimitBytes/allowedFileTypes 按需）始终取自入参，模板不含这些。" +
            "必须先以 confirmed=false 或不传 confirmed 获取预览，再用 ask_user_choice 让用户确认/修改；" +
            "只有用户确认后才允许以 confirmed=true 再次调用并真正创建。创建成功返回用户填写链接 submitUrl。仅 SUPER 角色可创建。")
    public Map<String, Object> createProject(
            @ToolParam(description = "项目名称（必填）") String name,
            @ToolParam(description = "是否使用模板。创建项目第一步必须先问用户，并传入 true/false。", required = false) Boolean useTemplate,
            @ToolParam(description = "模板 ID。useTemplate=true 时必填，来自用户选择的模板。", required = false) Long templateId,
            @ToolParam(description = "开始时间 epoch 毫秒（可选）", required = false) Long startAt,
            @ToolParam(description = "截止时间 epoch 毫秒（可选）", required = false) Long endAt,
            @ToolParam(description = "单文件大小上限字节（可选，null=不限）", required = false) Long fileSizeLimitBytes,
            @ToolParam(description = "允许的文件扩展名列表，如 [\"pdf\",\"zip\"]（可选，null=不限）", required = false) List<String> allowedFileTypes,
            // 可复用字段覆盖（可选；未提供且选了模板则继承模板值）
            @ToolParam(description = "可复用覆盖：是否允许重复提交（可选）", required = false) Boolean allowResubmit,
            @ToolParam(description = "可复用覆盖：是否允许多文件（可选）", required = false) Boolean allowMultiFiles,
            @ToolParam(description = "可复用覆盖：是否允许逾期提交（可选）", required = false) Boolean allowOverdue,
            @ToolParam(description = "可复用覆盖：期望字段配置 JSON（可选）", required = false) String expectedUserFieldsJson,
            @ToolParam(description = "可复用覆盖：上传路径字段 key（可选）", required = false) String pathFieldKey,
            @ToolParam(description = "可复用覆盖：上传路径层级 JSON 数组（可选）", required = false) String pathSegmentsJson,
            @ToolParam(description = "可复用覆盖：状态提示类型 info/warning/success/danger（可选）", required = false) String userSubmitStatusType,
            @ToolParam(description = "可复用覆盖：状态提示文案（可选）", required = false) String userSubmitStatusText,
            @ToolParam(description = "可复用覆盖：查询主键字段（可选）", required = false) String queryFieldKey,
            @ToolParam(description = "可复用覆盖：允许提交者字段 key JSON 数组（可选）", required = false) String allowedSubmitterKeysJson,
            @ToolParam(description = "可复用覆盖：允许提交名单 JSON（可选）", required = false) String allowedSubmitterListJson,
            @ToolParam(description = "可复用覆盖：是否开启自动命名（可选）", required = false) Boolean autoFileNamingEnabled,
            @ToolParam(description = "可复用覆盖：自动命名配置 JSON（可选）", required = false) String autoFileNamingConfigJson,
            @ToolParam(description = "用户是否已确认创建。未确认/false 只返回预览，不创建；用户确认后传 true 才创建。", required = false) Boolean confirmed,
            @ToolParam(description = "预览返回的确认令牌。confirmed=true 时必须提供，且参数不能与预览时不同。", required = false) String confirmationToken
    ) {
        AdminUser user = currentUser();
        if (!isSuper()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅 SUPER 可创建项目");
        }
        if (useTemplate == null) {
            return Map.of(
                    "kind", "template_usage_required",
                    "requiresUserChoice", true,
                    "created", false,
                    "question", "是否使用项目模板？",
                    "options", List.of(
                            Map.of("value", true, "label", "使用模板"),
                            Map.of("value", false, "label", "不使用模板")
                    ),
                    "nextStep", "请调用 ask_user_choice 询问用户是否使用模板。用户选择后，把结果作为 useTemplate 再调用 create_project。"
            );
        }
        if (Boolean.FALSE.equals(useTemplate) && templateId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "useTemplate=false 时不能传 templateId");
        }
        if (Boolean.TRUE.equals(useTemplate) && templateId == null) {
            List<Map<String, Object>> templates = listMyTemplates();
            return Map.of(
                    "kind", "template_selection_required",
                    "requiresUserChoice", true,
                    "created", false,
                    "prompt", "请选择项目模板",
                    "options", templates.stream()
                            .map(t -> Map.of(
                                    "value", t.get("id"),
                                    "label", String.valueOf(t.getOrDefault("name", "模板 " + t.get("id")))
                            ))
                            .toList(),
                    "templates", templates,
                    "nextStep", "请用 ask_user_choice 直接展示 options 供用户选择模板，不要让用户手填 templateId；然后带 useTemplate=true 和所选 templateId 再调用 create_project。"
            );
        }

        CreateProjectRequest req = new CreateProjectRequest();
        req.setName(name);
        req.setStartAt(startAt);
        req.setEndAt(endAt);
        req.setFileSizeLimitBytes(fileSizeLimitBytes);
        req.setAllowedFileTypes(allowedFileTypes);

        // 模板基底 + 入参覆盖
        if (Boolean.TRUE.equals(useTemplate)) {
            ProjectTemplate t = templateService.get(templateId);
            if (!templateService.isUsable(user, t)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用该模板");
            }
            applyTemplateBase(req, t);
        }
        applyOverrides(req, allowResubmit, allowMultiFiles, allowOverdue, expectedUserFieldsJson,
                pathFieldKey, pathSegmentsJson, userSubmitStatusType, userSubmitStatusText,
                queryFieldKey, allowedSubmitterKeysJson, allowedSubmitterListJson,
                autoFileNamingEnabled, autoFileNamingConfigJson);

        Map<String, Object> preview = previewProject(req, useTemplate, templateId);
        if (!Boolean.TRUE.equals(confirmed)) {
            String token = UUID.randomUUID().toString();
            projectCreationConfirmations.put(token, confirmationFingerprint(user, preview));
            return Map.of(
                    "kind", "project_creation_preview",
                    "requiresConfirmation", true,
                    "created", false,
                    "confirmationToken", token,
                    "preview", preview,
                    "nextStep", "请向用户展示以上项目配置，并用 ask_user_choice 让用户选择“确认创建”或“修改”。用户确认后，以相同参数、confirmationToken 和 confirmed=true 再次调用 create_project。用户要修改时，按用户新要求调整参数后重新预览并使用新的 confirmationToken。"
            );
        }
        String expected = confirmationToken == null ? null : projectCreationConfirmations.get(confirmationToken);
        if (expected == null || !expected.equals(confirmationFingerprint(user, preview))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请先预览项目配置并让用户确认；确认创建时必须传入预览返回的 confirmationToken，且参数不能改变");
        }
        projectCreationConfirmations.remove(confirmationToken);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Project p = projectService.create(req, auth);
        ProjectResponse response = projectQueryService.getOne(p.getId(), false);
        Map<String, Object> out = projectPayload(response);
        out.put("kind", "project_created");
        out.put("created", true);
        out.put("submitPath", submitPath(p.getId()));
        out.put("submitUrl", submitUrl(p.getId()));
        return out;
    }

    // ====================================================================
    // list_my_projects：按权限过滤的项目列表
    // ====================================================================
    @Tool(name = "list_my_projects", description =
            "列出当前登录用户有权限查看的项目。SUPER 返回全部；ADMIN 仅返回被分配给自己的项目，看不到别人的。" +
            "用于后续操作（如查询未提交者）前选定 projectId。建议用 ask_user_choice 让用户从结果里选 projectId。")
    public List<Map<String, Object>> listMyProjects() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return projectQueryService.myProjects(auth).stream()
                .map(this::projectPayload)
                .toList();
    }

    // ====================================================================
    // get_project_info：获取项目详情与用户填写链接
    // ====================================================================
    @Tool(name = "get_project_info", description =
            "获取某个项目的详情，并返回该项目的用户填写链接 submitUrl。需对该项目有管理权限。" +
            "建议先用 list_my_projects 让用户选择 projectId。")
    public Map<String, Object> getProjectInfo(
            @ToolParam(description = "项目 ID") Long projectId
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!adminPermissionService.canManageProject(auth, projectId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该项目");
        }
        return projectPayload(projectQueryService.getOne(projectId, true));
    }

    // ====================================================================
    // list_missing_submitters：某项目未提交名单
    // ====================================================================
    @Tool(name = "list_missing_submitters", description =
            "查询某项目尚未提交的允许提交者名单（基于 allowedSubmitterKeys/List 与已提交记录）。" +
            "需对该项目有管理权限（SUPER 或被分配该项目的 ADMIN），否则返回权限错误。" +
            "建议先用 list_my_projects + ask_user_choice 让用户选定 projectId。")
    public Map<String, Object> listMissingSubmitters(
            @ToolParam(description = "项目 ID") Long projectId
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!adminPermissionService.canManageProject(auth, projectId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权管理该项目");
        }
        return projectQueryService.missingAllowed(projectId);
    }

    // ====================================================================
    // create_archive_download_link：生成打包下载链接
    // ====================================================================
    @Tool(name = "create_archive_download_link", description =
            "为某项目生成打包下载链接。逻辑与后台提交列表页“生成打包分享链接/打包按钮”一致：" +
            "后端生成最新有效提交文件的预签名清单并创建 /share?s=... 下载页，用户访问该链接即可打包下载。" +
            "可选 fieldKey/fieldValue 用于按提交者字段前缀过滤；expireSeconds 默认 3600 秒。需项目管理权限。")
    public Map<String, Object> createArchiveDownloadLink(
            @ToolParam(description = "项目 ID") Long projectId,
            @ToolParam(description = "可选：按提交者字段过滤的字段 key", required = false) String fieldKey,
            @ToolParam(description = "可选：按提交者字段过滤的字段值前缀", required = false) String fieldValue,
            @ToolParam(description = "可选：分享链接/预签名有效期秒数，默认 3600", required = false) Long expireSeconds
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!adminPermissionService.canManageProject(auth, projectId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权打包该项目");
        }
        String fk = blankToNull(fieldKey);
        String fv = blankToNull(fieldValue);
        long exp = expireSeconds != null && expireSeconds > 0 ? expireSeconds : 3600L;
        List<ArchiveTaskService.ManifestEntry> entries = archiveTaskService.buildManifest(projectId, fk, fv, exp);
        if (entries.isEmpty()) {
            return Map.of(
                    "kind", "archive_download_link",
                    "created", false,
                    "projectId", projectId,
                    "message", "没有可打包的文件"
            );
        }
        String filename = archiveFilename(projectId, fk, fv);
        List<Map<String, Object>> shareEntries = entries.stream()
                .map(e -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("u", e.getUrl());
                    m.put("f", e.getFilename());
                    m.put("s", e.getSize());
                    return m;
                })
                .toList();
        ShareLinkService.CreatedShare share = shareLinkService.create(projectId, filename, shareEntries, exp);
        return Map.of(
                "kind", "archive_download_link",
                "created", true,
                "projectId", projectId,
                "fieldKey", fk == null ? "" : fk,
                "fieldValue", fv == null ? "" : fv,
                "filename", filename,
                "fileCount", entries.size(),
                "expireAt", share.expireAt(),
                "downloadPath", sharePath(share.code()),
                "downloadUrl", shareUrl(share.code())
        );
    }

    // ====================================================================
    // ask_user_choice：向用户提问让其选择（自包含工具）
    // ====================================================================
    @Tool(name = "ask_user_choice", description =
            "向用户提问并让其从选项中选择，返回所选 value。" +
            "凡需用户在确定选项中做选择的场景（选模板/选项目/开关字段是 否等），优先调用本工具，" +
            "而非让用户在输入框手输这些可选项确定的值。用户取消时返回 {cancelled:true}。")
    public Map<String, Object> askUserChoice(
            @ToolParam(description = "提问说明/标题，向用户清晰呈现要选什么") String prompt,
            @ToolParam(description = "选项数组，每项 {value, label}；value 为返回值，label 为展示文本") List<Map<String, Object>> options
    ) {
        // 该工具由 MCP 客户端/宿主代理实际向用户呈现选项并收集选择。
        // 服务端按 MCP 协议返回"需要用户输入"的 elicitation 请求；这里返回结构化提示，
        // 由宿主（如 ZCode 提问能力）承接呈现与收集，再把所选结果回填。
        // 若宿主直接回填了 selected，则原样返回；否则返回 elicitation 元信息。
        List<Map<String, Object>> opts = options == null ? List.of() : options;
        // 当 options 中存在已带 _selected 标记（宿主回填）时直接返回
        for (Map<String, Object> o : opts) {
            Object sel = o.get("_selected");
            if (Boolean.TRUE.equals(sel)) {
                return Map.of("selected", o.getOrDefault("value", ""), "label", o.getOrDefault("label", ""));
            }
        }
        // 默认：返回提示信息，由宿主承接向用户提问
        return Map.of(
                "kind", "user_choice",
                "prompt", prompt == null ? "" : prompt,
                "options", opts,
                "note", "由接入的 agent 宿主向用户呈现选项并收集选择；用户可取消，取消时返回 cancelled=true"
        );
    }

    // ====================================================================
    // 内部辅助
    // ====================================================================

    private Map<String, Object> previewProject(CreateProjectRequest req, Boolean useTemplate, Long templateId) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("useTemplate", useTemplate);
        m.put("templateId", templateId);
        m.put("name", req.getName());
        m.put("startAt", req.getStartAt());
        m.put("endAt", req.getEndAt());
        m.put("fileSizeLimitBytes", req.getFileSizeLimitBytes());
        m.put("allowedFileTypes", req.getAllowedFileTypes());
        m.put("allowResubmit", req.getAllowResubmit());
        m.put("allowMultiFiles", req.getAllowMultiFiles());
        m.put("allowOverdue", req.getAllowOverdue());
        m.put("expectedUserFields", req.getExpectedUserFields());
        m.put("pathFieldKey", req.getPathFieldKey());
        m.put("pathSegments", req.getPathSegments());
        m.put("userSubmitStatusType", req.getUserSubmitStatusType());
        m.put("userSubmitStatusText", req.getUserSubmitStatusText());
        m.put("queryFieldKey", req.getQueryFieldKey());
        m.put("allowedSubmitterKeys", req.getAllowedSubmitterKeys());
        m.put("allowedSubmitterList", req.getAllowedSubmitterList());
        m.put("autoFileNamingEnabled", req.getAutoFileNamingEnabled());
        m.put("autoFileNamingConfig", req.getAutoFileNamingConfig());
        return m;
    }

    private String confirmationFingerprint(AdminUser user, Map<String, Object> preview) {
        try {
            return user.getUsername() + ":" + M.writeValueAsString(preview);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目预览参数无法序列化");
        }
    }

    private Map<String, Object> projectPayload(ProjectResponse response) {
        Map<String, Object> m = M.convertValue(response, new TypeReference<java.util.LinkedHashMap<String, Object>>() {});
        Long id = response.getId();
        if (id != null) {
            m.put("submitPath", submitPath(id));
            m.put("submitUrl", submitUrl(id));
        }
        return m;
    }

    private String submitPath(Long projectId) {
        return "/user/projects/" + projectId;
    }

    private String submitUrl(Long projectId) {
        return absoluteUrl(submitPath(projectId));
    }

    private String sharePath(String code) {
        return "/share?s=" + code;
    }

    private String shareUrl(String code) {
        return absoluteUrl(sharePath(code));
    }

    private String absoluteUrl(String path) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        if (base.isEmpty()) {
            return path;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private String archiveFilename(Long projectId, String fieldKey, String fieldValue) {
        Project p = projectService.get(projectId);
        String baseName = (p.getName() == null || p.getName().isBlank()) ? ("project-" + projectId) : p.getName().trim();
        if (fieldKey != null && !fieldKey.isBlank() && fieldValue != null && !fieldValue.isBlank()) {
            baseName = baseName + "-" + fieldKey + "-" + fieldValue;
        }
        return baseName + ".zip";
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AdminUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        }
        return userRepo.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
    }

    private boolean isSuper() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_SUPER".equals(ga.getAuthority())) return true;
        }
        return false;
    }

    private void applyTemplateBase(CreateProjectRequest req, ProjectTemplate t) {
        req.setExpectedUserFields(readList(t.getExpectedUserFields()));
        req.setPathFieldKey(t.getPathFieldKey());
        req.setPathSegments(readStrList(t.getPathSegments()));
        req.setUserSubmitStatusType(t.getUserSubmitStatusType());
        req.setUserSubmitStatusText(t.getUserSubmitStatusText());
        req.setQueryFieldKey(t.getQueryFieldKey());
        req.setAllowedSubmitterKeys(readStrList(t.getAllowedSubmitterKeys()));
        req.setAllowedSubmitterList(readJson(t.getAllowedSubmitterList()));
        req.setAutoFileNamingEnabled(t.getAutoFileNamingEnabled());
        req.setAutoFileNamingConfig(readJson(t.getAutoFileNamingConfig()));
        req.setAllowResubmit(t.getAllowResubmit());
        req.setAllowMultiFiles(t.getAllowMultiFiles());
        req.setAllowOverdue(t.getAllowOverdue());
    }

    @SuppressWarnings("unchecked")
    private void applyOverrides(CreateProjectRequest req, Boolean allowResubmit, Boolean allowMultiFiles,
                                Boolean allowOverdue, String expectedUserFieldsJson, String pathFieldKey,
                                String pathSegmentsJson, String userSubmitStatusType, String userSubmitStatusText,
                                String queryFieldKey, String allowedSubmitterKeysJson, String allowedSubmitterListJson,
                                Boolean autoFileNamingEnabled, String autoFileNamingConfigJson) {
        if (allowResubmit != null) req.setAllowResubmit(allowResubmit);
        if (allowMultiFiles != null) req.setAllowMultiFiles(allowMultiFiles);
        if (allowOverdue != null) req.setAllowOverdue(allowOverdue);
        if (expectedUserFieldsJson != null) req.setExpectedUserFields(readList(expectedUserFieldsJson));
        if (pathFieldKey != null) req.setPathFieldKey(pathFieldKey);
        if (pathSegmentsJson != null) req.setPathSegments(readStrList(pathSegmentsJson));
        if (userSubmitStatusType != null) req.setUserSubmitStatusType(userSubmitStatusType);
        if (userSubmitStatusText != null) req.setUserSubmitStatusText(userSubmitStatusText);
        if (queryFieldKey != null) req.setQueryFieldKey(queryFieldKey);
        if (allowedSubmitterKeysJson != null) req.setAllowedSubmitterKeys(readStrList(allowedSubmitterKeysJson));
        if (allowedSubmitterListJson != null) req.setAllowedSubmitterList(readJson(allowedSubmitterListJson));
        if (autoFileNamingEnabled != null) req.setAutoFileNamingEnabled(autoFileNamingEnabled);
        if (autoFileNamingConfigJson != null) req.setAutoFileNamingConfig(readJson(autoFileNamingConfigJson));
    }

    private static Object readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return M.readValue(json, Object.class); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<java.util.Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return M.readValue(json, M.getTypeFactory().constructCollectionType(List.class, java.util.Map.class));
        } catch (Exception e) { return null; }
    }

    private static java.util.List<String> readStrList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return M.readValue(json, M.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<java.util.Map<String, Object>> readList(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List<?> list) {
            List<java.util.Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof java.util.Map<?, ?> m) out.add((java.util.Map<String, Object>) m);
            }
            return out;
        }
        return null;
    }
}
