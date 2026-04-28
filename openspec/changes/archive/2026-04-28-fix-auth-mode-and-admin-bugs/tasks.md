## 1. 修复前端 auth.mode 死代码

- [x] 1.1 修改 `frontend/src/views/user/UserProjects.vue`：将第 37 行 `!!auth.user && auth.user.mode === 'local'` 替换为 `!!auth.user`，将第 43 行 `auth.user.mode !== 'local'` 替换为 `!auth.user`
- [x] 1.2 修改 `frontend/src/views/admin/AdminProjectForm.vue`：将第 949 行 `return role === 'SUPER' || role === 'ADMIN' || auth.user.mode === 'local'` 中的 `|| auth.user.mode === 'local'` 删除

## 2. 修复删除用户事务异常

- [x] 2.1 在 `AdminUserController.java` 的 `deleteUser()` 方法（第 99 行）上添加 `@org.springframework.transaction.annotation.Transactional` 注解，并在文件头部添加对应 import

## 3. 修复 ADMIN 用户文件类型校验

- [x] 3.1 修改 `ProjectService.java` 的 `create()` 方法（约第 40-42 行）：将 `isAdmin` 判断从仅检查 `ROLE_SUPER` 扩展为同时检查 `ROLE_SUPER` 和 `ROLE_ADMIN`
- [x] 3.2 修改 `ProjectService.java` 的 `update()` 方法（约第 160-163 行）：同上，将 `isAdmin` 判断扩展为包含 `ROLE_ADMIN`
