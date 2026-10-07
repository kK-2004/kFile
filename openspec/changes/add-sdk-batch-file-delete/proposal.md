## Why

开放应用通过 SDK 上传文件后，目前只能获取下载链接，无法按上传返回的 `fileId` 清理不再需要的文件。业务系统需要自行请管理员删除，既不便于批量处理，也难以保证删除范围只限当前应用。

## What Changes

- 为开放 API 增加按 `fileId` 批量删除已完成上传文件的接口，并在 Java SDK 中提供对应方法。
- 每次删除均使用现有 appToken 鉴权；服务端逐个确认文件属于当前开放应用，仅允许删除文件，不接受文件夹或递归删除。
- 批量请求先校验全部 ID；任一 ID 无效、不可删除或不属于当前应用时，整个请求拒绝，不删除任何文件。
- 删除时清理对象存储、文件节点及关联上传记录，并向调用方返回删除数量和对象清理失败数量；对象清理失败沿用现有管理端的尽力而为语义。
- 补充 SDK 用法与服务端、SDK 测试。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `open-file-api`：增加受 appToken 与应用归属约束的批量文件删除端点及错误、结果语义。
- `content-sdk`：增加按 `fileId` 批量删除文件的客户端方法与返回结果。

## Impact

涉及 `OpenFileController`、`OpenFileService`、`ContentCenterClient`、SDK 文档及相关测试。新增 `/api/open/files/batch-delete` 端点；不改变现有上传、下载接口或数据库结构。
