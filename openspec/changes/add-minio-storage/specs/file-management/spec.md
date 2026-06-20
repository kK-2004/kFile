## ADDED Requirements

### Requirement: SUPER 可访问文件管理入口
系统 SHALL 在 SUPER 管理后台提供一个「文件管理」页面（路由 `/admin/files`），仅对 `SUPER` 角色可见可访问；非 SUPER 用户 MUST 无法访问该页面及其后端接口。

#### Scenario: SUPER 用户看到文件管理导航
- **WHEN** 拥有 `SUPER` 角色的管理员登录后台
- **THEN** 顶部导航出现「文件管理」入口，点击进入 `/admin/files`

#### Scenario: 非 SUPER 用户被拒绝
- **WHEN** `ADMIN`（非 SUPER）用户调用任意 `/api/admin/files/**` 接口
- **THEN** 系统返回 `403 Forbidden`

#### Scenario: 未登录用户被拒绝
- **WHEN** 未认证用户调用 `/api/admin/files/**` 接口
- **THEN** 系统返回 `401 Unauthorized`

### Requirement: 数据源切换
系统 SHALL 支持在文件管理页选择数据源（OSS 与 MinIO），各数据源对应独立的 bucket，彼此互不影响；前端 SHALL 仅展示当前已启用的数据源。

#### Scenario: 列出可用数据源
- **WHEN** 前端请求 `GET /api/admin/files/sources`
- **THEN** 系统返回当前启用的数据源数组（至少包含 `oss`；若 `minio.enabled=true` 则同时包含 `minio`）

#### Scenario: MinIO 未启用时隐藏
- **WHEN** `minio.enabled` 为 `false` 或 `minio.*` 配置缺失
- **THEN** `sources` 仅返回 OSS，前端不显示 MinIO 数据源切换项

#### Scenario: 两个 bucket 独立浏览
- **WHEN** 用户在 OSS 数据源下浏览目录 `/a/`，随后切换到 MinIO 数据源
- **THEN** MinIO 数据源展示其自身根目录，不受 OSS 当前路径影响；切回 OSS 仍停留在 `/a/`

### Requirement: 目录浏览
系统 SHALL 支持按前缀（目录）列出对象，区分「子目录」与「文件」，并通过面包屑支持进入子目录与返回上级。

#### Scenario: 列出根目录
- **WHEN** 用户请求 `GET /api/admin/files/list?source=oss&prefix=`（prefix 为空）
- **THEN** 系统返回根前缀下的子目录与文件列表，每个条目含 `name`、`isDir`、`size`、`lastModified`、`key`

#### Scenario: 进入子目录
- **WHEN** 用户请求 `GET /api/admin/files/list?source=minio&prefix=docs/`
- **THEN** 系统返回 `docs/` 前缀下的下一层子目录与文件，不递归

#### Scenario: 过滤目录占位对象
- **WHEN** 目录由 0 字节占位对象 `.keep` 创建
- **THEN** `list` 结果 MUST 将其所在前缀作为 `isDir=true` 条目返回，而不是作为文件展示 `.keep`

### Requirement: 创建文件夹
系统 SHALL 支持在指定目录下创建文件夹；对象存储无真目录时 MUST 通过约定占位对象实现。

#### Scenario: 创建文件夹
- **WHEN** 用户请求 `POST /api/admin/files/mkdir` `{source, prefix:"docs/", name:"2026"}`
- **THEN** 系统创建占位对象使其出现在列表中，并返回该目录的 key（如 `docs/2026/`）

#### Scenario: 防止路径穿越
- **WHEN** 用户传入含 `..` 或绝对路径的 `name`（如 `../evil`）
- **THEN** 系统 MUST 剥离或拒绝危险片段，最终 key 仍限定在目标 prefix 之下

### Requirement: 上传文件
系统 SHALL 支持将文件上传到指定目录；上传的路径与文件名处理规则 MUST 与现有 OSS 上传逻辑对齐（根前缀归一化、`baseName` 去路径与 `..`、日期分目录）。

#### Scenario: 上传到指定目录
- **WHEN** 用户请求 `POST /api/admin/files/upload?source=minio&prefix=docs/`，附带文件 `report.pdf`
- **THEN** 系统将对象存储到 `docs/yyyy/MM/dd/report.pdf`（日期为当天），返回可访问的代理 URL（如 `/file/minio/docs/yyyy/MM/dd/report.pdf`）

#### Scenario: 文件名安全处理
- **WHEN** 上传文件名为 `/etc/passwd` 或 `../../secret`
- **THEN** 系统 MUST 使用 `baseName` 规则剥离路径与 `..`，最终对象 key 不逃逸出目标 prefix

#### Scenario: 保留同名覆盖语义
- **WHEN** 目标 key 已存在同名对象并再次上传
- **THEN** 系统 MUST 按对象存储默认行为覆盖，不报错（与现有 OSS 上传一致）

### Requirement: 下载文件
系统 SHALL 支持下载文件；MUST 通过与 OSS 对称的下载代理路径 `/file/minio/**` 暴露 MinIO 对象，默认走 302 重定向到预签名直链，支持 `?proxy=1` 强制流式代理与 `?download=1` 强制下载。

#### Scenario: 默认 302 预签名直链
- **WHEN** 浏览器请求 `GET /file/minio/<key>`
- **THEN** 系统返回 `302`，`Location` 指向 MinIO 预签名 GET 直链

#### Scenario: 强制流式代理
- **WHEN** 浏览器请求 `GET /file/minio/<key>?proxy=1`
- **THEN** 系统以流式方式回传对象内容，附带正确的 `Content-Type`/`Content-Length`/`ETag` 头

#### Scenario: 强制下载
- **WHEN** 浏览器请求 `GET /file/minio/<key>?download=1`
- **THEN** 系统（无论 302 还是代理）设置 `Content-Disposition: attachment` 促使浏览器下载

#### Scenario: 公开 GET 访问
- **WHEN** 任意（含未登录）客户端 `GET /file/minio/**`
- **THEN** 系统允许访问（与 `/file/oss/**` 同样在 SecurityConfig 放开 GET）

### Requirement: 删除文件
系统 SHALL 支持删除单个对象；删除 MUST 经前端二次确认，删除后该对象从列表消失。

#### Scenario: 删除单个文件
- **WHEN** 用户请求 `DELETE /api/admin/files?source=oss&key=docs/2026/01/01/x.txt`
- **THEN** 系统删除该对象，后续 `list` 不再返回它

#### Scenario: 删除目录占位对象不级联
- **WHEN** 用户删除一个目录占位对象（`.keep`）对应的目录 key
- **THEN** 系统 MUST 仅删除占位对象本身，不递归删除目录内其它对象（递归删除属 Non-Goal，需用户逐个删除）
