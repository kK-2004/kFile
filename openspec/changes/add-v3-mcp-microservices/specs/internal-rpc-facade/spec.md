## ADDED Requirements

### Requirement: kfile-service 提供内部 RPC 端点
系统必须在 `kfile-v3-service` 上以 `/internal/rpc/projects/**` 与 `/internal/rpc/submissions/**` 为路径前缀暴露内部 RPC 端点。这些端点必须覆盖首批 MCP 工具所需的能力：项目创建、项目读取、项目列表、提交人校验、上传 URL 创建（包装 `direct-init`）、上传完成（包装 `direct-complete`）。

#### Scenario: 项目创建 RPC
- **WHEN** Feign 客户端携带合法的项目 DTO 与已通过授权的内部头，向 `POST /internal/rpc/projects` 发起请求
- **THEN** 服务复用现有服务层逻辑创建项目，并返回创建后的项目 DTO

#### Scenario: 上传初始化 RPC
- **WHEN** Feign 客户端以合法参数调用 `POST /internal/rpc/submissions/upload-init`
- **THEN** 服务返回与现有 `direct-init` HTTP 端点等价的预签名 OSS PUT URL

#### Scenario: 上传完成 RPC
- **WHEN** 客户端 PUT 到 OSS 后，Feign 客户端调用 `POST /internal/rpc/submissions/upload-complete`
- **THEN** 服务完成提交、持久化提交记录，并执行与现有 `direct-complete` HTTP 端点完全一致的文件类型、大小、重复提交、允许名单校验

### Requirement: 内部 RPC 仅允许网关调用
系统必须保证 `/internal/rpc/**` 仅可被 `kfile-v3-gateway` 调用。该约束必须同时通过以下两种手段实施：（a）绑定到非公网监听器或网络 ACL 限制；（b）在每个 `/internal/rpc/**` 请求上要求共享密钥头 `X-KFile-Internal-Token`。缺失或携带错误 `X-KFile-Internal-Token` 的请求必须在控制器方法运行前以 HTTP 401 拒绝。

#### Scenario: 直接外部调用被拒绝
- **WHEN** 除 `kfile-v3-gateway` 之外的任何客户端在未携带合法 `X-KFile-Internal-Token` 的情况下直接调用 `/internal/rpc/projects`
- **THEN** 服务以 HTTP 401 拒绝，且不执行业务逻辑

#### Scenario: 网关调用被接受
- **WHEN** `kfile-v3-gateway` 携带合法 `X-KFile-Internal-Token` 调用 `/internal/rpc/projects`
- **THEN** 服务正常执行该请求

### Requirement: Actor 上下文头透传
系统必须接受每个 `/internal/rpc/**` 请求上的 `X-KFile-Actor`、`X-KFile-Roles`、`X-KFile-Client-IP`、`X-KFile-User-Agent` 以及由网关生成的 `X-KFile-Request-Id` 请求头，并复用 `kk-common` 的 `RequestContext` 承载其中通用字段：`X-KFile-Request-Id` → `RequestContext.traceId`，`X-KFile-Actor` → `RequestContext.userId`，`X-KFile-Client-IP` → `RequestContext.clientIp`。`v3` 专属字段（`roles`、`userAgent`、命名空间）必须封装在请求作用域的 `RpcActorContext` 中，向既有服务层暴露。

#### Scenario: 由请求头构建上下文
- **WHEN** `/internal/rpc/**` 请求携带 `X-KFile-Request-Id=req-1`、`X-KFile-Actor=alice`、`X-KFile-Roles=user`、`X-KFile-Client-IP=10.0.0.1`、`X-KFile-User-Agent=MCP/1.0`
- **THEN** 请求作用域内 `RequestContext.traceId=req-1`、`RequestContext.userId=alice`、`RequestContext.clientIp=10.0.0.1`，且 `RpcActorContext` 暴露 `roles=user`、`userAgent=MCP/1.0`

#### Scenario: 缺失 Actor 头
- **WHEN** 已通过 `X-KFile-Internal-Token` 鉴权的 `/internal/rpc/**` 请求未携带 `X-KFile-Actor`
- **THEN** 服务以 HTTP 400 拒绝，且不执行业务逻辑

### Requirement: 统一响应与异常体系
系统必须在所有 `/internal/rpc/**` 端点上使用 `kk-common` 提供的 `TransDTO<T>`（成功包装单值或对象）与 `PageResponse<T>`（分页结果）作为响应外壳；业务错误必须抛 `BusinessException`（携带 `ErrorCode`），系统错误必须抛 `SystemException`，由 `kk-common` 的全局异常处理器统一转译为 `TransDTO.fail(...)` 形式的错误响应。`/internal/rpc/**` 控制器必须不自定义错误体结构。

#### Scenario: 成功响应使用 TransDTO
- **WHEN** 项目创建 RPC 成功
- **THEN** 响应体为 `TransDTO.success(projectDTO)` 序列化结果，HTTP 状态码 200

#### Scenario: 分页响应使用 PageResponse
- **WHEN** 项目列表 RPC 命中分页参数
- **THEN** 响应体为 `TransDTO<PageResponse<ProjectDTO>>` 形式，包含 `total`、`page`、`size` 与 `records`

#### Scenario: 业务错误经全局处理器输出
- **WHEN** 上传完成 RPC 触发文件类型不允许的业务异常
- **THEN** 控制器抛 `BusinessException`，响应体为 `TransDTO.fail(errorCode, message)`，且不暴露内部堆栈

### Requirement: 复用既有服务层逻辑
系统必须将 `/internal/rpc/**` 控制器实现为既有 `kfile-service` 服务层之上的薄适配层。文件类型校验、大小限制、重复检测、允许名单、项目配额等业务规则必须不在 `/internal/rpc/**` 控制器中重复实现，必须由公共 HTTP 控制器同样调用的服务层代码路径承载。

#### Scenario: 复用文件类型校验
- **WHEN** 上传完成 RPC 提交一个扩展名不在项目允许名单内的文件
- **THEN** 服务以与现有公开 `direct-complete` 端点一致的错误码拒绝该调用

#### Scenario: 复用配额校验
- **WHEN** 在 `user` 命名空间下触发项目创建 RPC，且该用户已超出既有配额
- **THEN** 服务以与现有公开项目创建端点一致的配额错误拒绝该调用
