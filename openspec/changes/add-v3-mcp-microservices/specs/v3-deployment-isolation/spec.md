## ADDED Requirements

### Requirement: 独立的应用名
系统必须以与 `v2` 任意服务都不同的应用名注册 `v3` 服务。具体而言，网关必须以 `kfile-v3-gateway` 注册，服务必须以 `kfile-v3-service` 注册。任何 `v3` 进程都必须不以 `v2` 应用名注册。

#### Scenario: 服务 ID 带有 v3 前缀
- **WHEN** `v3` 整套服务启动并向 Nacos 注册
- **THEN** 注册的服务 ID 为 `kfile-v3-gateway` 与 `kfile-v3-service`，且任何 `v3` 进程都不会出现在任何 `v2` 服务 ID 下

### Requirement: 独立的 Nacos 命名空间与分组
系统必须将 `v3` 服务注册到 Nacos 的 `kfile-v3` 命名空间与 `kfile-v3` 分组。`v2` 服务必须保留在其原有命名空间/分组中且不被改动。`v3` 的配置必须存放于 `kfile-v3` 命名空间/分组下。

#### Scenario: v2 命名空间不可见 v3
- **WHEN** 运维人员在 `v2` 的 Nacos 命名空间中查看服务列表
- **THEN** 列表中不出现任何 `kfile-v3-*` 服务

#### Scenario: v3 命名空间可见 v3
- **WHEN** 运维人员在 `kfile-v3` 命名空间中查看服务列表
- **THEN** 列表中出现 `kfile-v3-gateway` 与 `kfile-v3-service`，且健康状态正常

### Requirement: 独立的入口
系统必须通过与 `v2` 不重叠的入口对外暴露 `v3` 的 HTTP 与 MCP 流量。可以使用独立域名（例如 `kfile-v3.example.com`）或独立路径前缀（例如 HTTP 使用 `/kfile-v3/**`，MCP 使用 `/mcp/**`）。无论选择哪种隔离策略，MCP 入口必须为 `/mcp/{namespace}`。

#### Scenario: MCP 入口可达
- **WHEN** MCP 客户端连接到 `v3` 入口
- **THEN** `/mcp/user` 与 `/mcp/admin` 仅在 `v3` 入口上可达，不会出现在 `v2` 入口上

### Requirement: 独立的部署制品
系统必须为 `v3` 提供独立的部署制品（例如 `docker-compose-v3.yml` 或对应的 K8s 清单）。本变更必须不修改既有的 `v2` 部署制品（`docker-compose.yml`）。

#### Scenario: docker-compose.yml 未被改动
- **WHEN** 评审人对比变更前后的 `docker-compose.yml`
- **THEN** 不存在任何功能性差异（services、ports、images、env、volumes 完全一致）

#### Scenario: docker-compose-v3.yml 启动 v3 服务栈
- **WHEN** 运维人员执行 `docker compose -f docker-compose-v3.yml up`
- **THEN** `kfile-v3-gateway` 与 `kfile-v3-service` 成功启动并进入健康状态

### Requirement: 数据库隔离
系统应当为 `v3` 使用独立的数据库或 schema（例如 `kfile_v3`）。如确需共用同一数据库，`v3` 引入的所有新表必须为新增表，必须以 `v3_` 前缀命名，且必须不修改任何 `v2` 所属列的结构或语义。

#### Scenario: 新表带有 v3 前缀
- **WHEN** `v3` 的迁移脚本新增一张表
- **THEN** 表名以 `v3_` 开头（例如 `v3_api_token`）

#### Scenario: v2 表未被修改
- **WHEN** 应用 `v3` 的整套迁移脚本到数据库
- **THEN** 没有任何 `ALTER TABLE`、`DROP COLUMN` 或列重命名语句作用于 `v2` 所属的表

### Requirement: 通过停止 v3 服务栈进行回滚
系统必须支持运维人员通过停止 `v3` 部署制品来彻底回滚 `v3`，且对 `v2` 部署不产生任何影响。

#### Scenario: v3 下线，v2 健康
- **WHEN** 运维人员执行 `docker compose -f docker-compose-v3.yml down`
- **THEN** `kfile-v3-*` 服务不再注册或可达，`v2` 服务保持健康并继续承载流量
