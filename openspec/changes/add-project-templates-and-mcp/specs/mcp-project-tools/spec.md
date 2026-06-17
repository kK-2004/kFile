## ADDED Requirements

### Requirement: MCP 服务端 SSE 传输

系统 SHALL 提供基于 SSE 传输的 MCP（Model Context Protocol）服务端，作为 Spring Boot 应用的一部分暴露，使用长期令牌鉴权。未通过令牌鉴权的 MCP 连接 SHALL 被拒绝（401）。

#### Scenario: 未携带令牌连接 MCP 被拒
- **WHEN** 客户端在未携带有效长期令牌的情况下连接 MCP SSE 端点
- **THEN** 系统 SHALL 返回 401，拒绝建立 MCP 会话

#### Scenario: 携带有效令牌连接 MCP 成功
- **WHEN** 客户端携带有效长期令牌连接 MCP SSE 端点
- **THEN** 系统 SHALL 建立 MCP 会话并允许工具调用

### Requirement: 可用模板查询工具

系统 SHALL 通过 MCP 暴露一个可用模板查询工具 `list_my_templates`，返回当前令牌绑定用户有权限使用的模板（owner 为自己 ∪ 被分配给自己的），包含可复用字段，供 agent 选定。该查询 SHALL 与 Web 创建项目下拉共用同一来源。

#### Scenario: 查询返回当前用户可用模板
- **WHEN** 某管理员令牌调用 `list_my_templates`
- **THEN** 系统 SHALL 返回 owner 为自己或被分配给自己的模板，含可复用字段

### Requirement: 项目创建工具（支持选定模板回填）

系统 SHALL 通过 MCP 暴露一个项目创建工具 `create_project`，入参 SHALL 支持可选的 `templateId` 与项目字段（name、startAt、endAt、fileSizeLimitBytes、allowedFileTypes，以及可选的可复用字段覆盖）。系统 SHALL 仅在令牌绑定用户具有 SUPER 角色时创建成功，非 SUPER 调用 SHALL 失败。当提供 `templateId` 时，系统 SHALL 先校验当前用户对该模板可用，再以模板可复用字段为基底，用入参中显式提供的字段覆盖、未提供的保留模板值，组合后复用现有 `ProjectService.create` 及其校验逻辑完成创建。

#### Scenario: 选定模板并补全项目特有字段创建成功
- **WHEN** SUPER 用户的令牌调用 `create_project`，提供 templateId=T、name="期末作业"、startAt、endAt、fileSizeLimitBytes、allowedFileTypes
- **THEN** 系统 SHALL 以模板 T 的可复用字段为基底，叠加入参项目特有字段，创建项目
- **AND** 创建结果 SHALL 复用既有校验逻辑

#### Scenario: 入参覆盖模板的部分可复用字段
- **WHEN** SUPER 用户的令牌调用 `create_project`，提供 templateId=T 并显式提供某个可复用字段的新值
- **THEN** 系统 SHALL 使用入参的新值覆盖模板对应字段，其余可复用字段保留模板值

#### Scenario: 不指定模板直接创建
- **WHEN** SUPER 用户的令牌调用 `create_project` 不提供 templateId，并提供全部必要字段
- **THEN** 系统 SHALL 直接使用入参字段创建项目

#### Scenario: 选用了无权使用的模板
- **WHEN** 用户令牌调用 `create_project` 提供其无权使用的 templateId
- **THEN** 系统 SHALL 返回权限错误，且 SHALL NOT 创建项目

#### Scenario: 非 SUPER 调用创建工具失败
- **WHEN** 非 SUPER（ADMIN）用户的令牌调用 `create_project`
- **THEN** 系统 SHALL 返回权限错误，且 SHALL NOT 创建项目

### Requirement: 项目列表查询工具（按权限过滤）

系统 SHALL 通过 MCP 暴露一个项目列表查询工具 `list_my_projects`，返回当前令牌绑定用户有权限查看的项目。SUPER SHALL 能查看全部项目；ADMIN SHALL 仅能查看被分配给自己的项目，SHALL NOT 能查看其他管理员的项目。该过滤 SHALL 复用现有项目权限校验逻辑（`AdminPermissionService` / `ProjectPermission`）。

#### Scenario: SUPER 查询项目列表返回全部
- **WHEN** SUPER 用户的令牌调用 `list_my_projects`
- **THEN** 系统 SHALL 返回全部项目

#### Scenario: ADMIN 仅能查看自己有权限的项目
- **WHEN** ADMIN 用户的令牌调用 `list_my_projects`
- **THEN** 系统 SHALL 仅返回被分配给该 ADMIN 的项目
- **AND** SHALL NOT 返回未分配给该 ADMIN 或属于其他管理员的项目

### Requirement: 未提交者查询工具

系统 SHALL 通过 MCP 暴露一个未提交者查询工具 `list_missing_submitters`，接收一个项目，返回该项目中尚未提交的允许提交者名单。该工具 SHALL 复用现有未提交者计算逻辑（基于 `allowedSubmitterKeys/List` 与已提交记录）。调用者 SHALL 具有对该项目的管理权限（SUPER 或被分配该项目的 ADMIN），否则 SHALL 返回权限错误。

#### Scenario: 有权限用户查询某项目未提交者
- **WHEN** SUPER 或被分配某项目的 ADMIN 令牌对该项目调用 `list_missing_submitters`，且项目配置了允许提交名单
- **THEN** 系统 SHALL 返回尚未提交的名单

#### Scenario: 无权限用户查询未提交者被拒
- **WHEN** 未被分配某项目的 ADMIN 令牌对该项目调用 `list_missing_submitters`
- **THEN** 系统 SHALL 返回权限错误，且 SHALL NOT 返回任何名单

#### Scenario: 项目未配置允许提交名单
- **WHEN** 有权限用户对未配置允许提交名单的项目调用 `list_missing_submitters`
- **THEN** 系统 SHALL 返回明确提示（如 enabled=false / 未配置允许提交名单）

### Requirement: 向用户提问选择工具

系统 SHALL 通过 MCP 暴露一个向用户提问并让其从选项中选择结果的工具 `ask_user_choice`，供 agent 在需要用户做选择的场景调用。该工具 SHALL 接收一个提问标题/说明（prompt）与一组选项（options，每个选项含值 value 与展示标签 label），并返回用户所选的值（或用户取消/拒绝的明确结果）。该工具 SHALL 由 k-File MCP 服务端自包含提供，使任何接入该 MCP 的 agent 均可用，不依赖 agent 宿主的提问能力。该工具 SHALL 经长期令牌鉴权后可用。

#### Scenario: agent 用提问工具让用户选模板
- **WHEN** agent 在 create_project 流程中需要用户选定模板，先调用 list_my_templates 获取模板列表，再调用 ask_user_choice 以这些模板作为选项向用户提问
- **THEN** 工具 SHALL 向用户呈现选项，并返回用户所选模板的 value（templateId）

#### Scenario: agent 用提问工具让用户选项目
- **WHEN** agent 需要对多个项目操作（如查询未提交者），先调用 list_my_projects 获取列表，再调用 ask_user_choice 以这些项目作为选项向用户提问
- **THEN** 工具 SHALL 向用户呈现项目选项，并返回用户所选项目的 value（projectId）

#### Scenario: 开关类字段用提问工具让用户选是/否
- **WHEN** agent 在未选定模板（create_project 未提供 templateId）的情况下需要确定某开关字段（如 allowResubmit、allowMultiFiles、allowOverdue）取值，调用 ask_user_choice 以"是/否"作为选项向用户提问
- **THEN** 工具 SHALL 向用户呈现是/否选项，并返回用户所选布尔值

#### Scenario: 选定模板时开关字段继承模板值不再提问
- **WHEN** agent 已在 create_project 中提供 templateId
- **THEN** 开关字段（allowResubmit、allowMultiFiles、allowOverdue）SHALL 直接继承模板中的值
- **AND** agent SHALL NOT 对这些已由模板确定的开关字段再调用 ask_user_choice 提问

#### Scenario: 用户取消选择
- **WHEN** 用户对 ask_user_choice 的提问选择取消或拒绝
- **THEN** 工具 SHALL 返回明确的"已取消"结果，agent SHALL NOT 继续后续依赖该选择的操作

### Requirement: Agent 提示词优先使用提问工具

k-File MCP 服务端在工具描述（tool description）与使用说明文档中 SHALL 向接入的 agent 声明如下规范：凡是需要用户在多个确定选项中做选择的场景（包括但不限于选定模板、选定项目、确定开关字段取值），agent SHALL 优先调用 `ask_user_choice` 工具向用户呈现选项让其选择，SHALL NOT 让用户在自由输入框中手动输入这些本可由选项确定的值。

#### Scenario: 多模板可选时优先提问而非手输
- **WHEN** 当前用户有多个可用模板，agent 需要确定 create_project 的 templateId
- **THEN** agent SHALL 调用 ask_user_choice 让用户从可用模板中选择，SHALL NOT 要求用户手输 templateId

#### Scenario: 开关取值优先提问而非手输
- **WHEN** agent 需要确定某开关字段取值，且该值未由已选模板确定
- **THEN** agent SHALL 调用 ask_user_choice 以是/否选项让用户选择，SHALL NOT 要求用户手输 true/false
- **AND** 当 create_project 已提供 templateId 时，开关字段 SHALL 继承模板值，agent SHALL NOT 对其提问

### Requirement: 工具调用复用既有权限上下文

MCP 工具执行时，系统 SHALL 以令牌绑定的 AdminUser 身份建立安全上下文，使工具内部的权限校验与该用户通过 Web 直接操作时完全一致。系统 SHALL NOT 在 MCP 层引入独立于既有角色与项目权限的第二套授权规则。

#### Scenario: MCP 工具与 Web 端权限行为一致
- **WHEN** 同一 AdminUser 分别通过 MCP 工具与 Web 端访问同一受项目权限保护的资源
- **THEN** 两者的允许/拒绝结果 SHALL 一致
