## ADDED Requirements

### Requirement: 开放应用按 fileId 批量删除文件

系统 SHALL 提供 `POST /api/open/files/batch-delete`，请求体为 `{ "fileIds": [<long>, ...] }`，仅接受 1–100 个互不重复的正数 `fileId`。系统 MUST 使用 `/api/open/**` 的 appToken 鉴权，并在任何删除开始前验证全部 ID 对应的节点是当前应用上传且已完成上传的 `FILE`。成功时系统 SHALL 删除对应对象、关联上传记录和文件节点，并返回 `{ "deletedFiles": <number>, "failedObjects": <number> }`。系统 MUST NOT 因该接口删除文件夹或递归删除子项。

#### Scenario: 删除本应用的多个文件
- **WHEN** 已启用应用以有效 appToken 提交两个由该应用完成上传的文件 ID
- **THEN** 系统删除两个对象、关联上传记录和文件节点，并返回 `200`、`deletedFiles: 2`、`failedObjects: 0`

#### Scenario: 无效 appToken
- **WHEN** 请求缺少 appToken，或 appToken 无效、已轮换或所属应用已禁用
- **THEN** 系统返回 `401`，不删除任何文件

#### Scenario: 批次混入非本应用文件
- **WHEN** 一个批次同时包含本应用文件 ID 与其他应用或管理员上传的文件 ID
- **THEN** 系统返回 `404`，且不删除批次中的任何对象或文件记录，也不暴露非本应用文件是否存在

#### Scenario: 批次包含不存在或文件夹 ID
- **WHEN** 一个批次包含不存在的 ID 或文件夹 ID
- **THEN** 系统返回 `404`，且不删除批次中的任何对象或文件记录

#### Scenario: 批次包含未完成上传的文件
- **WHEN** 一个批次包含本应用仍处于 `UPLOADING` 状态的文件 ID
- **THEN** 系统返回 `409`，且不删除批次中的任何对象或文件记录

#### Scenario: 无效批量参数
- **WHEN** `fileIds` 为空、包含 null/重复/非正数 ID，或数量超过 100
- **THEN** 系统返回 `400`，不删除任何文件

#### Scenario: 对象清理失败
- **WHEN** 所有 ID 校验通过，但其中一个对象存储删除操作失败
- **THEN** 系统继续删除该文件的关联上传记录与文件节点，并在 `failedObjects` 中计入该失败
