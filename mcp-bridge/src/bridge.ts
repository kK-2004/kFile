/**
 * stdio ↔ SSE bridge for k-File MCP, with in-protocol authorization.
 *
 * Design:
 *   - A server-level "instructions" string explains the whole MCP's purpose
 *     and the login flow ONCE, so tool descriptions stay concise (no repeat).
 *   - Tools are NOT hardcoded locally. The k-File backend is the single source
 *     of truth: after login, tools/list forwards the backend's listTools()
 *     result (names/descriptions/schemas), so new/changed backend tools appear
 *     automatically without a bridge release.
 *   - Before login, only kfile_login / kfile_logout are exposed — this breaks
 *     the "must see login tool before logging in" deadlock; calling a real
 *     tool before login returns "请先 kfile_login".
 *   - kfile_login runs the OAuth browser flow + connects SSE.
 *   - kfile_logout deletes the token + disconnects.
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import { runAuthorizationFlow, loadToken, clearToken } from './auth.js';

export interface BridgeOptions {
  host: string;
}

/** Server-level instructions: explains the whole MCP once (not per-tool). */
const INSTRUCTIONS = [
  'k-File MCP —— 让你（AI agent）代管理员操作 k-File 文件收集系统。',
  'k-File 是一个文件收集系统：管理员创建"项目"定义收集规则（字段、文件类型、截止时间等），用户按规则提交文件，管理员可查询提交情况。',
  '',
  '工具清单（名称/用途/参数）以 tools/list 为准，由 k-File 后端动态返回；本说明只讲通用约定，不逐个枚举工具。',
  '',
  '鉴权：首次使用必须先调用 kfile_login 工具登录（会打开浏览器让用户授权）；之后即可调用其他工具。用完可用 kfile_logout 退出。',
  '权限由授权账号的角色决定：SUPER 可创建项目、看全部项目；ADMIN 只能看被分配给自己的项目、不能创建。',
  '',
  '重要约定：凡需用户在确定选项中选择（选模板/选项目/开关取值），优先调用 ask_user_choice 让用户选，而非让用户在输入框手输。',
].join('\n');

const LOGIN_TOOL = {
  name: 'kfile_login',
  description:
    '授权并登录 k-File。首次使用或令牌过期时调用：会打开浏览器让用户登录授权，成功后即可调用其他工具。',
  inputSchema: { type: 'object' as const, properties: {}, required: [] as string[] },
};

const LOGOUT_TOOL = {
  name: 'kfile_logout',
  description:
    '退出登录并清除本地令牌。之后调用其他工具需重新 kfile_login。用于令牌失效、切换账号或安全考虑。',
  inputSchema: { type: 'object' as const, properties: {}, required: [] as string[] },
};

/**
 * k-File 工具的元数据（name/description/inputSchema）来自后端，是单一真相源。
 * 本地不维护工具清单：登录后 tools/list 直接转发后端 listTools() 的结果，
 * 登录前只暴露 kfile_login / kfile_logout（打破“要先看到 login 才能登录”的死锁）。
 * 这样后端新增/修改工具后，bridge 无需同步、无需发版，agent 自动看到最新定义。
 */

export async function runBridge(opts: BridgeOptions): Promise<void> {
  const { host } = opts;
  const authHost = (process.env.KFILE_AUTH_HOST || host).trim();

  const server = new Server(
    { name: 'kfile-mcp-bridge', version: '0.2.0' },
    { capabilities: { tools: {} }, instructions: INSTRUCTIONS },
  );

  let remoteClient: Client | null = null;
  let remoteToolNames = new Set<string>();
  // 后端返回的完整工具定义缓存，供 tools/list 转发。
  let remoteTools: any[] = [];
  let activeToken: string | null = null;


  /** Connect to k-File SSE with a token. */
  async function connectRemote(token: string): Promise<void> {
    const sseUrl = new URL(`${host.replace(/\/$/, '')}/mcp/sse`);
    const authHeaders = { Authorization: `Bearer ${token}` };
    const sseTransport = new SSEClientTransport(sseUrl, {
      eventSourceInit: { fetch: authedFetch(authHeaders) } as any,
      requestInit: { headers: authHeaders },
    });
    const client = new Client(
      { name: 'kfile-mcp-bridge', version: '0.2.0' },
      { capabilities: {} },
    );
    await client.connect(sseTransport);
    remoteClient = client;
    activeToken = token;
    try {
      const listed = await client.listTools();
      remoteTools = listed.tools || [];
      remoteToolNames = new Set(remoteTools.map(tool => tool.name));
      if (remoteToolNames.size === 0) {
        remoteTools = [];
        console.error('[kfile-mcp-bridge] 远端工具列表为空。');
      } else {
        console.error(`[kfile-mcp-bridge] 远端工具: ${Array.from(remoteToolNames).join(', ')}`);
      }
    } catch (e: any) {
      remoteTools = [];
      remoteToolNames = new Set();
      console.error(`[kfile-mcp-bridge] 远端工具列表读取失败: ${e?.message || e}`);
    }
    console.error('[kfile-mcp-bridge] 已连接 k-File SSE，工具可用。');
  }

  /** Disconnect (logout). */
  function disconnectRemote(): void {
    if (remoteClient) {
      try { remoteClient.close(); } catch {}
      remoteClient = null;
      remoteToolNames = new Set();
      remoteTools = [];
      activeToken = null;
    }
  }

  // tools/list: 未登录只暴露 login/logout（打破死锁）；已登录转发后端的完整工具清单。
  server.setRequestHandler(ListToolsRequestSchema, async () => {
    return { tools: [LOGIN_TOOL, LOGOUT_TOOL, ...remoteTools] };
  });

  // tools/call: route to login/logout or forward to remote.
  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args } = request.params;

    if (name === 'kfile_login') {
      if (remoteClient) {
        return { content: [{ type: 'text', text: '已登录，无需重复授权。可直接调用其他工具。' }] };
      }
      try {
        let token = loadToken(host);
        if (!token) {
          token = await runAuthorizationFlow(host, authHost);
        }
        await connectRemote(token);
        return {
          content: [{ type: 'text', text: '授权成功，已连接 k-File。现在可以调用 list_my_projects / create_project 等工具了。' }],
        };
      } catch (e: any) {
        return { content: [{ type: 'text', text: `授权失败：${e?.message || e}` }], isError: true };
      }
    }

    if (name === 'kfile_logout') {
      disconnectRemote();
      clearToken();
      return { content: [{ type: 'text', text: '已退出登录并清除令牌。需要重新使用时请调用 kfile_login。' }] };
    }

    // Any other tool: requires an active connection.
    if (!remoteClient) {
      return {
        content: [{ type: 'text', text: '未登录。请先调用 kfile_login 工具完成授权后再使用此工具。' }],
        isError: true,
      };
    }
    const remoteName = resolveRemoteToolName(name, remoteToolNames);
    if (!remoteName) {
      return {
        content: [{ type: 'text', text: `未知工具：${name}。远端可用工具：${Array.from(remoteToolNames).join(', ')}` }],
        isError: true,
      };
    }

    // Forward to the remote k-File server (source of truth).
    const result = await remoteClient.callTool({
      name: remoteName,
      arguments: injectAccessToken(args, activeToken),
    });
    console.error(`[kfile-mcp-bridge] 工具 ${remoteName} 返回: ${summarizeResult(result)}`);
    return normalizeToolResult(result);
  });

  // Wire up stdio. All tools are listed immediately.
  const stdio = new StdioServerTransport();
  await server.connect(stdio);

  // If a token is already stored, auto-connect silently.
  const existing = loadToken(host);
  if (existing) {
    try {
      await connectRemote(existing);
    } catch (e: any) {
      console.error(`[kfile-mcp-bridge] 已存令牌连接失败（可能已过期）：${e?.message || e}`);
      console.error('[kfile-mcp-bridge] 请调用 kfile_login 重新授权。');
    }
  } else {
    console.error('[kfile-mcp-bridge] 未授权。所有工具已列出，调用真实工具前请先 kfile_login。');
  }
}

function injectAccessToken(args: unknown, token: string | null): Record<string, unknown> {
  const out = (args && typeof args === 'object' && !Array.isArray(args))
    ? { ...(args as Record<string, unknown>) }
    : {};
  if (token) {
    out.__kfile_access_token = token;
  }
  return out;
}

function resolveRemoteToolName(name: string, remoteToolNames: Set<string>): string | null {
  const unprefixed = name.replace(/^mcp__[^_]+__/, '');
  if (remoteToolNames.size === 0) {
    return unprefixed;
  }
  if (remoteToolNames.has(name)) {
    return name;
  }
  if (remoteToolNames.has(unprefixed)) {
    return unprefixed;
  }
  return null;
}

function normalizeToolResult(result: any): any {
  const content = Array.isArray(result?.content) ? result.content : [];
  if (content.length > 0) {
    return result;
  }
  if (result?.structuredContent !== undefined) {
    return {
      content: [{ type: 'text', text: JSON.stringify(result.structuredContent, null, 2) }],
      structuredContent: result.structuredContent,
      isError: Boolean(result?.isError),
    };
  }
  if (result !== undefined) {
    return {
      content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
      isError: Boolean(result?.isError),
    };
  }
  return { content: [{ type: 'text', text: 'null' }] };
}

function summarizeResult(result: any): string {
  try {
    const text = JSON.stringify(result);
    return text.length > 800 ? `${text.slice(0, 800)}...` : text;
  } catch {
    return String(result);
  }
}

/**
 * fetch wrapper that injects the Authorization header, for the SSE handshake.
 */
function authedFetch(headers: Record<string, string>) {
  return async (input: any, init?: any) => {
    const merged = { ...(init || {}), headers: { ...(init?.headers || {}), ...headers } };
    return globalThis.fetch(input, merged);
  };
}
