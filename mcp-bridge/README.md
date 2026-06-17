# kfile-mcp

一个 stdio MCP server，把本地 MCP 客户端（workbuddy、Claude Desktop、Cursor、Cline 等）桥接到 **k-File** 的远程 SSE MCP 服务端，并自动完成 OAuth 网页授权与令牌管理。

装好后首次运行会自动打开浏览器让你登录授权一次，之后全自动复用令牌——无需手动复制 token、无需配 Bearer header。

## 工作原理

```
你的 MCP 客户端 ──stdio──▶ kfile-mcp ──SSE + Bearer──▶ k-File 后端
                                │
                                ├ 首次（无令牌）:
                                │   起本地回调 → 开浏览器授权 → 收 ?token= → 存文件
                                └ 之后: 直接读令牌文件连 SSE
```

## 前置要求

- **Node.js ≥ 18**
- 已部署的 k-File 后端（提供 `/mcp/sse` 与 `/admin/mcp/authorize`）
- k-File SUPER 已在「系统设置 → MCP 授权回调白名单」加入 `http://127.0.0.1:` 前缀（否则本地回调会被拒）

## 配置（环境变量）

| 变量 | 必填 | 说明 | 默认 |
|---|---|---|---|
| `KFILE_HOST` | | k-File 后端地址，如 `http://localhost:9000`。不设置时连接默认服务 | `https://file.ksite.xin` |
| `KFILE_TOKEN` | | 直接指定令牌，跳过自动授权流程 | — |
| `KFILE_TOKEN_FILE` | | 令牌存储路径 | `~/.kfile-mcp/token.json` |
| `KFILE_CALLBACK_PORT` | | 本地授权回调端口 | 随机 |

## 各客户端接入

### workbuddy

编辑 `~/.workbuddy/mcp.json`：

```json
{
  "mcpServers": {
    "kfile": {
      "command": "npx",
      "args": ["-y", "@kk-2004/kfile-mcp"]
    }
  }
}
```

### Claude Desktop

`~/Library/Application Support/Claude/claude_desktop_config.json`（macOS）：

```json
{
  "mcpServers": {
    "kfile": {
      "command": "npx",
      "args": ["-y", "@kk-2004/kfile-mcp"]
    }
  }
}
```

### Cursor

`~/.cursor/mcp.json`：

```json
{
  "mcpServers": {
    "kfile": {
      "command": "npx",
      "args": ["-y", "@kk-2004/kfile-mcp"]
    }
  }
}
```

### Cline

`~/.cline/cline_mcp_settings.json`：

```json
{
  "mcpServers": {
    "kfile": {
      "command": "npx",
      "args": ["-y", "@kk-2004/kfile-mcp"],
      "disabled": false,
      "autoApprove": []
    }
  }
}
```

## 授权流程（在 agent 内完成，无需手动跑终端）

配置好后重启客户端。bridge 启动时**不阻塞**，且**立即列出全部工具**（kfile_login、kfile_logout + k-File 业务工具）。agent 一开始就能看到完整能力清单——未登录时调用真实工具会返回"请先 kfile_login"。

整体功能说明通过 MCP 的 server `instructions` 字段一次性传达给 agent（不重复写在每个工具描述里）。

### 首次使用 / 令牌过期

1. bridge 启动，所有工具可见。
2. 调用 `kfile_login` → bridge 起本地回调 + 打开浏览器到 `<KFILE_AUTH_HOST>/admin/mcp/authorize`。
3. 浏览器登录（若未登录）→ 点「授权并跳转」。
4. 令牌通过 `?token=` 回调到本地，保存到 `~/.kfile-mcp/token.json`，bridge 连接 SSE。
5. 之后即可调用 k-File 工具。

### 之后使用

bridge 启动时若发现已存令牌，会**静默自动连接**，无需再调 `kfile_login`。

### 退出

调用 `kfile_logout` → 清除本地令牌 + 断开连接，回到未授权态。用于切换账号、令牌失效或安全考虑。

## 故障排查

- **需要连接自部署后端**：在客户端配置的 `env` 里设置 `KFILE_HOST`，例如 `http://localhost:9000`。
- **调用真实工具返回"未登录"**：还没授权。调用 `kfile_login`（或重启客户端后它会自动用已存令牌）。
- **`kfile_login` 报 redirect_uri 不合法**：k-File SUPER 没在「系统设置 → MCP 授权回调白名单」放行 `http://127.0.0.1:`。
- **k-File 工具调用返回 401**：令牌过期或被吊销。调用 `kfile_logout` 再 `kfile_login` 重新授权。
- **无法自动打开浏览器**（无 GUI / SSH 环境）：`kfile_login` 会打印授权链接，手动复制到浏览器打开即可。

## 可用工具

bridge 启动即列出全部工具（整体功能见 server instructions）：

| 工具 | 用途 |
|---|---|
| `kfile_login` | 授权登录（开浏览器），成功后可调用其他工具 |
| `kfile_logout` | 退出登录并清除令牌，回到未授权态 |
| `list_my_templates` | 列出可用项目模板 |
| `create_project` | 创建项目（支持模板回填，仅 SUPER） |
| `list_my_projects` | 列出有权限的项目 |
| `get_project_info` | 获取项目详情与用户填写链接 |
| `list_missing_submitters` | 查某项目未提交名单 |
| `create_archive_download_link` | 生成打包下载链接 |
| `ask_user_choice` | 向用户提问让其选项选择 |

## 开发

```bash
cd mcp-bridge
npm install
npm run build       # 输出到 dist/
npm start           # 本地运行（默认连接 https://file.ksite.xin）
```

发布：

```bash
npm publish
```

## 安全

- 令牌等同你的管理员身份（角色 + 项目权限），有效期 6 个月，保存在 `~/.kfile-mcp/token.json`（权限 0600）。
- 如怀疑泄露：删除令牌文件 + 在 k-File「管理员与权限设置 → MCP 访问令牌」吊销。
- bridge 工具集仅含低危操作（创建 + 只读查询 + 未提交查询），不含文件上传/删除/修改项目。

## License

MIT
