# mcp-oauth-authorization Specification

## Purpose
自研 MCP OAuth 2.1 授权服务器：Authorization Code + PKCE、动态客户端注册（DCR）、不透明令牌（SHA-256 落库）、refresh 轮换与吊销、protected-resource 元数据发现，签发 scope=mcp:tools 的短期 token 供 /mcp 工具调用。
## Requirements
### Requirement: MCP 受保护资源与授权服务发现

系统 SHALL 为规范化远程 MCP URL 发布 OAuth 2.0 Protected Resource Metadata，metadata SHALL 包含与该 MCP URL 完全一致的 `resource`、至少一个 `authorization_servers` 值及支持的 `mcp:tools` scope。授权服务 SHALL 发布 OAuth 2.0 Authorization Server Metadata，声明 authorization、token、client registration、revocation endpoints 与 `S256` PKCE 支持。

#### Scenario: agent 发现 MCP 授权服务
- **WHEN** agent 读取 MCP URL 对应的 protected-resource metadata
- **THEN** 系统 SHALL 返回规范化 resource URI、authorization server URI 与 `mcp:tools` scope
- **AND** agent SHALL 能从 authorization server metadata 得到继续 OAuth 流程所需的 endpoints 和 `S256` 能力

#### Scenario: metadata 使用可信公共基址
- **WHEN** 请求携带与部署配置不一致的 `Host` 或转发头
- **THEN** 系统 SHALL 仍使用配置的公共基址生成 issuer、resource 与 endpoint URL，SHALL NOT 将不受信任的请求头反射进 metadata

### Requirement: 未认证 MCP 请求触发标准 OAuth challenge

系统 SHALL 在远程 MCP 请求未携带 access token、token 无效或 token 已过期时返回 HTTP `401 Unauthorized`。响应 SHALL 包含 Bearer `WWW-Authenticate` challenge，并通过 `resource_metadata` 指向该 MCP 资源的 metadata，同时通过 `scope` 声明 `mcp:tools`；系统 SHALL NOT 用 MCP tool result 或自定义登录工具替代 HTTP challenge。

#### Scenario: 首次连接收到可发现的 401
- **WHEN** agent 未携带 bearer token 请求远程 MCP URL
- **THEN** 系统 SHALL 返回 `401` 与包含 `resource_metadata`、`scope="mcp:tools"` 的 Bearer `WWW-Authenticate` header

#### Scenario: 失效 token 重新触发授权
- **WHEN** agent 使用已过期、已吊销或无法识别的 access token 请求远程 MCP URL
- **THEN** 系统 SHALL 返回同样可发现的 `401` challenge，使 agent 能刷新 token 或重新发起授权

### Requirement: OAuth client 自动注册与回调约束

授权服务 SHALL 支持预注册 public client 和 Dynamic Client Registration。动态注册 SHALL 仅接受允许的 OAuth grant/response 类型及 `token_endpoint_auth_method=none`，SHALL 精确保存已注册 redirect URI；localhost redirect URI MAY 使用 HTTP，非 localhost redirect URI MUST 使用 HTTPS。授权请求中的 redirect URI SHALL 与注册值完全匹配，系统 SHALL NOT 使用字符串前缀匹配。

#### Scenario: 未知 agent 自动注册 public client
- **WHEN** 一个尚无 client id 的兼容 agent 提交合法的动态注册请求
- **THEN** 系统 SHALL 返回可用于 Authorization Code + PKCE 的 public client id 与注册元数据

#### Scenario: 非法回调地址注册被拒
- **WHEN** 动态注册包含非 localhost 的 HTTP redirect URI、非 URL 值或不允许的 URI scheme
- **THEN** 系统 SHALL 拒绝注册且 SHALL NOT 创建 client 记录

#### Scenario: 授权请求回调地址必须精确匹配
- **WHEN** authorization request 的 redirect URI 未与该 client 的任一注册值完全一致
- **THEN** 系统 SHALL 拒绝授权且 SHALL NOT向该 redirect URI发送 code 或 error

### Requirement: 基于现有管理员登录的 Authorization Code + PKCE 授权

授权服务 SHALL 只支持带 `S256` PKCE 的 Authorization Code 流程。授权请求 SHALL 包含有效的 client id、redirect URI、`state`、`resource`、`code_challenge`、`code_challenge_method=S256` 与允许的 scope。未登录用户 SHALL 先完成现有 Web session 登录并返回授权流程；已登录用户 SHALL 看到 client、redirect URI 与 scope 并可批准或拒绝。批准后系统 SHALL 签发短时、单次使用且绑定 client/redirect URI/resource/PKCE challenge/用户的 authorization code，并原样返回 `state`；系统 SHALL NOT 在浏览器回调 URI 中返回 access token。

#### Scenario: 未登录用户登录后继续授权
- **WHEN** 未建立 Web session 的管理员打开有效 authorization request
- **THEN** 系统 SHALL 引导其完成现有管理员登录，并在登录成功后恢复同一个授权请求而不丢失其安全参数

#### Scenario: 用户批准并返回授权码
- **WHEN** 已登录管理员批准一个有效授权请求
- **THEN** 系统 SHALL 将短时单次 authorization code 与原始 `state` 返回已注册 redirect URI
- **AND** 回调 URL SHALL NOT 包含 access token 或 refresh token

#### Scenario: PKCE 缺失或方法不安全时拒绝
- **WHEN** authorization request 未提供 code challenge、使用非 `S256` 方法或 token request 的 verifier 不匹配
- **THEN** 系统 SHALL 拒绝请求且 SHALL NOT 签发可用 token

#### Scenario: 用户拒绝授权
- **WHEN** 已登录管理员拒绝授权
- **THEN** 系统 SHALL 按 OAuth 错误响应向已验证 redirect URI 返回 `access_denied` 与原始 `state`，且 SHALL NOT 创建授权 grant

### Requirement: OAuth token 绑定、刷新与安全存储

系统 SHALL 通过 token endpoint 为成功兑换的 authorization code 签发短期不透明 access token，并可签发可轮换的 refresh token。token SHALL 绑定 client、管理员、scope 与规范化 MCP resource；数据库 SHALL 仅保存 authorization code、access token 和 refresh token 的不可逆哈希及必要元数据，SHALL NOT 保存明文。每个 authorization code SHALL 只能兑换一次；每次 refresh SHALL 轮换 refresh token，重复使用已轮换 token SHALL 吊销同一 token family。token 不得通过 URL 或 MCP 工具参数传递。

#### Scenario: 正确 code verifier 换取 token
- **WHEN** agent 使用未消费 authorization code、正确 code verifier、匹配的 client/redirect URI/resource 请求 token endpoint
- **THEN** 系统 SHALL 返回绑定 `mcp:tools` scope 与该 MCP resource 的短期 access token，并在允许时返回 refresh token

#### Scenario: access token audience 不匹配
- **WHEN** agent 使用为其他 resource 签发的 access token 请求当前 MCP URL
- **THEN** 系统 SHALL 拒绝该 token 并返回可发现的 `401` challenge

#### Scenario: refresh token 正常轮换
- **WHEN** agent 使用有效 refresh token 请求新 access token
- **THEN** 系统 SHALL 签发新的 access token 与新的 refresh token，并使旧 refresh token 不再可用

#### Scenario: 检测 refresh token 重用
- **WHEN** 已轮换的旧 refresh token 再次被提交
- **THEN** 系统 SHALL 拒绝请求并吊销同一授权链中的活动 token，要求用户重新授权

#### Scenario: 数据库和日志不含明文 token
- **WHEN** 系统签发或使用 authorization code、access token 或 refresh token
- **THEN** 持久化记录和应用日志 SHALL NOT 包含这些凭据的明文

### Requirement: OAuth 授权撤销与用户状态联动

系统 SHALL 提供标准 token revocation endpoint，并允许已登录管理员查看和撤销自己授予的 MCP OAuth client；SUPER MAY 按现有管理权限查看和撤销全部授权。授权撤销、管理员被禁用或删除后，相关 access token 与 refresh token SHALL 立即不可用于 MCP。

#### Scenario: 用户撤销某个 agent 的授权
- **WHEN** 管理员撤销自己授予某 OAuth client 的 MCP 权限
- **THEN** 系统 SHALL 吊销该 grant 下的 access token 与 refresh token，后续 MCP 请求 SHALL 返回 `401`

#### Scenario: 禁用用户使 OAuth token 失效
- **WHEN** 一个已有 MCP OAuth grant 的管理员账号被禁用
- **THEN** 系统 SHALL 拒绝其所有现存 access token 和 refresh token，且 SHALL NOT 以该用户身份执行工具

### Requirement: OAuth 与 Web session 认证隔离

系统 SHALL 使 OAuth Authorization Server、MCP bearer Resource Server 与现有 Web session 使用独立的安全匹配规则。MCP bearer token SHALL 只用于规范化 MCP resource，SHALL NOT 让调用方直接访问普通管理 API；Web session 登录、登出和 `/api/admin/**` 权限行为 SHALL 保持不变。

#### Scenario: MCP token 不能访问普通管理 API
- **WHEN** 调用方仅携带 MCP access token 请求非 MCP 的受保护管理 API
- **THEN** 系统 SHALL NOT 因该 token 授予 Web API 访问权限

#### Scenario: Web session 流程保持兼容
- **WHEN** 管理员通过现有登录页建立 session 并访问管理端
- **THEN** 系统 SHALL 保持变更前的登录、登出、角色与项目权限行为

