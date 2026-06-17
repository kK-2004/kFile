## 1. 模板：后端实体与仓储

- [x] 新建 `com.kk.template.entity.ProjectTemplate`：id、name、ownerId(=AdminUser.id)、可复用字段（expectedUserFields/pathFieldKey/pathSegments/userSubmitStatusType/userSubmitStatusText/queryFieldKey/allowedSubmitterKeys/allowedSubmitterList/autoFileNamingEnabled/autoFileNamingConfig/allowResubmit/allowMultiFiles/allowOverdue，JSON 字段 `@Column(columnDefinition="json")`）、createdAt
- [x] 新建 `com.kk.template.entity.ProjectTemplateAssignment`：id、user(ManyToOne AdminUser)、template(ManyToOne ProjectTemplate)，唯一约束 (user_id, template_id)
- [x] 新建 `ProjectTemplateRepository`（findById、findByOwnerId、findAll）与 `ProjectTemplateAssignmentRepository`（findByUser、findByTemplate、findByUserAndTemplate、deleteByTemplate、deleteByUserAndTemplate）
- [x] 确认 ddl-auto 自动建表（核对 application*.yml 现有值）

## 2. 模板：DTO 与 Service

- [x] 2.1 新建 `ProjectTemplateRequest`（保存/更新入参，只含可复用字段 + name）
- [x] 2.2 新建 `ProjectTemplateResponse`（完整字段）与 usable 精简视图 DTO（仅回填所需字段 + id + name）
- [x] 2.3 新建 `ProjectTemplateService`：save（仅可复用字段、ownerId 取当前 SUPER）、update（仅 owner）、delete（仅 owner，级联清理 assignment）、listUsableForUser（owner=me ∪ 被分配）、findById；JSON 序列化沿用现有 Project/ProjectService 模式

## 3. 模板：Controller

- [x] 3.1 新建 `ProjectTemplateController`（`/api/admin/templates`）：POST 创建、GET 全部、PUT/{id}、DELETE/{id}，均 `@PreAuthorize("hasRole('SUPER')")`，更新/删除校验 owner
- [x] 3.2 增加 `GET /api/admin/templates/usable`（任意已登录管理员，返回我可用的精简模板列表；Web 下拉与 MCP list_my_templates 共用此 service 方法）
- [x] 3.3 在 `AdminUserController` 增加模板分配：`POST/DELETE /api/admin/users/{userId}/templates/{templateId}`、`GET /api/admin/users/{userId}/templates`，均 `@PreAuthorize("hasRole('SUPER')")`

## 4. 长期令牌：实体、仓储、Service

- [x] 4.1 新建 `com.kk.security.entity.McpAccessToken`：tokenHash(SHA-256)、user(ManyToOne AdminUser)、createdAt、expiresAt(createdAt+6月)、revoked(默认 false)、lastUsedAt；唯一约束 tokenHash
- [x] 4.2 新建 `McpAccessTokenRepository`：findByTokenHash、findByUser、findAll
- [x] 4.3 新建 `McpTokenService`：issue(username,password) 用 AuthenticationManager 校验后生成 ≥32 字节 SecureRandom 随机串 URL 安全编码，落库存哈希，expiresAt=now+6月，返回明文 token+expiresAt；authenticate(rawToken) 算哈希查库校验未过期未吊销返回 AdminUser+authorities，更新 lastUsedAt；revoke/listForUser/listAll
- [x] 4.4 确认 ddl-auto 自动建 `mcp_access_tokens` 表

## 5. 长期令牌：过滤器与 SecurityConfig

- [x] 5.1 新建 `McpTokenAuthFilter`（OncePerRequestFilter）：解析 `Authorization: Bearer <token>`，调 McpTokenService.authenticate，成功构造 `UsernamePasswordAuthenticationToken`（authorities=角色）塞入 SecurityContextHolder，失败不设置认证
- [x] 5.2 在 SecurityConfig 注册过滤器（置于 UsernamePasswordAuthenticationFilter 前），与 session 认证并存
- [x] 5.3 配置授权规则：`/api/mcp/login` permitAll；`/mcp/**` 与 `/api/mcp/**`（除 login）需认证；其余 `/api/**` 保持既有 session 行为

## 6. 长期令牌：Controller

- [x] 6.1 新建 `McpAuthController`：`POST /api/mcp/login`（复用 AuthenticationManager，调 McpTokenService.issue）
- [x] 6.2 `GET /api/mcp/tokens`（列出当前用户 token；SUPER 可看全部）、`DELETE /api/mcp/tokens/{id}`（吊销；SUPER 或所属用户）；返回元信息不含明文 token
- [x] 6.3 验证错误密码返回 401、明文 token 仅签发时返回一次

## 7. 权限逻辑抽取（Web 与 MCP 共用）

- [x] 7.1 将 `AdminProjectController.myProjects()` 的项目过滤逻辑抽到 `ProjectQueryService`，controller 与 MCP 共用
- [x] 7.2 将 `AdminProjectController.missingAllowed(id)` 的计算逻辑抽到 `ProjectQueryService`，controller 与 MCP 共用
- [x] 7.3 重构 AdminProjectController 调用抽取后的 service，回归验证 Web 端行为不变

## 8. MCP 服务端与工具

- [x] 8.1 在 pom.xml 添加 MCP 服务端依赖（优先 spring-ai-mcp-server-webmvc-spring-boot-starter；核对与 Spring Boot 3.5.6 兼容性，冲突改用裸 io.modelcontextprotocol.sdk:mcp + 手写 WebMVC SSE）
- [x] 8.2 新建 `com.kk.mcp` 包，配置 MCP 服务端 Bean（SSE 传输，WebMVC，端点前缀 /mcp）
- [x] 8.3 定义工具 `list_my_templates`：无入参；调 ProjectTemplateService.listUsableForUser（当前认证身份），返回含可复用字段
- [x] 8.4 定义工具 `create_project`：入参 templateId?（可选）+ 项目特有字段（name/startAt/endAt/fileSizeLimitBytes/allowedFileTypes）+ 可复用字段覆盖（可选）；校验当前认证为 SUPER；若提供 templateId 校验用户对该模板可用→以模板可复用字段为基底，入参显式字段覆盖、未提供保留模板值→组装 CreateProjectRequest→调 ProjectService.create(req,auth)；非 SUPER 或无权模板返回错误
- [x] 8.5 定义工具 `list_my_projects`：无入参；调 ProjectQueryService，按当前认证身份过滤（SUPER 全部，ADMIN 仅分配的）
- [x] 8.6 定义工具 `list_missing_submitters`：入参 projectId；前置 AdminPermissionService.canManageProject(auth,id)，无权限返回错误；有权限调 ProjectQueryService.missingAllowed
- [x] 8.7 定义工具 `ask_user_choice`：自包含向用户提问选择工具；入参 prompt（提问说明）+ options（每项 value+label）；向用户呈现选项并返回所选 value，用户取消时返回明确的"已取消"结果；token 鉴权后可用
- [x] 8.8 为每个工具补充清晰 description（含参数与模板回填语义），便于 agent 理解；并在工具描述/服务端声明中加入"优先使用 ask_user_choice 让用户选择而非输入框手输"的提示词规范

## 9. 前端：模板回填（Web）

- [x] 9.1 在 `api/index.js` 增加：adminCreateTemplate / adminListTemplates / adminUpdateTemplate / adminDeleteTemplate / adminListUsableTemplates / adminListUserTemplates(userId) / adminGrantTemplate(userId,templateId) / adminRevokeTemplate(userId,templateId)
- [x] 9.2 在 `AdminProjectForm.vue` 抽取 `applyTemplateData(data)`（从 TemplateResponse/ProjectResponse 还原表单 + expectedFields/pathSegments/autoNameFields 等 ref），编辑页 load() 与模板回填共用
- [x] 9.3 新建项目表单顶部新增"使用模板"下拉（默认"不使用模板"），onMounted 拉取 adminListUsableTemplates
- [x] 9.4 选择模板调用 applyTemplateData 回填可复用字段，确保 name/startAt/endAt/fileSizeLimitBytes/allowedFileTypes 保持为空；切换/取消时重置避免残留
- [x] 9.5 SUPER 显示"保存为模板"按钮（auth.role===SUPER），点击弹模板名输入，调 adminCreateTemplate（仅提交可复用字段）

## 10. 前端：权限设置页与令牌管理

- [x] 10.1 在 `AdminUsers.vue` 权限配置抽屉新增"可用模板"区域，加载全部模板标记当前用户已分配，保存时 diff 调 adminGrantTemplate/adminRevokeTemplate；模板分配区仅 SUPER 可见
- [x] 10.2 在 `api/index.js` 增加 mcpLogin / mcpListTokens / mcpRevokeToken
- [x] 10.3 在"管理员与权限设置"或"设置"页新增"MCP 访问令牌"区块：列出当前用户（SUPER 看全部）token 元信息 + 吊销；签发后仅展示一次明文并提示复制；非 SUPER 仅自己的 token

## 11. 部署与文档

- [x] 11.1 deploy.sh / Nginx 配置或部署文档对 `/mcp` 关闭 proxy_buffering、调大 proxy_read_timeout 适配 SSE
- [x] 11.2 编写 MCP 使用说明：/api/mcp/login 获取 token、配置 agent（SSE endpoint + Bearer token）、可用工具列表（含 ask_user_choice）、create_project 的模板回填工作流（list_my_templates → ask_user_choice 让用户选 templateId → 补 name/时间/文件规则 → create_project）；并写入 agent 提示词规范——选了 templateId 则开关继承模板值不再提问；未选 templateId 则每个开关用 ask_user_choice(是/否) 让用户选、不读默认值；选模板/选项目等选项场景优先用 ask_user_choice 而非输入框手输

## 12. 端到端验证

- [x] 12.1 用 IntelliJ build_project 编译后端，确认无错误
- [ ] 12.2 模板：SUPER 保存模板→分配 ADMIN→ADMIN Web 下拉看到并回填→ADMIN MCP list_my_templates 返回一致→删除模板不影响已建项目
- [ ] 12.3 令牌：/api/mcp/login 签发 token；过期/吊销后工具调用 401；Web session 登录与既有接口行为未受影响
- [ ] 12.4 MCP 工具：create_project（SUPER 成功含选定模板回填、ADMIN 失败、无权模板失败）、list_my_projects（SUPER 全部/ADMIN 仅分配的）、list_missing_submitters（有/无权限两种）、ask_user_choice（正常选择与用户取消两种）
- [ ] 12.5 用真实 MCP 客户端（或 curl 模拟 SSE）连通 /mcp 端点并调用五个工具；验证 agent 在 create_project 流程中能按提示词规范优先用 ask_user_choice 让用户选模板而非手输
