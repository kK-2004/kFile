## MODIFIED Requirements

### Requirement: 管理员权限配置范围

系统 SHALL 在"管理员与权限设置"页为每个管理员提供权限配置能力，配置范围 SHALL 包括：(1) 可管理的项目（基于 `ProjectPermission` 的 user ↔ project 分配）；(2) 可使用的项目模板（基于 `ProjectTemplateAssignment` 的 user ↔ template 分配）。模板分配能力 SHALL 仅对 SUPER 角色可见，SUPER SHALL 能为任意 ADMIN 分配或撤销可用模板。

#### Scenario: SUPER 打开 ADMIN 权限配置抽屉看到两类配置
- **WHEN** SUPER 用户点击某 ADMIN 的"权限配置"
- **THEN** 抽屉 SHALL 同时显示"项目权限"列表与"可用模板"分配区
- **AND** 用户 SHALL 能分别保存这两类配置

#### Scenario: SUPER 分配可用模板给 ADMIN
- **WHEN** SUPER 用户在权限配置抽屉勾选模板 T 并保存
- **THEN** 系统 SHALL 为该 ADMIN 建立模板 T 的使用授权
- **AND** 该 ADMIN 后续在新建项目时 SHALL 在模板下拉中看到 T

#### Scenario: SUPER 撤销 ADMIN 的可用模板
- **WHEN** SUPER 用户在权限配置抽屉取消勾选模板 T 并保存
- **THEN** 系统 SHALL 移除该 ADMIN 对模板 T 的使用授权
- **AND** 已用模板 T 创建的项目 SHALL NOT 受影响

#### Scenario: 非 SUPER 不可见模板分配
- **WHEN** 非 SUPER 用户（如 ADMIN）访问管理员与权限设置页
- **THEN** 系统 SHALL NOT 显示模板分配相关 UI（ADMIN 无权分配）
