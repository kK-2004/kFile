## MODIFIED Requirements

### Requirement: 移动端导航折叠
系统 SHALL 在小屏（<768px）将 header 导航按钮折叠为汉堡菜单图标，点击展开抽屉式导航；大屏（>=768px）保持现有按钮布局。

#### Scenario: 小屏显示汉堡菜单
- **WHEN** 视口宽度 < 768px
- **THEN** header 导航按钮隐藏，显示汉堡图标；点击打开左侧 el-drawer 展示导航列表

#### Scenario: 大屏保持原样
- **WHEN** 视口宽度 >= 768px
- **THEN** header 导航按钮正常展示，无汉堡图标

### Requirement: 表格移动端横向滚动
系统 SHALL 允许 el-table 在小屏横向滚动，不裁切列内容。

#### Scenario: 小屏表格滚动
- **WHEN** 视口宽度不足以显示所有列
- **THEN** 表格可横向滚动查看所有列，内容不被裁切

### Requirement: 工具栏自适应换行
系统 SHALL 在窄屏下让 card-header 和工具栏 flex-wrap 换行，不溢出。

#### Scenario: 窄屏工具栏换行
- **WHEN** card-header / 工具栏内容超出视口宽度
- **THEN** 自动换行排列，不横向溢出
