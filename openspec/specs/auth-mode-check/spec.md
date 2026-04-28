### Requirement: 用户端项目列表可见性判断
系统 SHALL 仅依据管理员是否已登录（`!!auth.user`）来决定用户端项目列表的可见性。已登录管理员 SHALL 能看到项目列表并操作提交按钮。未登录用户 SHALL 看到提示信息。

#### Scenario: 已登录管理员访问用户端项目列表
- **WHEN** 管理员已通过 `/api/admin/auth/me` 获取到有效的用户信息（`auth.user` 存在）
- **THEN** 用户端项目列表页 SHALL 显示项目表格而非"仅管理员可查看"提示
- **THEN** 系统 SHALL 调用 `GET /api/admin/projects` 获取项目列表（后端自动按角色过滤）

#### Scenario: 未登录用户访问用户端项目列表
- **WHEN** 用户未登录（`auth.user` 为 null）
- **THEN** 用户端项目列表页 SHALL 显示"仅管理员可查看项目列表"提示
- **THEN** 项目表格和提交按钮 SHALL 不显示

### Requirement: AdminProjectForm 角色判断
系统 SHALL 仅依据 `auth.user.role` 字段判断用户角色权限，不依赖 `mode` 字段。

#### Scenario: ADMIN 用户编辑项目表单
- **WHEN** 角色为 ADMIN 的用户打开项目编辑表单
- **THEN** `isSuperUser` computed 属性 SHALL 返回 `true`（因为 `role === 'ADMIN'`）
- **THEN** 文件扩展名白名单选择器 SHALL 可编辑
