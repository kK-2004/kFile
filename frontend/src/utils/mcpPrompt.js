// KFile 使用技能原文：仓库根 skills/kfile-usage/SKILL.md，构建时内联为字符串。
// 单一来源——skill 更新只改 md 文件，本文件与提示词无需同步。
import kfileSkillMd from '../../../skills/kfile-usage/SKILL.md?raw'

/**
 * MCP 接入提示词：三行接入说明 + 完整 kfile-usage skill。
 * url 来自 /api/hero 的 mcpUrl（后端按环境下发），未取到时回退当前页面 origin。
 */
export function buildMcpPrompt(url) {
  const u = url || (window.location.origin + '/mcp')
  return `添加一个远程 MCP server\n名称：k-File-MCP\nURL 配置：${u}\n\n` +
    `连接成功后，先询问用户：该技能（skill）安装到当前项目还是全局？\n` +
    `确认后将下方技能内容原样保存到宿主对应的技能目录（项目级或全局级，保留技能名 kfile-usage），之后阅读并严格遵循该技能完成用户的 k-File 相关请求：\n\n` +
    kfileSkillMd.trim()
}
