/**
 * stdio ↔ SSE bridge for k-File MCP, with in-protocol authorization.
 *
 * Design:
 *   - A server-level "instructions" string explains the whole MCP's purpose
 *     and the login flow ONCE, so tool descriptions stay concise (no repeat).
 *   - ALL tools (5 k-File tools + kfile_login + kfile_logout) are listed from
 *     startup; no dynamic discovery. Calling a real tool before login returns
 *     "请先 kfile_login".
 *   - kfile_login runs the OAuth browser flow + connects SSE.
 *   - kfile_logout deletes the token + disconnects.
 *
 * The k-File tool metadata (names/descriptions/schemas) is a static manifest;
 * the actual call is forwarded to the remote SSE server (source of truth).
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
  'k-File 是一个文件收集系统：管理员创建"项目"定义收集规则（字段、文件类型、截止时间等），用户按规则提交文件，管理员可查询谁还没提交。',
  '',
  '可用能力：创建项目（支持套用模板）、查看有权限的项目、查询某项目未提交名单、向用户提问让其选项选择。',
  '',
  '鉴权：首次使用必须先调用 kfile_login 工具登录（会打开浏览器让用户授权）；之后即可调用其他工具。用完可用 kfile_logout 退出。',
  '权限由授权账号的角色决定：SUPER 可创建项目、看全部项目；ADMIN 只能看被分配给自己的项目、不能创建。',
  '',
  '重要约定：凡需用户在确定选项中选择（选模板/选项目/开关取值），优先调用 ask_user_choice 让用户选，而非让用户在输入框手输。',
  '创建项目第一步必须先调用 ask_user_choice 询问“是否使用模板”，并把结果作为 useTemplate 传给 create_project。',
  '用户选择使用模板后，必须展示模板列表让用户选择模板，不要让用户手填 templateId；选了模板后开关字段（allowResubmit 等）继承模板值，不要再问用户。useTemplate=false 时每个开关用 ask_user_choice(是/否) 询问、不读默认值。',
  '创建项目必须先预览再确认：先调用 create_project 且不传 confirmed 或 confirmed=false，展示预览和 confirmationToken 后用 ask_user_choice 让用户确认/修改；用户确认后再以 confirmed=true 和原 confirmationToken 调用创建。',
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

/** Tools provided by the k-File backend (static manifest). */
const KFILE_TOOLS = [
  {
    name: 'list_my_templates',
    description:
      '列出当前用户有权限使用的项目模板，含可复用字段。用于 create_project 前选定 templateId。建议用 ask_user_choice 让用户选。',
    inputSchema: { type: 'object' as const, properties: {}, required: [] as string[] },
  },
  {
    name: 'create_project',
    description:
      '创建项目。第一步必须先用 ask_user_choice 问用户是否使用模板，并传 useTemplate。useTemplate=true 且未传 templateId 时会返回模板 options，必须用 ask_user_choice 展示给用户选择，不要让用户手填 templateId；useTemplate=false 时手填创建。必须先预览再确认：首次不传 confirmed 或 confirmed=false，只返回预览和 confirmationToken 不创建；展示给用户并让用户确认/修改，用户确认后再以 confirmed=true 和原 confirmationToken 调用才创建。如果参数修改，必须重新预览获取新 token。创建成功返回 submitUrl。仅 SUPER 可创建。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        name: { type: 'string', description: '项目名称（必填）' },
        useTemplate: { type: 'boolean', description: '是否使用模板。创建项目第一步必须先问用户，并传入 true/false。' },
        templateId: { type: 'number', description: '模板 ID。useTemplate=true 时必填，来自用户选择的模板。' },
        startAt: { type: 'number', description: '开始时间 epoch 毫秒（可选）' },
        endAt: { type: 'number', description: '截止时间 epoch 毫秒（可选）' },
        fileSizeLimitBytes: { type: 'number', description: '单文件大小上限字节（可选，null=不限）' },
        allowedFileTypes: { type: 'array', items: { type: 'string' }, description: '允许的文件扩展名，如 ["pdf","zip"]（可选）' },
        allowResubmit: { type: 'boolean', description: '可复用覆盖：是否允许重复提交（可选）' },
        allowMultiFiles: { type: 'boolean', description: '可复用覆盖：是否允许多文件（可选）' },
        allowOverdue: { type: 'boolean', description: '可复用覆盖：是否允许逾期提交（可选）' },
        expectedUserFieldsJson: { type: 'string', description: '可复用覆盖：期望字段配置 JSON（可选）' },
        pathFieldKey: { type: 'string', description: '可复用覆盖：上传路径字段 key（可选）' },
        pathSegmentsJson: { type: 'string', description: '可复用覆盖：上传路径层级 JSON 数组（可选）' },
        userSubmitStatusType: { type: 'string', description: '可复用覆盖：状态提示类型 info/warning/success/danger（可选）' },
        userSubmitStatusText: { type: 'string', description: '可复用覆盖：状态提示文案（可选）' },
        queryFieldKey: { type: 'string', description: '可复用覆盖：查询主键字段（可选）' },
        allowedSubmitterKeysJson: { type: 'string', description: '可复用覆盖：允许提交者字段 key JSON 数组（可选）' },
        allowedSubmitterListJson: { type: 'string', description: '可复用覆盖：允许提交名单 JSON（可选）' },
        autoFileNamingEnabled: { type: 'boolean', description: '可复用覆盖：是否开启自动命名（可选）' },
        autoFileNamingConfigJson: { type: 'string', description: '可复用覆盖：自动命名配置 JSON（可选）' },
        confirmed: { type: 'boolean', description: '用户是否已确认创建。false/不传=只预览；true=确认后真正创建。' },
        confirmationToken: { type: 'string', description: '预览返回的确认令牌。confirmed=true 时必须提供，且参数不能与预览时不同。' },
      },
      required: ['name'] as string[],
    },
  },
  {
    name: 'list_my_projects',
    description:
      '列出当前用户有权限的项目（SUPER 全部；ADMIN 仅被分配的）。用于后续操作（如查询未提交者）前选定 projectId。建议用 ask_user_choice 让用户选。',
    inputSchema: { type: 'object' as const, properties: {}, required: [] as string[] },
  },
  {
    name: 'get_project_info',
    description:
      '获取某个项目详情，并返回用户填写链接 submitUrl。需项目管理权限。建议先用 list_my_projects 让用户选 projectId。',
    inputSchema: {
      type: 'object' as const,
      properties: { projectId: { type: 'number', description: '项目 ID' } },
      required: ['projectId'] as string[],
    },
  },
  {
    name: 'list_missing_submitters',
    description:
      '查询某项目尚未提交的允许提交者名单。需对该项目有管理权限（SUPER 或被分配的 ADMIN）。建议先用 list_my_projects + ask_user_choice 让用户选定 projectId。',
    inputSchema: {
      type: 'object' as const,
      properties: { projectId: { type: 'number', description: '项目 ID' } },
      required: ['projectId'] as string[],
    },
  },
  {
    name: 'create_archive_download_link',
    description:
      '为项目生成打包下载链接。复用后台打包分享逻辑：生成最新有效提交文件的预签名清单并创建 /share?s=... 下载页，访问链接即可打包下载。可选 fieldKey/fieldValue 按提交者字段前缀过滤，expireSeconds 默认 3600。需项目管理权限。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        projectId: { type: 'number', description: '项目 ID' },
        fieldKey: { type: 'string', description: '可选：按提交者字段过滤的字段 key' },
        fieldValue: { type: 'string', description: '可选：按提交者字段过滤的字段值前缀' },
        expireSeconds: { type: 'number', description: '可选：链接有效期秒数，默认 3600' },
      },
      required: ['projectId'] as string[],
    },
  },
  {
    name: 'ask_user_choice',
    description:
      '向用户提问让其从选项中选择，返回所选 value。凡需用户在确定选项中选择（选模板/选项目/开关取值）时优先用本工具，而非让用户在输入框手输。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        prompt: { type: 'string', description: '提问说明/标题，向用户清晰呈现要选什么' },
        options: {
          type: 'array',
          description: '选项数组，每项 {value, label}',
          items: { type: 'object', properties: { value: {}, label: { type: 'string' } } },
        },
      },
      required: ['prompt', 'options'] as string[],
    },
  },
];

const ALL_TOOLS = [LOGIN_TOOL, LOGOUT_TOOL, ...KFILE_TOOLS];

export async function runBridge(opts: BridgeOptions): Promise<void> {
  const { host } = opts;
  const authHost = (process.env.KFILE_AUTH_HOST || host).trim();

  const server = new Server(
    { name: 'kfile-mcp-bridge', version: '0.1.0' },
    { capabilities: { tools: {} }, instructions: INSTRUCTIONS },
  );

  let remoteClient: Client | null = null;
  let remoteToolNames = new Set<string>();
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
      { name: 'kfile-mcp-bridge', version: '0.1.0' },
      { capabilities: {} },
    );
    await client.connect(sseTransport);
    remoteClient = client;
    activeToken = token;
    try {
      const listed = await client.listTools();
      remoteToolNames = new Set((listed.tools || []).map(tool => tool.name));
      if (remoteToolNames.size === 0) {
        remoteToolNames = new Set(KFILE_TOOLS.map(tool => tool.name));
        console.error('[kfile-mcp-bridge] 远端工具列表为空，将使用本地清单。');
      } else {
        console.error(`[kfile-mcp-bridge] 远端工具: ${Array.from(remoteToolNames).join(', ')}`);
      }
    } catch (e: any) {
      remoteToolNames = new Set(KFILE_TOOLS.map(tool => tool.name));
      console.error(`[kfile-mcp-bridge] 远端工具列表读取失败，将使用本地清单: ${e?.message || e}`);
    }
    console.error('[kfile-mcp-bridge] 已连接 k-File SSE，工具可用。');
  }

  /** Disconnect (logout). */
  function disconnectRemote(): void {
    if (remoteClient) {
      try { remoteClient.close(); } catch {}
      remoteClient = null;
      remoteToolNames = new Set();
      activeToken = null;
    }
  }

  // tools/list: ALWAYS return the full set, regardless of auth state.
  server.setRequestHandler(ListToolsRequestSchema, async () => {
    return { tools: ALL_TOOLS };
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
