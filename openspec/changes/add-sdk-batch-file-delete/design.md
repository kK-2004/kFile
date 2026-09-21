## Context

SDK 上传返回 `fileId`，开放 API 目前提供上传和下载链接，但没有文件删除端点。`stored_file` 的 `open_app_id` 记录开放应用归属；管理端现有删除流程会清理对象、关联上传记录与文件节点，对象删除失败时继续删除数据库记录并计数。开放 API 已由独立的 appToken 安全链鉴权。

## Goals / Non-Goals

**Goals:**

- 让应用用一次 SDK 调用按多个 `fileId` 删除自己已完成上传的文件。
- 在执行任何删除前校验整个批次，防止混入其他应用、管理员或不存在的文件时产生部分删除。
- 沿用既有对象清理的尽力而为语义，并明确返回结果。

**Non-Goals:**

- 删除文件夹、递归删除或自动清理空目录。
- 取消未完成的简单上传或正在进行的分片上传。
- 按 `storageKey` 删除或改变既有管理端删除权限。

## Decisions

### 批量 HTTP 契约

新增 `POST /api/open/files/batch-delete`，JSON 请求体为 `{ "fileIds": [1, 2] }`，成功返回 `200 { "deletedFiles": 2, "failedObjects": 0 }`。使用 POST 承载批量 JSON，避免 DELETE 请求体在客户端和网关中的兼容问题。一次允许 1–100 个互不重复的正数 ID；空列表、null、重复、非正数或超过上限返回 400。SDK 暴露 `deleteFiles(List<Long> fileIds)` 与 `DeleteFilesResult(deletedFiles, failedObjects)`，并复用现有 Bearer 头和异常解析。只提供批量方法；单个文件可传单元素列表。

### 身份、归属与预校验

控制器沿用 `currentApp(auth)`，由现有 `/api/open/**` 安全链校验 appToken；缺失、无效、已轮换或禁用的 token 返回 401。服务层在一个事务中按 ID 顺序锁定并读取整个批次，逐一确认记录存在、`type=FILE`、`openAppId` 与当前应用相同、`status=UPLOADED`。不存在、文件夹或其他主体的 ID 统一返回 404 且不透露存在性；本应用仍在上传中的文件返回 409。任何预校验失败都不调用对象存储删除，也不删除数据库记录。锁定顺序固定为 ID 升序，减少并发批次死锁。不能把通用管理端 `delete(id, null)` 当作开放接口的权限校验。

### 删除与失败结果

预校验通过后复用 `StoredFileService` 的文件删除核心逻辑：按文件记录上的 `storageSource` 与 `storageKey` 删除对象，再清理关联上传记录与 `stored_file` 行；限定只处理已锁定的 FILE 节点，不进入文件夹递归分支。对象删除抛错时记录告警并增加 `failedObjects`，继续删除该文件的数据库记录，与管理端语义一致。`deletedFiles` 是删除的文件记录数，`failedObjects` 是对象删除未成功的数量。批次校验的全有或全无仅保证删除开始前的参数、归属与状态检查；外部对象存储操作不能与数据库事务原子提交。

### 验证

服务端测试覆盖同应用多文件、跨应用与管理员文件混入、缺失/文件夹、未完成上传、边界输入、对象删除失败及无副作用预校验。控制器鉴权测试覆盖无效 token。SDK 的本地 HTTP stub 测试验证请求路径、JSON、Bearer 头、响应解析与非 2xx 异常；README 补充调用示例和删除结果语义。

## Risks / Trade-offs

- [对象删除成功而数据库事务随后回滚] → 对象和记录可能短暂不一致；沿用现有清理模型，记录错误并通过运维排查。实现时尽量避免对象删除后的可预见数据库异常。
- [对象删除失败但数据库记录已删除] → 返回 `failedObjects` 并记录带 source/key 的告警，便于后续对象存储清理；调用方不应把 `deletedFiles` 解读为全部物理对象已删除。
- [大批量持锁时间长] → 限制每次最多 100 个 ID，并按升序锁定。

## Migration Plan

无数据库迁移。先部署含新增端点的服务端，再发布并升级 SDK；旧 SDK 和已有接口保持兼容。回滚服务端版本即可移除新端点，已删除文件无法通过回滚恢复。

## Open Questions

无。
