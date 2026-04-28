## 1. 后端：移除 SSO/OAuth2 认证

- [x] 1.1 移除 pom.xml 中 `spring-boot-starter-oauth2-resource-server` 依赖
- [x] 1.2 删除 `security/jwt/CircuitBreakerJwtDecoder.java`
- [x] 1.3 删除 `security/SiteJwtAuthConverter.java`
- [x] 1.4 删除 `security/JwtAudienceValidator.java`
- [x] 1.5 删除 `security/service/SsoStatusService.java`
- [x] 1.6 删除 `security/service/SiteUserLookupService.java`
- [x] 1.7 删除 `security/service/UserAccountService.java`
- [x] 1.8 删除 `security/controller/SsoStatusController.java`
- [x] 1.9 删除 `security/controller/AuthController.java`
- [x] 1.10 删除 `security/entity/UserAccount.java`
- [x] 1.11 删除 `security/repo/UserAccountRepository.java`
- [x] 1.12 简化 `SecurityConfig.java`：移除 OAuth2 Resource Server 配置、CircuitBreakerJwtDecoder、JwtAudienceValidator、SiteJwtAuthConverter；仅保留 session 认证
- [x] 1.13 简化 `RestAuthenticationEntryPoint.java`：移除 `app.base-path` 前缀逻辑，重定向到 `/admin/login`
- [x] 1.14 清理引用 SSO 类的关联文件：删除 `AdminUserManageController`、`ProjectAssignmentController`；清理 `ProjectService`、`ProjectController`、`AdminProjectController`、`AdminPermissionService`、`AuditLogUtil`、`AliOssService` 中的 SSO 引用

## 2. 后端：配置文件清理

- [x] 2.1 `application-prod.yml`：移除 `spring.security.oauth2` 段、`app.sso` 段、`app.base-path`、`site.base-url`；CORS 改为 `https://file.ksite.xin`；端口改为 8081
- [x] 2.2 `application-dev.yml`：移除 `app.sso`、`site.base-url`、`issuer-uri` 配置
- [x] 2.3 `application.yml`：清理与 SSO 相关的任何配置项

## 3. 前端：移除 SSO 认证逻辑

- [x] 3.1 `stores/auth.js`：移除 `mode: 'site'` 分支、`KSITE_ACCESS_TOKEN` 逻辑、Bearer token 相关代码，统一为 cookie session
- [x] 3.2 `api/index.js`：移除 Bearer token 注入逻辑（请求拦截器中的 `KSITE_ACCESS_TOKEN` 头部），仅依赖 cookie
- [x] 3.3 `api/index.js`：移除 `ssoStatus()` 和 `authMe()` 方法
- [x] 3.4 `router/index.js`：移除 URL 中 `accessToken` 参数的捕获和处理逻辑，base 改为 `/`
- [x] 3.5 `views/admin/AdminLogin.vue`：移除"从主站登录（k-Site）"按钮及 SSO 相关逻辑（accessToken 获取、ssoStatus 探测、重定向到 k-Site）
- [x] 3.6 `App.vue`：移除与 site auth 模式相关的 UI 显示逻辑

## 4. 前端：移除 /kfile 子路径

- [x] 4.1 `vite.config.js`：将 `base: '/kfile/'` 改为 `base: '/'`
- [x] 4.2 `.env.production`：移除 `VITE_KSITE_BASE`；将 `VITE_API_BASE` 改为 `/`
- [x] 4.3 `.env.development`：移除 `VITE_KSITE_BASE`

## 5. Docker：后端容器 + 服务器 Nginx

- [x] 5.1 创建 `nginx.conf`：服务器 Nginx 配置，`root /var/www/k-File/`，`/api/`、`/actuator/`、`/file/` 代理到 `127.0.0.1:8081`，前端 try_files fallback，静态资源长期缓存
- [x] 5.2 重写 `Dockerfile`：移除 Nginx 和前端构建阶段，仅保留 Maven 构建后端 JAR + 运行时 JDK 镜像，暴露 8081 端口
- [x] 5.3 `application-prod.yml` 端口改为 8081

## 6. 部署脚本

- [x] 6.1 创建 `deploy.sh`：Jenkins 部署脚本，Docker 镜像构建与容器运行（端口 8081）、Nginx 重载、旧镜像清理
- [x] 6.2 `deploy.sh` 容器名/镜像名改为 `KFile-v2` / `kfile-v2_i`
- [x] 6.3 `deploy.sh` 新增 OSS_AK/OSS_SK 必需变量检查与容器环境变量传递

## 7. Safari 剪贴板兼容修复

- [x] 7.1 新增 `utils/clipboard.js`：统一剪贴板工具，优先 `navigator.clipboard`，回退 `execCommand('copy')`；导出 `isSafari` 检测
- [x] 7.2 `AdminSubmissions.vue`：Safari 下预签名/分享链接改为两步流程（先获取链接展示输入框，用户手动点复制），其他浏览器直接复制
- [x] 7.3 所有使用 `navigator.clipboard.writeText` 的页面统一改用 `copyText()`：`AdminSubmissions.vue`、`AdminProjects.vue`、`AdminUsers.vue`、`Hero.vue`

## 8. 分享短链接（替代 Base64 编码 URL）

- [x] 8.1 后端：新增 `Base62Util`（UUID → Base62 短码）
- [x] 8.2 后端：新增 `ShareLink` 实体（code、data JSON、expireAt）
- [x] 8.3 后端：新增 `ShareLinkRepository`（findByCode + deleteExpiredBefore）
- [x] 8.4 后端：新增 `ShareController`（POST 创建 + GET 查询），SecurityConfig 放行 GET /api/share/*
- [x] 8.5 后端：`ShareController.getShare()` 返回数据中包含 `expireAt`（epoch 毫秒），前端可正确判断过期
- [x] 8.6 前端 `api/index.js`：新增 `adminCreateShare()` 和 `getShare()`
- [x] 8.7 前端 `AdminSubmissions.vue`：archive 模式改用 `adminCreateShare()` 生成短链接
- [x] 8.8 前端 `ShareDownload.vue`：支持 `?s=code` 短码查询，兼容旧 `?d=` base64 格式
- [x] 8.9 前端 `ShareDownload.vue`：过期/不存在/错误状态 UI 重构（全屏居中、标题和图标跟随错误类型变化）

## 9. 过期链接定时清理

- [x] 9.1 后端：新增 `ShareCleanupTask`（`@Scheduled(cron = "0 0 2 * * ?")`），每天 02:00 清理过期 share_link 记录
- [x] 9.2 `ShareLinkRepository.deleteExpiredBefore(Instant)` 批量删除方法

## 10. CI/CD：Jenkinsfile

- [x] 10.1 新增 `Jenkinsfile`：GitHub webhook 触发，仅 main 分支部署
- [x] 10.2 Jenkinsfile `environment` 注入凭据（OSS_AK、OSS_SK、SPRING_DATASOURCE_USERNAME、SPRING_DATASOURCE_PASSWORD）
- [x] 10.3 Deploy stage 调用 `deploy.sh`，环境变量直接传递

## 11. 配置安全化

- [x] 11.1 `application-prod.yml` 数据库凭据改为 `${SPRING_DATASOURCE_USERNAME}` / `${SPRING_DATASOURCE_PASSWORD}` 环境变量占位符
- [x] 11.2 `application.yml` OSS 凭据已为 `${OSS_AK}` / `${OSS_SK}` 环境变量占位符
- [x] 11.3 Git 历史清理：用 `git-filter-repo` 将历史中的阿里云 AK/SK 替换为环境变量占位符
- [x] 11.4 新增 `.dockerignore`（排除 deploy.sh、openspec/、.opencode/）
- [x] 11.5 `.gitignore` 排除 `.opencode/`

## 12. 验证

- [x] 12.1 后端编译通过（`mvn clean package -DskipTests`）
- [x] 12.2 前端构建通过（`npm run build`）
- [x] 12.3 Docker 镜像构建通过（`docker build .`）
- [x] 12.4 容器启动后后端 API 可正常访问（数据库连接正常）
- [x] 12.5 分享短链接端到端测试（创建 → 访问 → 过期 → 定时清理）
