## ADDED Requirements

### Requirement: SDK 按 fileId 批量删除文件

Java SDK SHALL 提供 `deleteFiles(List<Long> fileIds)`，调用开放 API 的批量删除端点，自动携带客户端配置的 `Authorization: Bearer <appToken>`，并返回含 `deletedFiles` 与 `failedObjects` 的结果。SDK SHALL 沿用现有 `ContentCenterException` 处理非 2xx 响应。

#### Scenario: 批量删除成功
- **WHEN** 调用 `client.deleteFiles(List.of(id1, id2))` 且两个 ID 均为当前应用已完成上传的文件
- **THEN** SDK 向 `/api/open/files/batch-delete` 发送 `{ "fileIds": [id1, id2] }` 与 Bearer token，并返回服务端的删除数量和对象清理失败数量

#### Scenario: 删除单个文件
- **WHEN** 调用 `client.deleteFiles(List.of(id1))`
- **THEN** SDK 按相同批量接口删除单个文件并返回结果

#### Scenario: 服务端拒绝删除
- **WHEN** 服务端因 token 失效、无权限、无效 ID 或文件仍在上传而返回非 2xx
- **THEN** SDK 抛出带 HTTP 状态和服务端消息的 `ContentCenterException`
