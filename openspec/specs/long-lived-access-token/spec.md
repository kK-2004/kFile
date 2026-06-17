### Requirement: MCP 长期令牌登录签发

系统 SHALL 提供一个独立于现有 session 登录的端点 `POST /api/mcp/login`，接收 `{username, password}`，使用现有 `AuthenticationManager` 校验账号密码。校验成功后系统 SHALL 签发一个有效期 6 个月（自签发时刻起）的不透明随机 `accessToken`，并将其返回给调用方。校验失败 SHALL 返回 401。

#### Scenario: 正确账号密码签发 6 个月 token
- **WHEN** 调用方以正确的管理员账号密码请求 `POST /api/mcp/login`
- **THEN** 系统 SHALL 返回 200 与 `{accessToken, expiresAt}`，且 expiresAt SHALL 为签发时刻之后 6 个月

#### Scenario: 错误密码登录失败
- **WHEN** 调用方以错误密码请求 `POST /api/mcp/login`
- **THEN** 系统 SHALL 返回 401，且 SHALL NOT 签发任何 token

### Requirement: 令牌存储安全

系统 SHALL 以 `SecureRandom` 生成不少于 32 字节熵的随机 token，并将其以 URL 安全编码返回给调用方一次。系统 SHALL 在数据库中仅存储该 token 的 SHA-256 哈希，SHALL NOT 存储 token 明文。系统 SHALL 记录每个 token 的绑定用户、创建时间、过期时间、吊销状态与最近使用时间。

#### Scenario: 数据库不存明文 token
- **WHEN** 系统签发一个 token
- **THEN** 落库记录 SHALL 仅包含该 token 的 SHA-256 哈希
- **AND** 明文 token SHALL 仅在签发响应中出现一次

### Requirement: 令牌鉴权与安全上下文注入

系统 SHALL 对携带 `Authorization: Bearer <token>` 的请求进行令牌鉴权：对请求中的 token 计算 SHA-256 并查库，命中且未过期且未吊销时，系统 SHALL 以该 token 绑定的 AdminUser 身份建立 Spring Security 认证上下文（authorities 含其角色），使后续权限校验天然复用既有逻辑。鉴权失败（未提供 token / 无效 / 过期 / 已吊销）SHALL 导致请求未认证（受保护资源返回 401）。

#### Scenario: 有效 token 鉴权成功并以绑定用户身份执行
- **WHEN** 请求携带一个有效且未过期未吊销的 token 访问受保护资源
- **THEN** 系统 SHALL 以该 token 绑定的 AdminUser 身份建立认证上下文
- **AND** 该请求内的权限校验 SHALL 与该 AdminUser 直接登录时一致

#### Scenario: 过期 token 鉴权失败
- **WHEN** 请求携带一个已超过 6 个月有效期的 token
- **THEN** 系统 SHALL 视该请求为未认证，受保护资源 SHALL 返回 401

#### Scenario: 已吊销 token 鉴权失败
- **WHEN** 请求携带一个已被吊销的 token
- **THEN** 系统 SHALL 视该请求为未认证，受保护资源 SHALL 返回 401

### Requirement: 令牌不影响现有 session 认证

系统 SHALL 使长期令牌认证与现有 session cookie 认证并存且互不影响。Web 端基于 session 的登录、`/api/admin/auth/**` 流程 SHALL 保持原有行为不变。

#### Scenario: session 登录流程不受令牌机制影响
- **WHEN** 管理员通过 Web 端 session 方式登录并访问 `/api/admin/**`
- **THEN** 系统 SHALL 沿用既有 session 鉴权，行为与引入令牌前完全一致

### Requirement: 令牌吊销

系统 SHALL 允许 SUPER 角色（或 token 所属用户本人）查看与吊销已签发的令牌。吊销后该令牌 SHALL 立即失效。SUPER SHALL 能查看全部用户令牌，普通用户 SHALL 仅能查看与吊销自己的令牌。

#### Scenario: SUPER 吊销某 token
- **WHEN** SUPER 用户请求吊销某 token
- **THEN** 该 token SHALL 被标记为已吊销
- **AND** 之后该 token 的任何请求 SHALL 返回 401

#### Scenario: 用户查看自己的 token 列表
- **WHEN** 某管理员请求查看自己的 token 列表
- **THEN** 系统 SHALL 返回该用户名下 token 的元信息（创建/过期/最近使用/吊销状态），且 SHALL NOT 返回 token 明文
