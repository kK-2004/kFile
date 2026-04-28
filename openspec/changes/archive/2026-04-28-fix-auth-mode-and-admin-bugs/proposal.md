## Why

多个前端和后端 bug 影响系统的基本可用性：管理员登录后无法在用户端查看项目列表、SUPER 删除 ADMIN 用户报事务错误、ADMIN 用户管理项目时被全局文件类型白名单错误拦截。这些问题源自未完成的 `mode === 'local'` 设计残留和缺少事务注解。

## What Changes

- 移除前端 `auth.user.mode === 'local'` 死代码检查，改为 `!!auth.user`，修复用户端项目列表始终显示"仅管理员可查看"的问题
- 给 `AdminUserController.deleteUser()` 添加 `@Transactional` 注解，修复 SUPER 删除 ADMIN 用户时的 `TransactionRequiredException`
- 将 `ProjectService` 中 `isAdmin` 判断从仅检查 `ROLE_SUPER` 扩展为同时包含 `ROLE_ADMIN`，修复 ADMIN 用户编辑项目时被全局文件类型白名单拦截的问题

## Capabilities

### New Capabilities

_(无新增能力)_

### Modified Capabilities

- `auth-mode-check`: 修正前端认证状态检查逻辑，移除未实现的 `mode` 字段依赖
- `admin-user-management`: 修复删除用户的事务支持
- `project-file-type-validation`: 修正 ADMIN 角色在项目文件类型校验中的权限判断

## Impact

- **前端**: `UserProjects.vue`、`AdminProjectForm.vue` — 认证状态检查逻辑变更
- **后端**: `AdminUserController.java` — 删除用户方法增加事务注解
- **后端**: `ProjectService.java` — 项目创建/更新时的角色判断逻辑变更，影响 `create()` 和 `update()` 方法
- **API**: 无接口变更，无破坏性变更
