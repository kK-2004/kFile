## MODIFIED Requirements

### Requirement: SUPER 删除 ADMIN 用户
系统 SHALL 在删除 ADMIN 用户时正确执行事务操作，先删除该用户的所有项目权限记录，再删除用户本身。

#### Scenario: SUPER 成功删除 ADMIN 用户
- **WHEN** SUPER 用户发起 `DELETE /api/admin/users/{userId}` 请求，目标用户角色为 ADMIN
- **THEN** 系统 SHALL 在同一事务中删除 `project_permissions` 表中该用户的所有权限记录
- **THEN** 系统 SHALL 删除 `admin_users` 表中的用户记录
- **THEN** 返回 HTTP 200

#### Scenario: 尝试删除 SUPER 用户
- **WHEN** SUPER 用户发起 `DELETE /api/admin/users/{userId}` 请求，目标用户角色为 SUPER
- **THEN** 系统 SHALL 返回 HTTP 400，消息为"不能删除SUPER账号"
- **THEN** 不删除任何数据
