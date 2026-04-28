## Context

k-File 是一个文件收集管理平台，采用 Spring Boot 3 后端 + Vue 3 前端。系统有两种管理员角色：SUPER（超级管理员）和 ADMIN（普通管理员）。后端使用 Spring Security + Session 认证，前端使用 Pinia auth store 管理登录状态。

当前存在多个互相关联的 bug：
1. 前端存在未实现的 `auth.user.mode === 'local'` 检查（后端从未返回 `mode` 字段），导致用户端项目列表始终不可见
2. `AdminUserController.deleteUser()` 缺少 `@Transactional` 注解，JPA 无法执行 delete 操作
3. `ProjectService` 中 `isAdmin` 判断只识别 `ROLE_SUPER`，ADMIN 用户被误当作普通用户受全局文件类型白名单约束

## Goals / Non-Goals

**Goals:**
- 修复 `mode === 'local'` 导致的用户端项目列表不可见问题
- 修复 SUPER 删除 ADMIN 用户的事务异常
- 修复 ADMIN 用户管理项目时被全局白名单拦截的问题
- 清理所有 `mode === 'local'` 死代码

**Non-Goals:**
- 不引入新的 `mode` 字段或认证机制
- 不修改数据库 schema
- 不改变 SUPER/ADMIN 的权限边界定义
- 不涉及普通终端用户的提交流程

## Decisions

### D1: 用 `!!auth.user` 替代 `auth.user.mode === 'local'`

**选择**: 直接检查用户是否已登录（`!!auth.user`），而非添加 `mode` 字段到后端响应。

**理由**: `mode` 原本设计用于区分本地登录与第三方登录，但第三方登录从未实现。当前所有管理员都是本地账号登录，简单的登录状态检查即可满足需求。后端已有正确的权限过滤（SUPER 看全部项目、ADMIN 只看被分配的项目）。

**备选方案**: 后端在 `/api/admin/auth/me` 响应中添加 `mode: "local"` 字段 — 不采用，因为增加了不必要的复杂度，且无实际区分场景。

### D2: 在方法级别添加 `@Transactional`

**选择**: 在 `deleteUser()` 方法上添加 `@Transactional` 注解。

**理由**: 该方法执行 `permRepo.deleteByUser(u)` + `userRepo.delete(u)` 两个写操作，需要事务保证原子性。方法级别注解是最小侵入的修复方式。

**备选方案**: 在 Repository 的 `deleteByUser` 方法上添加 `@Modifying` + `@Transactional` — 不采用，因为 `deleteUser()` 本身就是多步操作，应在方法级别统一管理事务。

### D3: `isAdmin` 判断扩展至 `ROLE_ADMIN`

**选择**: 将 `ProjectService` 中 `isAdmin` 的判断从仅 `ROLE_SUPER` 扩展为 `ROLE_SUPER || ROLE_ADMIN`。

**理由**: ADMIN 是系统管理员角色，经 SUPER 授权后管理指定项目。他们不应受全局 USER 文件类型白名单约束 — 该白名单是为非管理员终端用户设计的。当前行为导致 ADMIN 编辑 SUPER 创建的项目时，因文件类型不在白名单中而报错，这是不合理的。

**备选方案**: 在 update 方法中仅当 `allowedFileTypes` 实际被修改时才校验 — 不采用，因为语义上 ADMIN 就不应受此约束，修改校验时机只是绕过问题。

## Risks / Trade-offs

- **[Risk] 移除 `mode === 'local'` 后未来第三方登录无法区分** → 缓解：当前无第三方登录计划；未来如有需要，可在后端添加 `mode` 字段并恢复前端检查
- **[Risk] `isAdmin` 扩展后 ADMIN 创建项目也不受文件类型约束** → 可接受：ADMIN 本身就是管理员，其权限由 SUPER 分配，与 SUPER 创建项目行为一致
