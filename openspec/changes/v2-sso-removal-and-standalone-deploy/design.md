## Context

k-File 是一个基于 Spring Boot 3 + Vue 3 的文件收集应用，当前作为 k-Site 的子应用部署在 `ksite.xin/kfile/` 路径下。认证采用双模式：SSO（OAuth2 Resource Server 接收 k-Site 签发的 JWT）和本地管理员登录（session cookie）。前端通过 Vite 构建，后端打包为可执行 JAR，当前 Dockerfile 仅包含后端，前端静态资源由 Spring Boot 直接服务。

## Goals / Non-Goals

**Goals:**

- 完全移除 SSO/OAuth2 认证依赖，简化为仅本地用户名密码认证
- 后端打包为 Docker 容器（仅 Spring Boot），前端 dist 由服务器 Nginx 直接服务
- 使用独立子域名（如 `file.ksite.xin`）部署，移除 `/kfile` 子路径
- 保持现有本地管理员认证体系（AdminUser + session cookie）不变
- Safari 浏览器剪贴板复制兼容

**Non-Goals:**

- 不重新设计用户权限体系，保留现有 AdminUser 角色（KF_ADMIN/KF_SUPER）
- 不修改业务功能（项目管理、提交、分享等）
- 不做数据库迁移脚本——UserAccount 表的清理可在后续手动处理

## Decisions

### 1. 认证架构：移除 OAuth2，仅保留 session 认证

**选择**: 删除所有 OAuth2 Resource Server 配置和相关代码，仅使用 Spring Security 的 form-based / session 认证。

**理由**: SSO 是唯一的 OAuth2 使用场景。移除后不再需要 JWT 解码、断路器、跨站用户查询等复杂机制。

**备选方案**: 保留 JWT 认证作为可选方案 → 增加不必要的复杂度，违背独立部署的目标。

### 2. Docker 架构：纯后端容器，服务器 Nginx 服务前端

**选择**: Docker 容器仅运行 Spring Boot 后端（暴露 8081 端口），前端 dist 通过 `rsync` 部署到服务器 `/var/www/k-File/`，由服务器已有 Nginx 直接服务静态文件并反代 API。

**理由**: 服务器已有 Nginx，无需在容器内重复安装。职责分离更清晰：Nginx 处理静态文件和 TLS，容器专注业务逻辑。容器镜像更小、构建更快。

**备选方案**: 容器内打包 Nginx + Spring Boot → 增加镜像体积，与服务器已有 Nginx 冲突，违背用户"不要在容器内打包 Nginx"的要求。

### 3. Nginx 配置：服务器端根路径部署

**选择**: 服务器 Nginx 监听 80 端口，`root /var/www/k-File/` 服务前端静态文件，`/api/`、`/actuator/`、`/file/` 代理到 `127.0.0.1:8081`。移除所有 `/kfile` 前缀。

**理由**: 使用独立子域名后不再需要子路径。前端 base path 改为 `/`，后端 `app.base-path` 移除。

### 4. 前端认证简化

**选择**: 移除 Pinia auth store 中的 `mode: 'site'` 分支和 Bearer token 逻辑，统一使用 cookie session。

**理由**: 单一认证模式大幅简化代码，避免 dual-auth 的复杂分支逻辑。

### 5. Safari 剪贴板兼容

**选择**: 新增 `utils/clipboard.js` 统一剪贴板工具。非 Safari 浏览器直接使用 `navigator.clipboard.writeText`；Safari 在异步上下文中（如预签名链接获取后）改为两步流程：先获取链接展示在输入框中，用户手动点击"复制"按钮（同步上下文）完成复制。

**理由**: Safari 要求剪贴板操作必须在用户直接触发的事件同步回调中执行，`await` 网络请求后 `navigator.clipboard` 和 `execCommand('copy')` 均不可靠。两步流程确保复制发生在用户点击的同步上下文中。

**备选方案**: 所有浏览器统一使用两步流程 → 增加 Chrome 等浏览器用户的不必要操作步骤。

### 6. 部署自动化

**选择**: 新增 `deploy.sh` Jenkins 部署脚本，整合前端 rsync、后端 Docker 构建运行、Nginx 重载、旧镜像清理。

**理由**: 一键部署，减少人工操作遗漏。

## Risks / Trade-offs

- **[现有 SSO 用户]** 依赖 SSO 登录的用户将无法继续登录 → 这些用户的 UserAccount 数据不受影响，但需手动创建对应的 AdminUser 或重新注册
- **[CORS 配置]** 需将 CORS 从 `ksite.xin` 更新为 `file.ksite.xin` → 在 application-prod.yml 中更新
- **[前端部署]** 前端 dist 需单独部署到服务器 Nginx 目录，与后端容器解耦 → 通过 deploy.sh 自动化

## Open Questions

- `file.ksite.xin` 是否为最终域名？还是其他 `file.xxx` 域名？（当前按 `file.ksite.xin` 设计，可在部署时通过环境变量调整）
- 是否需要保留 UserAccount 表的数据，还是可以直接清理？
