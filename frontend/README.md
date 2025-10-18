# K-File Frontend (Vue 3 + Vite + Element Plus)

## 开发启动

- 先启动后端（默认 http://localhost:8080）
- 前端开发模式采用 Vite 代理将 `/api` 转发至后端

运行：

    cd frontend
    npm install
    npm run dev

可选：使用环境变量 `VITE_PROXY_TARGET` 覆盖代理目标，或设置 `VITE_API_BASE` 直连后端（跳过代理）。

## 构建

    npm run build

## 功能概览

- 用户端
  - 列出项目、查看项目详情、根据期望字段动态生成表单
  - 文件选择与前端校验（扩展名、大小），上传提交
- 管理端
  - 项目列表、新建/编辑项目、上下线
  - 查看提交记录（分页）、导出 CSV

接口对接参见后端 `docs/API.md`。
