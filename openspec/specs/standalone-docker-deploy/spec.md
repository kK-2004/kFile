## ADDED Requirements

### Requirement: Docker 镜像包含后端 JAR

Docker 镜像 SHALL 通过多阶段构建包含后端 JAR（Spring Boot），不含 Nginx 和前端。

#### Scenario: 成功构建 Docker 镜像

- **WHEN** 执行 `docker build -t kfile-v2_i:v2 .`
- **THEN** 镜像中包含 `/app/app.jar`，暴露 8081 端口

#### Scenario: 容器启动后后端 API 可用

- **WHEN** 容器启动并完成健康检查（`curl -f http://localhost:8081/actuator/health`）
- **THEN** 访问 `http://127.0.0.1:8081/api/admin/auth/me` 返回后端 JSON 响应

### Requirement: 服务器 Nginx 反向代理配置

Nginx SHALL 将 `/api/`、`/actuator/`、`/file/` 路径的请求代理到 Spring Boot 后端（`127.0.0.1:8081`），其他路径服务前端静态文件。

#### Scenario: API 请求代理到后端

- **WHEN** 请求路径以 `/api/`、`/actuator/` 或 `/file/` 开头
- **THEN** Nginx 将请求代理到 `http://127.0.0.1:8081`，并传递 Host、X-Real-IP、X-Forwarded-For 等头部

#### Scenario: 前端路由 fallback

- **WHEN** 请求路径不以 `/api/`、`/actuator/` 或 `/file/` 开头
- **THEN** Nginx 尝试返回 `/var/www/k-File/` 下对应静态文件，若文件不存在则返回 `index.html`（支持 Vue Router history 模式）

#### Scenario: 静态资源缓存

- **WHEN** 请求的文件路径包含 hash 指纹（如 `/assets/index-abc123.js`）
- **THEN** Nginx 设置长期缓存头（`Cache-Control: public, immutable, max-age=1y`）

### Requirement: 移除 /kfile 子路径

应用 SHALL 在根路径 `/` 下部署，不再使用 `/kfile` 前缀。

#### Scenario: 前端 base path

- **WHEN** 前端应用构建
- **THEN** Vite 的 base 配置为 `'/'`，所有资源路径不含 `/kfile` 前缀

#### Scenario: 后端 API 路径

- **WHEN** 后端应用启动
- **THEN** `app.base-path` 配置被移除或为空，所有 API 和重定向路径不含 `/kfile` 前缀

### Requirement: 独立子域名 CORS 配置

后端 SHALL 允许来自部署子域名（如 `https://file.ksite.xin`）的跨域请求。

#### Scenario: CORS 允许子域名

- **WHEN** 前端从 `https://file.ksite.xin` 发起 API 请求
- **THEN** 后端返回正确的 CORS 响应头，允许该来源的请求

### Requirement: 前端环境配置更新

前端 `.env.production` SHALL 移除 `VITE_KSITE_BASE` 和 `VITE_API_BASE` 中的 `/kfile` 前缀。

#### Scenario: 生产环境 API 基路径

- **WHEN** 前端在生产模式下构建
- **THEN** API 请求路径为 `/api/...`，不含 `/kfile` 前缀

### Requirement: Jenkins 部署脚本

`deploy.sh` SHALL 提供一键部署能力：后端 Docker 构建与运行（容器名 KFile-v2）、Nginx 重载、旧镜像清理。前端部署由独立步骤处理。

#### Scenario: 一键部署

- **WHEN** 执行 `bash deploy.sh`（Jenkins 注入环境变量后）
- **THEN** 旧容器停止，新镜像构建（标签 `kfile-v2_i:<BUILD_NUMBER>`），新容器 `KFile-v2` 在 8081 端口启动，Nginx 配置重载

#### Scenario: OSS 凭据必需检查

- **WHEN** `OSS_AK` 或 `OSS_SK` 环境变量未设置
- **THEN** `deploy.sh` 输出错误并退出，不执行构建

### Requirement: Safari 剪贴板兼容

前端 SHALL 在 Safari 浏览器中正确处理剪贴板复制操作。

#### Scenario: Safari 异步上下文复制（预签名链接）

- **WHEN** Safari 用户获取预签名链接后需要复制
- **THEN** 系统展示链接输入框和"复制"按钮，用户点击"复制"按钮后（同步上下文）完成复制

#### Scenario: 非 Safari 浏览器直接复制

- **WHEN** Chrome 等浏览器用户获取预签名链接后
- **THEN** 链接直接复制到剪贴板，弹窗关闭

#### Scenario: Safari 同步上下文复制（用户页链接等）

- **WHEN** Safari 用户在同步上下文中点击"复制"按钮（如项目用户页链接）
- **THEN** 优先使用 `navigator.clipboard.writeText`，失败回退 `execCommand('copy')`

### Requirement: 分享短链接 API

后端 SHALL 提供分享短链接的创建和查询 API，使用 Base62 编码短码替代 Base64 编码长 URL。

#### Scenario: 创建分享短链接

- **WHEN** 管理员调用 `POST /api/admin/projects/{projectId}/share`，传入 filename、entries、expireSeconds
- **THEN** 后端生成 UUID→Base62 短码，存储到 `share_link` 表，返回 `{ code, expireAt }`

#### Scenario: 通过短码查询分享数据

- **WHEN** 访问 `GET /api/share/{code}`
- **THEN** 后端返回存储的数据（filename、entries），并附加 `expireAt`（epoch 毫秒）

#### Scenario: 短码不存在

- **WHEN** 访问 `GET /api/share/{code}` 且 code 不存在
- **THEN** 返回 404 Not Found

#### Scenario: 短码已过期

- **WHEN** 访问 `GET /api/share/{code}` 且 `expire_at < NOW()`
- **THEN** 返回 410 Gone

#### Scenario: SecurityConfig 放行分享查询

- **WHEN** 未认证用户访问 `GET /api/share/*`
- **THEN** 不需要登录，直接返回分享数据

### Requirement: 分享短链接前端集成

前端 SHALL 使用短链接 API 替代 Base64 编码 URL 进行分享。

#### Scenario: AdminSubmissions archive 模式生成短链接

- **WHEN** 管理员在 archive 模式下点击分享
- **THEN** 调用 `adminCreateShare()` API，获取短码，生成 `/share?s={code}` URL

#### Scenario: ShareDownload 支持短码查询

- **WHEN** 用户访问 `/share?s={code}`
- **THEN** 前端调用 `getShare(code)` API 获取分享数据，展示文件列表和下载按钮

#### Scenario: 兼容旧 Base64 格式

- **WHEN** 用户访问 `/share?d=<base64>`（旧格式）
- **THEN** 前端直接解码 Base64 数据，保持向后兼容

### Requirement: 分享页错误状态 UI

分享页 SHALL 根据错误类型展示不同的标题、图标和背景。

#### Scenario: 链接已过期

- **WHEN** 分享链接已过期（410 响应）
- **THEN** 全屏居中显示橙色时钟图标，标题"链接已过期"

#### Scenario: 链接不存在

- **WHEN** 分享链接不存在（404 响应）
- **THEN** 全屏居中显示灰色禁止图标，标题"链接不存在"

#### Scenario: 其他错误

- **WHEN** 分享数据无效或加载失败
- **THEN** 全屏居中显示红色叉号图标，标题"无法加载分享内容"

### Requirement: 过期分享链接定时清理

后端 SHALL 每天定时清理过期的分享链接记录。

#### Scenario: 每天 02:00 自动清理

- **WHEN** 时间到达 02:00（Asia/Shanghai）
- **THEN** `ShareCleanupTask` 执行 `DELETE FROM share_link WHERE expire_at IS NOT NULL AND expire_at < NOW()`

### Requirement: Jenkinsfile CI/CD

Jenkinsfile SHALL 定义完整的 CI/CD 流程，GitHub webhook 触发，仅 main 分支自动部署。

#### Scenario: GitHub 推送触发部署

- **WHEN** GitHub main 分支收到 push 事件
- **THEN** Jenkins 触发流水线，执行 `deploy.sh`

#### Scenario: 非 main 分支不触发

- **WHEN** GitHub 非 main 分支收到 push 事件
- **THEN** Jenkins 流水线跳过 Deploy stage

#### Scenario: 环境变量凭据注入

- **WHEN** Deploy stage 执行
- **THEN** Jenkins environment 中的 OSS_AK、OSS_SK、SPRING_DATASOURCE_USERNAME、SPRING_DATASOURCE_PASSWORD 自动注入为环境变量，`deploy.sh` 直接读取

### Requirement: 凭据环境变量化

所有密钥 SHALL 通过环境变量传入，不出现在代码或 Git 历史中。

#### Scenario: 数据库凭据

- **WHEN** 容器启动
- **THEN** `SPRING_DATASOURCE_USERNAME` 和 `SPRING_DATASOURCE_PASSWORD` 通过 `-e` 环境变量传入，覆盖 `application-prod.yml` 中的 `${...}` 占位符

#### Scenario: OSS 凭据

- **WHEN** 容器启动
- **THEN** `OSS_AK` 和 `OSS_SK` 通过 `-e` 环境变量传入，覆盖 `application.yml` 中的 `${...}` 占位符

#### Scenario: 凭据缺失时启动失败

- **WHEN** 必需的环境变量未设置（如 `SPRING_DATASOURCE_PASSWORD` 为空）
- **THEN** Spring Boot 启动失败，日志显示连接被拒绝

### Requirement: 构建上下文清理

`.dockerignore` SHALL 排除非构建相关文件，减小构建上下文体积。

#### Scenario: 排除的文件不进入构建上下文

- **WHEN** 执行 `docker build`
- **THEN** `deploy.sh`、`openspec/`、`.opencode/` 不包含在构建上下文中
