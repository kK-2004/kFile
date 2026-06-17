## Why

k-File 当前存在两个相互关联的运营痛点：

1. **项目创建重复劳动**：创建项目需配置收集字段、存储路径、状态提示、文件自动命名、提交者限制等十余区块，同类项目（课程作业、比赛收集）配置高度重复，管理员每次从零填写，效率低且易错。
2. **无法程序化运营**：项目创建、查看项目、催交（查谁没提交）只能通过 Web 界面人工操作，无法让 AI agent 自动化完成。

引入"项目模板"沉淀可复用配置、引入"MCP 服务端"暴露操作工具，二者协同：agent 可先查看自己有权限的模板，选定后补全模板缺失的少量项目特有信息（名称、时间、文件规则），即可一键创建项目，最大化降低人工与重复。

## What Changes

**A. 项目模板能力**
- 新增实体 `ProjectTemplate`，存储可复用配置快照。**只保存**可复用字段（expectedUserFields、pathFieldKey/pathSegments、userSubmitStatusType/Text、queryFieldKey、allowedSubmitterKeys/List、autoFileNamingEnabled/Config、allowResubmit/allowMultiFiles/allowOverdue），**不保存** name、startAt、endAt、fileSizeLimitBytes、allowedFileTypes、offline。
- 新增实体 `ProjectTemplateAssignment`（user ↔ template），SUPER 可把模板授权给 ADMIN 使用。
- 新增 REST API：SUPER 的模板 CRUD、SUPER 给 ADMIN 分配/撤销模板、任意管理员查看自己可用模板。
- 前端 `AdminProjectForm.vue` 新建项目顶部新增"使用模板"下拉（默认不使用），选择后回填可复用字段；SUPER 显示"保存为模板"按钮。
- 前端 `AdminUsers.vue` 权限抽屉新增"可用模板"分配区。

**B. 长期令牌认证**
- 引入独立于现有 session 的长期令牌：MCP 首次使用前用账号密码登录，后端签发 6 个月有效期的 `accessToken`（不透明随机串，DB 存 SHA-256 哈希），MCP 服务端持久化保存，之后每次工具调用携带它鉴权。与 session 认证并存互不影响。

**C. MCP 服务端（SSE）**
- 新增基于 SSE 传输的 MCP 服务端，token 鉴权后以绑定 AdminUser 身份注入安全上下文，零额外权限代码复用既有校验。暴露工具：
  1. **查看可用模板** `list_my_templates`：返回当前令牌用户有权限使用的模板（owner=自己 ∪ 被分配给自己的），含可复用字段，供 agent 选定。
  2. **创建项目** `create_project`：入参支持可选 `templateId` + 项目特有字段（name、startAt、endAt、fileSizeLimitBytes、allowedFileTypes）及可复用字段覆盖。系统先按选定模板回填可复用字段，再用入参覆盖补充，复用 `ProjectService.create` 与既有校验；仅 SUPER 可创建。
  3. **查看项目列表** `list_my_projects`：自己有权限的项目（SUPER 全部，ADMIN 仅分配的），复用 `AdminPermissionService`/`ProjectPermission`，不能看别人的。
  4. **查看未提交者** `list_missing_submitters`：传入项目，返回未提交名单，前置 `canManageProject` 校验，复用现有未提交者计算逻辑。
  5. **向用户提问选择** `ask_user_choice`：自包含工具，接收提问说明与一组选项（value+label），向用户呈现选项并返回所选值；使任何接入的 agent 都能用它让用户"选项选择"而非"输入框手输"。配套在工具描述与使用说明中声明提示词规范：凡需用户在确定选项中选择的场景（选模板/选项目/开关字段等），agent SHALL 优先调用本工具，SHALL NOT 让用户手输这些可选项确定的值。
- 新增 token 管理 UI（查看/吊销）。

## Capabilities

### New Capabilities

- `project-templates`: 项目模板的存储、复用与权限分配。模板保存可复用配置，SUPER 创建并授权 ADMIN，Web 与 MCP 均可在创建项目时下拉选择并回填。
- `long-lived-access-token`: 长期访问令牌认证。账号密码登录签发 6 个月 token，落库存哈希，凭 token 鉴权并注入对应 AdminUser 身份，支持吊销。
- `mcp-project-tools`: 基于 SSE 传输的 MCP 服务端，暴露模板查询、项目创建（支持选定模板回填）、项目列表查询、未提交者查询工具，token 鉴权。

### Modified Capabilities

- `admin-user-management`: "管理员与权限设置"页权限配置范畴扩展，除项目权限外新增"可用模板"分配（现有 spec 位于 `openspec/specs/admin-user-management/`）。

## Impact

- **后端新增**：`template/` 包（ProjectTemplate/Assignment entity、repo、dto、service、controller）、`mcp/` 包（MCP 服务端配置、工具定义）、`security/` 扩展（McpAccessToken entity/repo/service、McpTokenAuthFilter、McpAuthController）；`ProjectQueryService`（从 AdminProjectController 抽取 myProjects/missingAllowed 共用逻辑）。
- **依赖**：pom.xml 新增 MCP SDK（优先 spring-ai-mcp-server-webmvc-starter，兼容性冲突则回退裸 mcp sdk + 手写 WebMVC SSE）。
- **数据库**：新增 `project_templates`、`project_template_assignments`、`mcp_access_tokens` 三表（ddl-auto 自动建表）。
- **安全配置**：SecurityConfig 增加 token 过滤器与 `/mcp/**`、`/api/mcp/**` 规则，不影响现有 session 流程。
- **前端**：AdminProjectForm.vue（模板下拉 + SUPER 保存按钮）、AdminUsers.vue（模板分配区 + token 管理）、api/index.js（模板与 MCP token 客户端方法）。
- **复用**：ProjectService.create、AdminPermissionService.canManageProject、myProjects/missingAllowed 逻辑被 Web 与 MCP 共用。
- **部署**：Nginx 对 `/mcp` 关闭 buffering、调大 timeout 适配 SSE。
- **不影响**：用户端提交、session 登录、文件上传/OSS。
