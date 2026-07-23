## ADDED Requirements

### Requirement: 后端 MCP porter 直连转发

系统 SHALL 通过后端 WebMVC Streamable HTTP MCP server 为 agent 提供单一远程入口，并由 `McpPorter` 将已通过 OAuth bearer 认证的工具调用在本进程交给既有 `ToolCallbackProvider`。MCP server SHALL 直接使用现有工具注册生成 `tools/list` schema；porter SHALL NOT 创建 MCP client、连接本机或远端 MCP server、维护工具清单副本或执行 SSE 重连。

#### Scenario: agent 直接发现并调用后端工具
- **WHEN** 已授权 agent 通过远程 MCP URL 请求 `tools/list` 后调用其中一个业务工具
- **THEN** MCP server SHALL 返回既有 `ToolCallbackProvider` 产生的实时 schema，porter SHALL 在本进程调用对应 tool callback

#### Scenario: 后端没有出站 MCP client
- **WHEN** porter 处理工具发现或工具调用
- **THEN** 系统 SHALL NOT 建立任何出站 MCP client 连接，结果 SHALL 直接来自本地业务工具实现

### Requirement: porter 传播可信 OAuth 身份

porter SHALL 只从 HTTP bearer 认证结果取得管理员身份，并通过不可由工具参数伪造的 MCP request context 将 subject、client、scope 与 resource 传到工具执行边界。执行 tool callback 前系统 SHALL 以对应 AdminUser 和现有角色建立临时安全上下文，执行结束后 SHALL 恢复或清理上下文。系统 SHALL NOT 暴露、接受或依赖 `__kfile_access_token` 等隐藏工具参数，也 SHALL NOT 将 bearer token 存入 MCP session。

#### Scenario: OAuth 用户身份用于工具权限校验
- **WHEN** 一个有效 token 所属管理员调用任意 MCP 业务工具
- **THEN** 工具内取得的当前用户与该 token 的 subject 一致，并沿用该用户现有角色和项目权限

#### Scenario: 工具参数不能伪造身份
- **WHEN** agent 在工具 arguments 中提交 `__kfile_access_token` 或其他自称身份的字段
- **THEN** porter SHALL NOT 使用该字段建立身份，工具调用身份 SHALL 只来自当前 HTTP bearer 认证结果

#### Scenario: 并发用户身份隔离
- **WHEN** 两个不同管理员并发调用 MCP 工具且 transport 在不同执行线程间调度请求
- **THEN** 每次工具执行 SHALL 只看到对应请求的管理员身份，SHALL NOT 泄漏或复用另一个请求的安全上下文

## MODIFIED Requirements

### Requirement: 向用户提问选择工具

系统 SHALL 通过 MCP 暴露一个向用户提问并让其从选项中选择结果的工具 `ask_user_choice`，供 agent 在需要用户做选择的场景调用。该工具 SHALL 接收一个提问标题/说明（prompt）与一组选项（options，每个选项含值 value 与展示标签 label），并返回用户所选的值（或用户取消/拒绝的明确结果）。该工具 SHALL 由 k-File MCP 服务端自包含提供，使任何接入该 MCP 的 agent 均可用，不依赖 agent 宿主的提问能力。该工具 SHALL 经 MCP OAuth access token 鉴权后可用。

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

## REMOVED Requirements

### Requirement: MCP 服务端 SSE 传输

**Reason**: 旧要求把服务端固定为长期令牌保护的 legacy SSE 双端点，无法作为现代 agent 的标准 OAuth 远程 MCP 单一入口。

**Migration**: 使用新增的 OAuth 保护 `/mcp` Streamable HTTP 远程传输；agent 直接配置该 URL 并按 `401` challenge 完成 OAuth。
