<div align="center">

<h1>kFile</h1>

<p><strong>文件收集 · 网盘 · CDN 外链，一站搞定</strong></p>
<p>内置远程 MCP 服务，让 AI 用一句话帮你收文件</p>

<p>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/Vue-3-4FC08D?style=flat-square&logo=vuedotjs&logoColor=white" alt="Vue" />
  <img src="https://img.shields.io/badge/MCP-Streamable%20HTTP-8A2BE2?style=flat-square" alt="MCP" />
  <a href="https://github.com/kK-2004/kFile/stargazers"><img src="https://img.shields.io/github/stars/kK-2004/kFile?style=flat-square&logo=github" alt="Stars" /></a>
</p>

<p>
  <a href="https://file.ksite.xin"><strong>在线体验</strong></a>
  &nbsp;&nbsp;·&nbsp;&nbsp;
  <a href="#快速开始"><strong>快速开始</strong></a>
  &nbsp;&nbsp;·&nbsp;&nbsp;
  <a href="#mcp-接入"><strong>MCP 接入</strong></a>
  &nbsp;&nbsp;·&nbsp;&nbsp;
  <a href="sdk/README.md"><strong>SDK 文档</strong></a>
</p>

<br />

<img src="https://file.ksite.xin/file/cdn/ez--PT_uE-jUuV0WfYO7H48KYBl0V166o7_x545-yTQ" alt="kFile 首页" width="92%" />

</div>

<br />

## 目录

- [核心能力](#核心能力)
- [MCP 接入：一句话管理文件收集](#mcp一句话管理文件收集)
- [自定义对象存储源](#自定义对象存储源)
- [更多功能](#更多功能)
- [技术栈](#技术栈)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [MCP 接入](#mcp-接入)
- [开放 API 与 SDK](#开放-api-与-sdk)
- [许可证](#许可证)

## 核心能力

<table>
  <tr>
    <th width="33%">文件收集</th>
    <th width="33%">网盘</th>
    <th width="33%">一键 CDN 链接</th>
  </tr>
  <tr>
    <td valign="top">创建收集项目并分享链接，成员按规范提交。支持截止时间、人员名单、文件格式校验、<b>自动命名</b>、实时查看未交名单、<b>一键打包下载</b>。</td>
    <td valign="top">统一管理所有上传文件，支持大文件<b>分片上传、断点续传</b>，生成限时<b>分享链接</b>，过期自动清理。</td>
    <td valign="top">图片、音频、视频一键生成<b>稳定的 CDN 预览外链</b>，可直接嵌入网页、文档和 Markdown —— 本 README 的截图就是用它托管的。</td>
  </tr>
</table>

## MCP：一句话管理文件收集

kFile 内置远程 MCP 服务，Claude、WorkBuddy 等 AI 客户端授权后即可用自然语言操作：

- **一键创建项目** —— 描述需求，AI 先给出预览，确认后再创建
- **查看提交进度** —— 谁交了、谁没交、交了什么，直接回答
- **获取下载链接** —— 一句话生成打包下载链接

<table>
  <tr>
    <td width="50%" align="center">
      <img src="https://file.ksite.xin/file/cdn/4aFtxeLi9-HL3g4XHpS4JkoG3jRaga5WVWBOA8PAyuA" alt="Claude MCP 演示" />
      <br /><sub><b>Claude</b> 中使用 kFile MCP</sub>
    </td>
    <td width="50%" align="center">
      <img src="https://file.ksite.xin/file/cdn/REykSBUFXpYkg4FzoVkSTvWozCboW2yfQQ8KfA6xMTw" alt="WorkBuddy MCP 演示" />
      <br /><sub><b>WorkBuddy</b> 中使用 kFile MCP</sub>
    </td>
  </tr>
</table>

## 自定义对象存储源

<table>
  <tr>
    <td width="40%" valign="top">
      <p>后台可配置上传使用的对象存储源：</p>
      <ul>
        <li>阿里云 OSS</li>
        <li>MinIO（私有化部署，S3 兼容）</li>
      </ul>
      <p>文件经<b>预签名 URL 直传</b>对象存储，不占用服务器带宽。</p>
    </td>
    <td width="60%">
      <img src="https://file.ksite.xin/file/cdn/-DENoLnAVGo11-NTTz9mQUz1da6EtNChABob64eO4ng" alt="后台自定义对象存储源" />
    </td>
  </tr>
</table>

## 更多功能

| 功能 | 说明 |
| :--- | :--- |
| **智能截止控制** | 到期自动停止收集，支持单人多文件提交 |
| **项目模板** | 常用收集配置保存为模板，一键复用 |
| **安全授权** | MCP 使用 OAuth 2.1（授权码 + PKCE、动态客户端注册） |
| **开放 API 与 SDK** | appToken 鉴权，提供 Java / Go / Python 官方 SDK |
| **消息通知** | 接入飞书机器人等消息通道 |

## 技术栈

<table>
  <tr>
    <td width="18%"><b>后端</b></td>
    <td>
      <img src="https://img.shields.io/badge/Java%2021-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java" />
      <img src="https://img.shields.io/badge/Spring%20Boot%203.5-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot" />
      <img src="https://img.shields.io/badge/Spring%20Security-6DB33F?style=flat-square&logo=springsecurity&logoColor=white" alt="Spring Security" />
      <img src="https://img.shields.io/badge/Spring%20Data%20JPA-6DB33F?style=flat-square&logo=spring&logoColor=white" alt="Spring Data JPA" />
      <img src="https://img.shields.io/badge/Authorization%20Server-6DB33F?style=flat-square&logo=spring&logoColor=white" alt="Spring Authorization Server" />
      <img src="https://img.shields.io/badge/Spring%20AI%20MCP-6DB33F?style=flat-square&logo=spring&logoColor=white" alt="Spring AI MCP Server" />
    </td>
  </tr>
  <tr>
    <td><b>前端</b></td>
    <td>
      <img src="https://img.shields.io/badge/Vue%203-4FC08D?style=flat-square&logo=vuedotjs&logoColor=white" alt="Vue" />
      <img src="https://img.shields.io/badge/Vite-646CFF?style=flat-square&logo=vite&logoColor=white" alt="Vite" />
      <img src="https://img.shields.io/badge/Element%20Plus-409EFF?style=flat-square&logo=element&logoColor=white" alt="Element Plus" />
      <img src="https://img.shields.io/badge/Pinia-FFD859?style=flat-square&logo=pinia&logoColor=black" alt="Pinia" />
      <img src="https://img.shields.io/badge/Tailwind%20CSS-06B6D4?style=flat-square&logo=tailwindcss&logoColor=white" alt="Tailwind CSS" />
    </td>
  </tr>
  <tr>
    <td><b>存储</b></td>
    <td>
      <img src="https://img.shields.io/badge/MySQL-4479A1?style=flat-square&logo=mysql&logoColor=white" alt="MySQL" />
      <img src="https://img.shields.io/badge/阿里云%20OSS-FF6A00?style=flat-square&logo=alibabacloud&logoColor=white" alt="Aliyun OSS" />
      <img src="https://img.shields.io/badge/MinIO-C72E49?style=flat-square&logo=minio&logoColor=white" alt="MinIO" />
    </td>
  </tr>
  <tr>
    <td><b>基础设施</b></td>
    <td>
      <img src="https://img.shields.io/badge/Nacos-1F6FEB?style=flat-square" alt="Nacos" />
      <img src="https://img.shields.io/badge/XXL--JOB-E34F26?style=flat-square" alt="XXL-JOB" />
      <img src="https://img.shields.io/badge/MaxMind%20GeoIP-可选-6E7681?style=flat-square" alt="MaxMind GeoIP" />
      <img src="https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white" alt="Docker" />
    </td>
  </tr>
</table>

## 目录结构

```text
kFile
├── src/main/java/com/kk   # 后端：项目/提交、存储、分享、开放 API、MCP、OAuth 等模块
├── frontend/              # 前端（Vue 3 + Vite）
├── sdk/                   # 开放 API 多语言 SDK（java / go / python）
├── skills/kfile-usage/    # 供 AI 助手使用的 kFile MCP 使用技能
├── docs/                  # 存储、预览说明及 Nacos 配置备份
├── Dockerfile             # 多阶段构建镜像
└── docker-compose.yml     # 部署编排
```

## 快速开始

### 环境要求

| 依赖 | 版本 |
| :--- | :--- |
| JDK | 21+ |
| Maven | 3.9+ |
| Node.js | 18+ |
| MySQL | 8.x |
| Nacos | 2.x（业务配置托管在 Nacos） |
| 对象存储 | 阿里云 OSS 或 MinIO 任选其一 |

### 1. 准备配置

本地 `application.yml` 只保留 Nacos 接入信息，业务配置全部托管在 Nacos：

| Profile | Data ID | 配置模板 |
| :--- | :--- | :--- |
| `dev` | `kfile-dev.yaml` | [docs/nacos/kfile-dev.yaml](docs/nacos/kfile-dev.yaml) |
| `prod` | `kfile.yaml` | [docs/nacos/kfile.yaml](docs/nacos/kfile.yaml) |

将模板导入 Nacos 后，按需修改数据库、OSS / MinIO 等配置。Nacos 连接信息可通过环境变量覆盖：

```bash
export NACOS_SERVER_ADDR=127.0.0.1:8848
export NACOS_USERNAME=nacos
export NACOS_PASSWORD=nacos
```

### 2. 启动后端

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

> [!NOTE]
> 依赖中的 `kmessage-sdk` 发布在 private GitHub Packages，可自行更换IM实现方式。

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

Vite 会将 `/api` 代理到后端，可通过 `VITE_PROXY_TARGET` 修改代理目标。

### 4. Docker 部署

```bash
docker compose up -d
```

默认暴露 `8081` 端口，数据库、MinIO 等参数通过环境变量注入，详见 [docker-compose.yml](docker-compose.yml)。

## MCP 接入

kFile 提供远程 MCP 服务，入口为 `/mcp`（Streamable HTTP），使用 OAuth 授权，支持 Claude、WorkBuddy、Cursor 等 MCP 客户端直接接入：

```json
{
  "mcpServers": {
    "kfile": {
      "url": "https://<your-domain>/mcp"
    }
  }
}
```

客户端首次连接时会自动完成动态注册并跳转浏览器授权。

<details open>
<summary><b>可用工具</b></summary>
<br />

| 工具 | 作用 |
| :--- | :--- |
| `list_my_projects` | 查看我的收集项目 |
| `get_project_info` | 查看项目配置、获取填写链接 |
| `create_project` | 创建收集项目（预览确认后执行） |
| `list_my_templates` | 查看项目模板 |
| `list_project_submissions` | 查看提交情况 |
| `list_missing_submitters` | 查看未提交名单 |
| `create_archive_download_link` | 生成打包下载链接 |

</details>

> [!TIP]
> 配合 [skills/kfile-usage](skills/kfile-usage/SKILL.md) 技能，AI 助手能更准确地完成操作。

## 开放 API 与 SDK

在管理后台创建开放应用获取 `appToken`，即可通过 `/api/open/**` 上传文件、获取下载链接和媒体预览地址：

| 语言 | 目录 | 说明 |
| :--- | :--- | :--- |
| <img src="https://img.shields.io/badge/Java-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java" /> | [sdk/java](sdk/java) | Maven 坐标 `com.kk:content-center-sdk`，Java 17+ |
| <img src="https://img.shields.io/badge/Go-00ADD8?style=flat-square&logo=go&logoColor=white" alt="Go" /> | [sdk/go](sdk/go) | `github.com/kK-2004/kFile/sdk/go`，仅依赖标准库 |
| <img src="https://img.shields.io/badge/Python-3776AB?style=flat-square&logo=python&logoColor=white" alt="Python" /> | [sdk/python](sdk/python) | PyPI 包 `content-center-sdk`，Python 3.11+ |

详见 [sdk/README.md](sdk/README.md)。

## 许可证

本项目基于 [MIT License](LICENSE) 开源，可自由使用、修改、分发和商用。

二次开发或再分发时，**必须保留 [LICENSE](LICENSE) 文件及其中的原作者版权声明**。

<br />

<div align="center">

<sub>Copyright © 2025-2026 <a href="https://github.com/kK-2004">kK-2004</a></sub>

<sub>如果这个项目对你有帮助，欢迎点一个 Star 支持一下</sub>

</div>