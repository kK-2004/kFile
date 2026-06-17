## Context

k-File 技术栈：Spring Boot 3.5.6 / Java 21 / Spring Security 6 / Web MVC（非 WebFlux）/ JPA + MySQL / Vue 3 + Element Plus。

**现状认证**：纯 session cookie（`HttpSessionSecurityContextRepository`，`SessionCreationPolicy.IF_REQUIRED`），登录端点 `POST /api/admin/auth/login`。无 JWT/token。

**现状权限**：
- `AdminPermissionService.canManageProject(auth, projectId)`：SUPER 全过；否则查 `ProjectPermission(user, project)`。
- `AdminProjectController.myProjects()`：SUPER 返回全部；ADMIN 仅返回 `permRepo.findByUser(user)`。
- `AdminProjectController.missingAllowed(id)`：基于 `allowedSubmitterKeys/List` 与已提交 Submission 计算未提交名单。

**现状表单**：`AdminProjectForm.vue` 重型表单，编辑页 `load()` 已有从 ProjectResponse 反序列化还原表单的逻辑（含 expectedFields/pathSegments/autoNameFields 等独立 ref）。

**现状创建**：`ProjectService.create(req, auth)` 接 `CreateProjectRequest`，含非管理员的文件类型白名单校验；`POST /api/projects` 限 `hasRole('SUPER')`。

三个能力（模板、长期令牌、MCP）协同的核心：MCP `create_project` 选定模板 → 回填可复用字段 → 补全项目特有字段 → 复用 `ProjectService.create`。

## Goals / Non-Goals

**Goals:**
- 模板：SUPER 建模板并授权 ADMIN；Web 与 MCP 创建项目时均可选定模板回填可复用字段。
- 长期令牌：6 个月 token，账号密码签发，落库存哈希，凭 token 鉴权并注入 AdminUser 身份，可吊销，与 session 并存。
- MCP：SSE 传输，token 鉴权，暴露 list_my_templates / create_project（支持 templateId）/ list_my_projects / list_missing_submitters，权限零额外代码复用。
- create_project 流程：模板回填 + 入参覆盖 → 复用 ProjectService.create 与既有校验。

**Non-Goals:**
- 不改造 session 登录；不做 OAuth2/JWT；token 不自动续期。
- 模板不做分类/版本/导入导出；仅 SUPER 可建模板。
- MCP 不暴露文件上传、删除、修改项目等高危操作。
- 不做 token scope 细粒度授权（token=绑定 AdminUser 全权身份）。
- 模板不保存 name/startAt/endAt/fileSizeLimitBytes/allowedFileTypes/offline。

## Decisions

### 1. 模板字段范围：显式白名单

模板**只保存**可复用字段：expectedUserFields、pathFieldKey、pathSegments、userSubmitStatusType/Text、queryFieldKey、allowedSubmitterKeys/List、autoFileNamingEnabled/Config、allowResubmit/allowMultiFiles/allowOverdue。**不保存** name、startAt、endAt、fileSizeLimitBytes、allowedFileTypes、offline。存储方式与 `Project` 一致（`@Column(columnDefinition="json")` + ObjectMapper），保证回填数据形状一致。

### 2. 模板权限：ProjectTemplateAssignment，复用 ProjectPermission 模式

- `ProjectTemplate.ownerId`（SUPER）：始终可用、唯一可编辑/删除。
- `ProjectTemplateAssignment`（user ↔ template）：SUPER 分配给 ADMIN，ADMIN 可只读使用。
- 可用模板 = owner=自己 ∪ 被分配给自己。MCP `list_my_templates` 与 Web 下拉共用此查询。

### 3. 创建项目的模板回填：Web 与 MCP 共用 applyTemplateData

抽取 `applyTemplateData(data)`（从 ProjectResponse/TemplateResponse 还原表单 + 独立 ref），Web 编辑页 `load()` 与新建页模板回填共用。

### 4. MCP create_project 的模板合并语义

入参：`{templateId?, name, startAt?, endAt?, fileSizeLimitBytes?, allowedFileTypes?, ...可复用字段覆盖?}`。流程：
1. 若 `templateId` 非空：校验当前用户对该模板可用（owner 或被分配）→ 取模板可复用字段作为基底（含三个开关）。
2. 入参中显式提供的可复用字段覆盖基底；入参中未提供的保留模板值。
3. name 等项目特有字段取自入参（模板本就不含）。
4. 组装 CreateProjectRequest → 调 `ProjectService.create(req, auth)`，沿用既有文件类型/字段校验。
5. 仅 SUPER 可创建（沿用既有约束）。

**开关字段的提示词规则**（影响 agent 行为，写入工具 description 与使用说明）：
- 选了 templateId → 三个开关继承模板值，agent **不再**就这些开关提问。
- 未选 templateId → agent 对每个开关用 `ask_user_choice`（是/否）让用户选，不读默认值、不让用户手输。

这使 agent 的工作流为：list_my_templates → ask_user_choice 选 templateId → 补 name/时间/文件规则 → create_project。

### 5. 长期令牌：不透明随机 token + 落库存 SHA-256 哈希

- `McpAccessToken`：tokenHash、userId、createdAt、expiresAt(=+6月)、revoked、lastUsedAt。
- token 明文 SecureRandom ≥32 字节 URL 安全编码，仅签发时返回一次；DB 存 SHA-256 哈希（防 DB 泄露后直接复用）。
- 否决 JWT：6 月有效期不可吊销（除非黑名单），与"支持吊销"冲突；否决存明文：DB 泄露即冒用。

### 6. token 鉴权注入：McpTokenAuthFilter

自定义 OncePerRequestFilter，置于 `UsernamePasswordAuthenticationFilter` 前，解析 `Authorization: Bearer <token>` → 计算哈希查库 → 校验未过期未吊销 → 构造 `UsernamePasswordAuthenticationToken`（authorities=角色）塞入 SecurityContext → 更新 lastUsedAt。失败不设置认证（后续 401）。MCP 工具执行时 `SecurityContextHolder` 即真实 AdminUser，`canManageProject`/`myProjects` 零改动复用。

### 7. MCP 传输：Spring AI MCP Server Starter（WebMVC SSE）

用 `spring-ai-mcp-server-webmvc-spring-boot-starter`，SSE 端点 `/mcp/sse` + `/mcp/messages`，与 Web MVC 栈兼容。工具用 `@Tool` 注解 Bean 注册。端点由 McpTokenAuthFilter 鉴权（非 permitAll）。
否决手写 SSE：协议层成本高易错。回退方案：starter 与 Boot 3.5.6 冲突时改裸 `io.modelcontextprotocol.sdk:mcp` + 手写 WebMVC SSE。

### 8. 权限逻辑抽取：ProjectQueryService

把 `AdminProjectController.myProjects()` 与 `missingAllowed(id)` 的核心计算抽到 `ProjectQueryService`，controller 与 MCP 工具共用，避免重复实现。

### 9. API 形状

```
# 模板（Web 与 MCP 内部共用 service）
POST/GET/PUT/DELETE  /api/admin/templates          # 仅 SUPER（PUT/DELETE 仅 owner）
GET                  /api/admin/templates/usable    # 当前用户可用模板（通用）
POST/DELETE/GET      /api/admin/users/{uid}/templates/{tid}  # SUPER 分配

# 长期令牌
POST   /api/mcp/login            # permitAll，签发 token
GET    /api/mcp/tokens           # 查自己的（SUPER 可查全部）
DELETE /api/mcp/tokens/{id}      # 吊销（SUPER 或所属用户）

# MCP SSE（token 鉴权）
/mcp/sse, /mcp/messages
```

## Risks / Trade-offs

- **[风险] 模板与 Project 字段演进不同步** → 模板只存可复用子集，演进统一改 applyTemplateData；新增字段默认不进模板。
- **[风险] allowedSubmitterList 模板可很大** → 存 json text 列不设硬上限；必要时加 size 校验。
- **[风险] 6 月 token 泄露=完整 AdminUser 身份** → 仅存哈希 + 可吊销 + 工具集仅低危操作 + 文档强调保管。
- **[取舍] 仅 SUPER 建模板 / token 不做 scope** → 降低复杂度，权限由"绑定用户的角色+项目权限"决定，语义清晰。
- **[风险] SSE 与 Nginx buffering** → deploy.sh/Nginx 对 `/mcp` 关 proxy_buffering、调大 read timeout。
- **[风险] starter 与 Boot 3.5.6 兼容** → 选型核对兼容矩阵，冲突回退裸 sdk。
- **[取舍] create_project 模板合并用"入参覆盖模板基底"** → 灵活（agent 可局部修正），但需文档说明哪些字段来自模板、哪些必填。

## Migration Plan

- 新增依赖与 `template/`、`mcp/`、token 相关包；新增三表（ddl-auto 自动建表）。
- SecurityConfig 增加 token 过滤器与 `/mcp/**`、`/api/mcp/**` 规则；不影响现有 `/api/**` session 流程。
- 抽取 ProjectQueryService，重构 AdminProjectController 调用，回归验证 Web 行为不变。
- 上线后模板与 MCP 均为附加能力，不使用不影响现有 Web 功能。回滚：移除依赖与新增包，三表可保留。

## Resolved Questions

- **MCP 依赖选型**：采用 Spring AI MCP starter（`spring-ai-mcp-server-webmvc-spring-boot-starter`），实现时先验证与 Spring Boot 3.5.6 兼容性，冲突再回退裸 `io.modelcontextprotocol.sdk:mcp` + 手写 WebMVC SSE。
- **MCP SSE 端点前缀**：采用 `/mcp/**`（`/mcp/sse` + `/mcp/messages`），便于 Nginx 单独配置 buffering/timeout。
- **create_project 是否必须选模板**：templateId 可选；不传时等价手填创建，开关字段由 agent 用 ask_user_choice 逐个询问。
- **ask_user_choice 呈现方式**：工具只负责收发选项（prompt + options → 用户所选 value / 取消），向终端用户的呈现交由接入的 agent 宿主（如 ZCode 提问能力），k-File 不自带呈现页面。
- **开关字段默认值**：选了 templateId → 继承模板开关值，agent 不再提问；未选 templateId → agent 对每个开关用 ask_user_choice 询问，不读默认值。
