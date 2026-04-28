## MODIFIED Requirements

### Requirement: 项目文件类型全局白名单约束范围
系统 SHALL 仅对非管理员用户（无 SUPER 或 ADMIN 角色）应用全局文件类型白名单约束。SUPER 和 ADMIN 用户在创建和更新项目时 SHALL 不受全局 `USER_ALLOWED_FILE_TYPES` 配置的限制。

#### Scenario: ADMIN 用户创建项目时文件类型不受全局白名单限制
- **WHEN** ADMIN 角色用户通过 `POST /api/projects` 创建项目，且 `allowedFileTypes` 包含不在全局白名单中的扩展名
- **THEN** 系统 SHALL 允许创建成功，不校验全局白名单

#### Scenario: ADMIN 用户更新项目时文件类型不受全局白名单限制
- **WHEN** ADMIN 角色用户通过 `PUT /api/projects/{id}` 更新已分配给自己的项目
- **THEN** 系统 SHALL 跳过全局文件类型白名单校验
- **THEN** 即使项目的 `allowedFileTypes` 不在全局白名单中，更新 SHALL 成功

#### Scenario: 非 ADMIN 用户创建项目时文件类型受全局白名单限制
- **WHEN** 既非 SUPER 也非 ADMIN 的用户创建项目，且 `allowedFileTypes` 包含不在全局白名单中的扩展名
- **THEN** 系统 SHALL 拒绝创建并返回错误提示，列出允许的扩展名
