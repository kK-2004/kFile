## Why

k-File 当前依赖主站 (k-Site) 的 SSO 认证，导致用户必须先在主站注册/登录才能使用 k-File，增加了使用门槛和部署耦合。将 k-File 独立部署为 `file.域名` 子域名，去除 SSO 依赖，使其成为一个自包含的应用，便于独立运维和扩展。

## What Changes

- **BREAKING**: 移除所有 OAuth2/SSO 认证逻辑，仅保留本地用户名密码登录（session cookie 认证）
- **BREAKING**: 移除前端"从主站登录（k-Site）"按钮及相关 SSO 流程代码
- **BREAKING**: 移除后端 OAuth2 Resource Server、JWT 解码、断路器 JWT 解码器等 SSO 基础设施
- **BREAKING**: 移除跨站用户查询服务（SiteUserLookupService）
- **BREAKING**: 移除 UserAccount 实体及其关联的 SSO 用户映射逻辑
- 新增 Docker 部署配置，后端打包为独立容器（仅 Spring Boot，端口 8081），容器名/镜像名为 `KFile-v2` / `kfile-v2_i`
- 新增服务器 Nginx 配置，前端 dist 部署到 `/var/www/k-File/`，Nginx 反代 API 到容器
- 新增 Jenkins 部署脚本 `deploy.sh`，一键完成后端容器构建运行 + Nginx 重载 + 旧镜像清理
- 新增 `Jenkinsfile`，GitHub webhook 触发 CI/CD，仅 main 分支自动部署
- 新增分享短链接系统：后端 Base62 编码生成短码、ShareLink 实体存储、ShareController API、每天 02:00 定时清理过期记录
- 前端分享流程改用短链接 API（`/share?s={code}`），替代 Base64 编码 URL
- 修复分享页过期/错误状态 UI（全屏居中、标题和图标跟随错误类型）
- 修复 Safari 浏览器剪贴板复制兼容性问题（异步上下文复制失败）
- 移除 `app.base-path: /kfile` 配置，应用改为根路径部署
- 所有凭据（DB 密码、OSS AK/SK）改为环境变量占位符，通过容器 `-e` 注入
- Git 历史中的泄露密钥已清理（git-filter-repo 替换）
- 新增 `.dockerignore`（排除 deploy.sh、openspec/、.opencode/），`.gitignore` 排除 `.opencode/`

## Capabilities

### New Capabilities

- `standalone-docker-deploy`: Docker 镜像构建（纯后端）+ 服务器 Nginx 配置 + Jenkins 部署脚本，支持独立子域名部署
- `clipboard-compat`: Safari 剪贴板兼容方案，异步上下文使用两步复制流程
- `share-short-link`: 分享短链接系统，Base62 编码短码 + 数据库存储 + 定时过期清理，替代 Base64 编码长 URL
- `jenkins-cicd`: Jenkinsfile CI/CD 流程，GitHub webhook 触发，环境变量凭据注入
- `credential-hardening`: 凭据安全化，环境变量占位符 + Git 历史清理 + .dockerignore

### Modified Capabilities

（无现有 specs）

## Impact

- **后端代码**: SecurityConfig 大幅简化，移除 OAuth2 Resource Server 配置；移除 jwt/、SsoStatusService、SiteUserLookupService、AuthController、AdminUserManageController、ProjectAssignmentController 等；移除 UserAccount 实体/repo；清理 ProjectService、ProjectController、AdminProjectController、AdminPermissionService、AuditLogUtil、AliOssService 中的 SSO 引用；新增 Base62Util、ShareLink、ShareController、ShareCleanupTask
- **前端代码**: AdminLogin.vue 移除 SSO 登录按钮和 accessToken 处理；auth store 简化为仅本地认证；api 层移除 Bearer token 逻辑，新增 adminCreateShare()/getShare()；router 移除 SSO 相关守卫；新增 `utils/clipboard.js` 统一剪贴板工具；AdminSubmissions.vue archive 模式改用短链接 API；ShareDownload.vue 支持 ?s= 短码 + 过期 UI 重构
- **配置文件**: application-prod.yml 移除 SSO 配置，DB 凭据改为环境变量占位符，端口改为 8081；application.yml OSS 凭据为环境变量占位符；vite.config.js 移除 base: '/kfile/'；.env.production 移除 VITE_KSITE_BASE
- **Docker**: Dockerfile 简化为纯后端镜像（无 Nginx），暴露 8081 端口；容器名 KFile-v2；deploy.sh 接受 Jenkins 环境变量
- **Nginx**: 新增 `nginx.conf` 服务器配置，前端静态文件由服务器 Nginx 直接服务
- **CI/CD**: 新增 Jenkinsfile（GitHub webhook + 仅 main 分支 + 凭据注入）
- **API**: 移除 `GET /api/sso/status` 和 `GET /api/auth/me`；新增 `POST /api/admin/projects/{id}/share` 和 `GET /api/share/{code}`；SecurityConfig 放行 `GET /api/share/*`
- **依赖**: pom.xml 移除 spring-boot-starter-oauth2-resource-server
