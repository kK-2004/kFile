## 1. 服务端批量删除能力

- [x] 1.1 在 `StoredFileRepository` 增加按升序锁定一组文件 ID 的查询，并为开放应用删除实现 1–100 个互异正数 ID 的参数校验及整批记录读取。
- [x] 1.2 在服务层完成整批预校验：只接受当前 `openAppId` 下 `type=FILE`、`status=UPLOADED` 的节点；将缺失/非本应用/文件夹映射为统一 404，将本应用未完成上传映射为 409；验证失败时不产生删除副作用。
- [x] 1.3 抽取或复用现有文件节点清理逻辑，在通过预校验后删除对象、关联上传记录和 DB 文件节点，统计 `deletedFiles`/`failedObjects`；保持管理端原有删除行为。
- [x] 1.4 在 `OpenFileController` 增加 `POST /api/open/files/batch-delete`，使用当前 appToken 身份调用服务层，配置与开放 API 一致的限流并返回约定 JSON。

## 2. Java SDK 与文档

- [x] 2.1 在 `ContentCenterClient` 增加 `deleteFiles(List<Long>)` 和 `DeleteFilesResult`，复用 Bearer 注入及非 2xx 错误解析，支持单元素列表。
- [x] 2.2 更新 `sdk/README.md` 的批量删除示例、请求限制、返回计数与对象清理失败语义；按仓库发布约定更新 SDK 版本说明。

## 3. 验证

- [x] 3.1 为服务层补充成功批量删除、混入跨应用/管理员文件、缺失/文件夹、上传中、非法列表及对象删除失败测试，断言预校验失败没有对象和 DB 删除调用。
- [x] 3.2 为开放端点补充 appToken 缺失/失效、当前应用身份传递及 HTTP 状态测试。
- [x] 3.3 为 SDK 补充本地 HTTP stub 测试，核对路径、请求 JSON、Bearer 头、响应计数及非 2xx 异常。
- [x] 3.4 运行服务端相关测试、`mvn -f sdk/pom.xml verify` 和 `openspec validate add-sdk-batch-file-delete --strict`，修复发现的问题。
