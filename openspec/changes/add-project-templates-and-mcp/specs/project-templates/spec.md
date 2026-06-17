## ADDED Requirements

### Requirement: 模板字段范围

系统 SHALL 只将以下可复用项目字段保存为模板内容：`expectedUserFields`、`pathFieldKey`、`pathSegments`、`userSubmitStatusType`、`userSubmitStatusText`、`queryFieldKey`、`allowedSubmitterKeys`、`allowedSubmitterList`、`autoFileNamingEnabled`、`autoFileNamingConfig`、`allowResubmit`、`allowMultiFiles`、`allowOverdue`。系统 SHALL NOT 将 `name`、`startAt`、`endAt`、`fileSizeLimitBytes`、`allowedFileTypes`、`offline` 保存为模板内容。

#### Scenario: 保存模板时排除项目特有字段
- **WHEN** SUPER 用户在新建项目表单填写了 name="期末作业"、startAt、endAt、fileSizeLimitBytes、allowedFileTypes 以及若干可复用字段，并点击"保存为模板"
- **THEN** 系统 SHALL 创建一个模板，其内容仅包含可复用字段
- **AND** 该模板 SHALL NOT 包含 name、startAt、endAt、fileSizeLimitBytes、allowedFileTypes

### Requirement: 仅 SUPER 可创建模板

系统 SHALL 仅允许 SUPER 角色用户创建项目模板。ADMIN 角色用户 SHALL NOT 具备创建模板的能力。

#### Scenario: ADMIN 尝试保存模板被拒绝
- **WHEN** ADMIN 角色用户尝试调用保存模板接口
- **THEN** 系统 SHALL 返回 403 并拒绝创建

#### Scenario: SUPER 可在新建项目表单看到"保存为模板"按钮
- **WHEN** SUPER 角色用户进入新建项目表单
- **THEN** 表单 SHALL 显示"保存为模板"按钮
- **AND** ADMIN 角色用户进入同一表单时 SHALL NOT 看到该按钮

### Requirement: 模板所有权与分配权限

系统 SHALL 记录每个模板的创建者（owner，为 SUPER）。模板 owner 始终可用该模板，且为唯一可编辑和删除该模板的用户。SUPER SHALL 可将模板分配给指定的 ADMIN 使用，ADMIN 被分配后可只读使用该模板回填表单，但 SHALL NOT 编辑或删除模板本体。

#### Scenario: 模板 owner 始终可用自建模板
- **WHEN** SUPER 用户查询自己可用模板列表
- **THEN** 列表 SHALL 包含该 SUPER 创建的所有模板，无需额外分配

#### Scenario: SUPER 分配模板给 ADMIN
- **WHEN** SUPER 用户在"管理员与权限设置"页将模板 T 分配给 ADMIN 用户 A
- **THEN** 用户 A 的可用模板列表 SHALL 包含模板 T
- **AND** 用户 A 仅能使用模板 T 回填表单，不能编辑或删除 T

#### Scenario: SUPER 撤销 ADMIN 的模板使用权限
- **WHEN** SUPER 用户撤销 ADMIN 用户 A 对模板 T 的分配
- **THEN** 用户 A 的可用模板列表 SHALL NOT 再包含模板 T
- **AND** 已用模板 T 创建的项目不受影响

### Requirement: 可用模板查询的统一来源

系统 SHALL 通过同一查询逻辑（owner 为当前用户 ∪ 被分配给当前用户）提供可用模板列表，供 Web 创建项目下拉与 MCP `list_my_templates` 工具共用，保证两者返回一致。

#### Scenario: Web 下拉与 MCP 工具返回一致的可用模板
- **WHEN** 同一管理员分别通过 Web 创建项目下拉与 MCP `list_my_templates` 查询可用模板
- **THEN** 两者返回的模板集合 SHALL 一致

### Requirement: 创建项目时模板下拉与回填（Web）

系统 SHALL 在新建项目表单顶部提供"使用模板"下拉框，默认选项为"不使用模板"。下拉框 SHALL 列出当前登录用户有权限使用的全部模板。当用户选择某模板时，系统 SHALL 自动将该模板的可复用字段回填到表单，且 SHALL NOT 回填 name、startAt、endAt、fileSizeLimitBytes、allowedFileTypes。

#### Scenario: 新建项目下拉框列出可用模板
- **WHEN** 已登录管理员进入新建项目表单
- **THEN** 页面顶部 SHALL 显示"使用模板"下拉框，默认值为"不使用模板"
- **AND** 下拉框 SHALL 包含当前用户所有可用模板

#### Scenario: 选择模板自动回填可复用字段
- **WHEN** 用户在新建项目表单选择模板 T
- **THEN** 系统 SHALL 将 T 的可复用字段回填到表单
- **AND** name、开始时间、截止时间、文件大小、扩展名白名单字段 SHALL 保持为空

#### Scenario: 切换或取消模板
- **WHEN** 用户在已选择模板 T 的状态下切换为"不使用模板"或选择另一模板 T2
- **THEN** 表单 SHALL 重新按新选择（或空）重置可复用字段，避免残留上一模板的字段

### Requirement: 模板管理 REST API

系统 SHALL 提供以下接口：SUPER 创建/更新/删除模板、SUPER 查看全部模板、SUPER 给 ADMIN 分配/撤销模板、SUPER 查看某 ADMIN 的已分配模板、任意已登录管理员查看自己可用模板。所有模板管理与分配接口 SHALL 校验调用者具有 SUPER 角色。

#### Scenario: 非 SUPER 调用模板管理接口被拒绝
- **WHEN** 非 SUPER 用户调用模板创建、更新、删除或分配接口
- **THEN** 系统 SHALL 返回 403

#### Scenario: 管理员查看自己可用模板
- **WHEN** 已登录管理员请求自己可用模板列表
- **THEN** 系统 SHALL 返回 owner 为自己或被分配给自己的模板，且仅含回填所需字段
