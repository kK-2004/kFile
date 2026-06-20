## 1. 依赖与配置

- [ ] 1.1 `pom.xml` 引入 `io.minio:minio:8.5.17`（与 drug-store 一致），运行 `mvn dependency:tree` 校验与 `aliyun-sdk-oss`/okhttp/okio 传递依赖无冲突，必要时用 `<exclusions>` 或显式版本锁定
- [ ] 1.2 新增 `com.kk.config.MinioProperties`（`@ConfigurationProperties(prefix="minio")`）：`enabled / endpoint / accessKey / secretKey / bucket / prefix / presignedDirect`
- [ ] 1.3 `application.yml` 增加 `minio` 段（默认 `enabled: false`，值走 `${MINIO_*}` 环境变量），并在 `application-dev.yml` / `application-prod.yml` 留占位
- [ ] 1.4 `deploy.sh` / `Dockerfile` / `docker-compose.yml` 补充 `MINIO_ENDPOINT / MINIO_ACCESS_KEY / MINIO_SECRET_KEY / MINIO_BUCKET / MINIO_ENABLED` 环境变量；`docs/` 增加可选 MinIO 容器与配置说明

## 2. 后端：MinIO 客户端与存储服务

- [ ] 2.1 新增 `com.kk.storage.config.MinioConfig`：暴露 `MinioClient` Bean，`@ConditionalOnProperty(value="minio.enabled", havingValue="true")`，启动时对配置 bucket 做 `ensureBucket`（参考 drug-store `MinioConfig`）
- [ ] 2.2 定义抽象接口 `com.kk.storage.StorageBrowserService` 与 `Entry` DTO（`name / isDir / size / lastModified / key`），方法：`list(prefix)` / `upload(prefix, file)` / `delete(key)` / `mkdir(prefix, name)` / `downloadUrl(key, download, expireSeconds)` / `stat(key)`
- [ ] 2.3 实现 `MinioStorageService implements StorageBrowserService`：基于 MinIO `listObjects`(delimiter `/`) / `putObject` / `removeObject` / `getPresignedObjectUrl` / `statObject`；list 过滤 `.keep` 占位归为目录；上传路径规则对齐 OSS（normalizePrefix + baseName 防穿越 + `yyyy/MM/dd/` 日期子目录）
- [ ] 2.4 复用工具：抽出 `baseName` / `normalizePrefix`（参考 `AliOssService` 同名私有方法）为共享静态工具或在两个实现内各自实现，确保语义一致；`mkdir` 通过上传 0 字节 `<prefix>/<name>/.keep` 实现
- [ ] 2.5 实现 `AliOssBrowserService implements StorageBrowserService`（OSS 数据源）：内部复用 `AliOssService` 现有 `OSSClient`；新增 `listObjectsV2` + delimiter `/` 的列表能力，过滤 `.keep` 占位；不改动 `OssService` / `AliOssService` 既有方法签名
- [ ] 2.6 新增 `StorageBrowserRegistry`（`@Component`）：按 `oss` / `minio` 字符串收集可用实现；`sources()` 返回当前已启用列表；`get(source)` 做分发与未知 source 校验

## 3. 后端：管理接口（SUPER）

- [ ] 3.1 新增 `com.kk.admin.controller.AdminFilesController`（`/api/admin/files`），全部 `@PreAuthorize("hasRole('SUPER')")`
- [ ] 3.2 `GET /api/admin/files/sources` → `[{id,label}]`（仅返回已启用；OSS 始终在，MinIO 仅 `enabled=true` 时）
- [ ] 3.3 `GET /api/admin/files/list?source=&prefix=` → `Entry[]`；prefix 解码（参考 `FileProxyController` 的 `+`→`%2B` 处理）
- [ ] 3.4 `POST /api/admin/files/upload?source=&prefix=`（multipart，字段 `file`）→ 返回 `{key, url}`，`url` 为对应代理路径（OSS→`/file/oss/`，MinIO→`/file/minio/`）
- [ ] 3.5 `POST /api/admin/files/mkdir` `{source, prefix, name}` → 占位对象 key；对 `name` 做防穿越校验
- [ ] 3.6 `DELETE /api/admin/files?source=&key=` → 删除单个对象（key 同样解码处理）
- [ ] 3.7 `GET /api/admin/files/download-url?source=&key=&download=&expireSeconds=` → 预签名直链或代理 URL

## 4. 后端：MinIO 下载代理与安全

- [ ] 4.1 扩展 `FileProxyController`（或新增对称 `MinioProxyController`）：`GET /file/minio/**`，逻辑与 `/file/oss/**` 对称——默认 302 到 MinIO 预签名直链，`?proxy=1` 流式代理，`?download=1` 设 `Content-Disposition: attachment`
- [ ] 4.2 `SecurityConfig` 增加 `.requestMatchers(HttpMethod.GET, "/file/minio/**").permitAll()`，与 `/file/oss/**` 一致
- [ ] 4.3 用 IntelliJ `build_project` 全量编译；本地启动验证：MinIO 未启用时 `sources` 仅返回 OSS、无 MinIO bean 装配报错

## 5. 前端：API 与页面

- [ ] 5.1 `frontend/src/api/index.js` 新增文件管理 API：`adminFileSources()` / `adminFileList(source, prefix)` / `adminFileUpload(source, prefix, file, config)` / `adminFileMkdir({source, prefix, name})` / `adminFileDelete(source, key)` / `adminFileDownloadUrl(source, key, {download, expireSeconds})`
- [ ] 5.2 新增 `frontend/src/views/admin/AdminFiles.vue`：顶部数据源下拉（仅显示 `sources` 返回项）；面包屑 + 当前目录列表（区分文件夹/文件图标、大小、修改时间）；操作按钮：新建文件夹、上传、下载、删除
- [ ] 5.3 列表交互：点击文件夹进入下级（更新 `prefix`），面包屑点击返回上级；删除前 `el-message-box` 二次确认显示完整 key
- [ ] 5.4 上传：调用 `adminFileUpload`，进度通过 axios `onUploadProgress` 展示；上传成功后刷新当前目录
- [ ] 5.5 下载：调用 `adminFileDownloadUrl` 获取链接并触发浏览器下载；图片/文本类可选项直接用 `/file/<source>/<key>` 内嵌预览
- [ ] 5.6 `frontend/src/router/index.js` 注册路由 `{ path: '/admin/files', component: AdminFiles }`
- [ ] 5.7 `frontend/src/App.vue` 在 `isSuper` 块下新增「文件管理」导航按钮，指向 `/admin/files`

## 6. 验证与回归

- [ ] 6.1 手动验证 SUPER 全流程：数据源切换 → 建文件夹 → 上传 → 列表 → 下载 → 删除，OSS 与 MinIO 各跑一遍
- [ ] 6.2 验证路径穿越防护：上传 `../evil` 文件名、`mkdir` 传入 `..`，确认 key 不逃逸
- [ ] 6.3 验证 `/file/minio/**` 默认 302、`?proxy=1` 流式、`?download=1` 强制下载；未登录 GET 可访问
- [ ] 6.4 验证权限：`ADMIN` 调用 `/api/admin/files/**` 返回 403；MinIO 禁用时前端无 MinIO 选项、后端无装配错误
- [ ] 6.5 回归现有 OSS 链路：项目提交、归档任务、MCP 工具、`/file/oss/**` 下载行为无变化
