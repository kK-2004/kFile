## MODIFIED Requirements

### Requirement: 分享页支持多选下载
系统 SHALL 在分享页文件列表支持 checkbox 多选 + 单文件下载 + 批量打包下载选中。

#### Scenario: 单文件下载
- **WHEN** 用户点击某文件的「下载」按钮
- **THEN** 浏览器直接下载该文件（不走 zip），同时该文件下载计数 +1、链接下载计数 +1

#### Scenario: 多选打包下载
- **WHEN** 用户勾选多个文件后点击「下载选中」
- **THEN** 系统用 JSZip 打包选中文件下载，每个选中文件计数 +1、链接计数 +选中数

#### Scenario: 打包下载全部
- **WHEN** 用户点击「打包下载全部」
- **THEN** 打包全部文件下载，所有文件计数 +1、链接计数 +文件数

### Requirement: 下载量计数（链接维度 + 文件维度）
系统 SHALL 记录每个分享链接的总下载次数 + 每个文件的下载次数。

#### Scenario: 计数自增
- **WHEN** 用户通过 `POST /api/share/{code}/download` 记录下载
- **THEN** ShareLink.downloadCount 自增；若指定 entryIndex 则对应文件 entries[entryIndex].downloadCount 自增

#### Scenario: 分享页展示下载量
- **WHEN** 访问分享页
- **THEN** 展示链接总下载次数 + 文件列表每行展示该文件下载次数

#### Scenario: 管理页展示下载量
- **WHEN** SUPER/ADMIN 查看分享管理页
- **THEN** 列表含「下载量」列（链接维度 downloadCount）
