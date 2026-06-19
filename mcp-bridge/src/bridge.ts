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
 * 工具清单在本地维护完整定义（name/description/inputSchema）。
 *
 * 为什么不用“登录后转发后端 listTools()”：部分客户端（如 workbuddy）采用延迟工具
 * 发现，启动时一次性索引工具定义并缓存，登录后不会因 list_changed 通知或登录状态变化
 * 重新拉取 tools/list。若 tools/list 返回空 schema 占位，agent 永远拿不到真实参数定义，
 * 调用时报 “must NOT have additional properties”。故本地必须维护完整 schema。
 *
 * 后端是工具【执行】的真相源（tools/call 仍转发后端），但工具【定义】本地维护。
 * 维护成本：后端新增/修改工具时，KFILE_TOOLS 同步加一份定义（与 @Tool/@ToolParam 对齐）。
 */
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
      '创建项目。第一步必须先用 ask_user_choice 问用户是否使用模板，并传 useTemplate。useTemplate=true 且未传 templateId 时会返回模板 options，必须用 ask_user_choice 展示给用户选择，不要让用户手填 templateId；useTemplate=false 时手填创建。必须先预览再确认：首次不传 confirmed 或 confirmed=false，只返回预览和 confirmationToken 不创建；展示给用户并让用户确认/修改，用户确认后再以 confirmed=true 和原 confirmationToken 调用才创建。如果参数修改，必须重新预览获取新 token。创建成功返回 submitUrl。ADMIN 和 SUPER 可创建。',
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
      '列出当前用户有权限的项目（SUPER 全部；ADMIN 仅被分配的）。用于后续操作（如查询未提交者/实际提交名单）前选定 projectId。建议用 ask_user_choice 让用户选。',
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
      '查询某项目【尚未提交】的允许提交者名单。【仅适用于已配置允许提交名单（allowedSubmitterKeys/List）的项目】：系统拿名单与已提交记录比对算出谁还没交；未配置名单时返回 {enabled:false}，此时无法判断未提交者。要看实际已提交情况（人数/名单/文件）请改用 list_project_submissions。需对该项目有管理权限（SUPER 或被分配的 ADMIN）。建议先用 list_my_projects + ask_user_choice 让用户选定 projectId。',
    inputSchema: {
      type: 'object' as const,
      properties: { projectId: { type: 'number', description: '项目 ID' } },
      required: ['projectId'] as string[],
    },
  },
  {
    name: 'list_project_submissions',
    description:
      '列出某项目的实际提交名单：按提交者去重（每人仅保留最新一次有效提交），返回每个提交者的 submitterInfo、文件名与每个文件的 OSS 实际大小（字节）。用于回答“有多少人提交了/谁提交了”，适用于所有项目，不依赖项目是否配置允许提交名单。与 list_missing_submitters 分工：本工具看实际已提交情况；list_missing_submitters 仅适用于已配置名单的项目用于查未提交者。可选 fieldKey/fieldValue 按提交者字段前缀过滤。需对该项目有管理权限（SUPER 或被分配的 ADMIN）。不带下载链接，需打包下载请改用 create_archive_download_link。建议先用 list_my_projects + ask_user_choice 让用户选定 projectId。',
    inputSchema: {
      type: 'object' as const,
      properties: {
        projectId: { type: 'number', description: '项目 ID' },
        fieldKey: { type: 'string', description: '可选：按提交者字段过滤的字段 key（如 queryFieldKey）' },
        fieldValue: { type: 'string', description: '可选：按提交者字段过滤的字段值前缀' },
      },
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

/** 全量工具清单（login/logout + 业务工具），tools/list 始终返回它，不随登录状态变化。 */
const ALL_TOOLS = [LOGIN_TOOL, LOGOUT_TOOL, ...KFILE_TOOLS];

export async function runBridge(opts: BridgeOptions): Promise<void> {
  const { host } = opts;
  const authHost = (process.env.KFILE_AUTH_HOST || host).trim();

  const server = new Server(
    { name: 'kfile-mcp-bridge', version: '0.2.5' },
    { capabilities: { tools: {} }, instructions: INSTRUCTIONS },
  );

  let remoteClient: Client | null = null;
  let remoteToolNames = new Set<string>();
  let activeToken: string | null = null;
  // 连接状态：避免断线回调与外部重连并发触发多次重连。
  // 'idle' 未连接 / 'connecting' 连接或重连进行中 / 'connected' 已连接
  let connState: 'idle' | 'connecting' | 'connected' = 'idle';
  // 主动 disconnect（logout）时置 true，阻止 onclose 触发自动重连。
  let intentionalClose = false;
  // SSE 心跳保活：MCP ping 用既有连接，探测连接是否还活着。
  let keepaliveTimer: ReturnType<typeof setInterval> | null = null;


  /**
   * 用 token 建立到 k-File 的 SSE 连接。
   * 连接成功后注册断线自动重连：SSE 长连接会被代理空闲超时/NAT 回收静默杀掉，
   * SDK 无内置重连；这里在 transport onclose/onerror 时用缓存的 activeToken 重连。
   * token 是无状态长效的（每次请求查库），可重复用于重连。
   */
  async function connectRemote(token: string): Promise<void> {
    if (connState === 'connecting') return;        // 已有重连在进行，直接返回
    connState = 'connecting';
    intentionalClose = false;
    const sseUrl = new URL(`${host.replace(/\/$/, '')}/mcp/sse`);
    const authHeaders = { Authorization: `Bearer ${token}` };
    const sseTransport = new SSEClientTransport(sseUrl, {
      eventSourceInit: { fetch: authedFetch(authHeaders) } as any,
      requestInit: { headers: authHeaders },
    });
    // transport 断开时自动重连（除非是主动 logout）。
    sseTransport.onclose = () => {
      console.error('[kfile-mcp-bridge] SSE 连接已关闭。');
      handleDisconnect('onclose');
    };
    sseTransport.onerror = (err: any) => {
      console.error(`[kfile-mcp-bridge] SSE 连接错误：${err?.message || err}`);
      handleDisconnect('onerror');
    };
    const client = new Client(
      { name: 'kfile-mcp-bridge', version: '0.2.5' },
      { capabilities: {} },
    );
    try {
      await client.connect(sseTransport);
    } catch (e: any) {
      connState = 'idle';
      // SSE 握手 401 = token 已失效（吊销/过期/无效，后端统一返回 TOKEN_INVALID）。
      // 此时本地 token 是废的：清除它 + 置空 activeToken + 不重连（重连也只会再 401）。
      // 与网络抖动等可重试错误区分开。
      if (isTokenInvalidError(e)) {
        console.error('[kfile-mcp-bridge] token 已失效（吊销/过期/无效），清除本地 token，需重新授权。');
        clearToken();
        activeToken = null;
        throw new Error('令牌已被吊销或失效。请重新调用 kfile_login 完成授权。');
      }
      // 其他错误（网络/5xx）：不清 token，抛出让调用方决定是否重试。
      throw e;
    }
    remoteClient = client;
    activeToken = token;
    connState = 'connected';
    // 仅用于 tools/call 的路由校验（确认后端确实暴露了该工具）。
    // tools/list 始终返回本地 ALL_TOOLS，不依赖后端清单——见 KFILE_TOOLS 说明。
    try {
      const listed = await client.listTools();
      remoteToolNames = new Set((listed.tools || []).map(tool => tool.name));
      if (remoteToolNames.size === 0) {
        console.error('[kfile-mcp-bridge] 远端工具列表为空。');
      } else {
        console.error(`[kfile-mcp-bridge] 远端工具: ${Array.from(remoteToolNames).join(', ')}`);
      }
    } catch (e: any) {
      remoteToolNames = new Set();
      console.error(`[kfile-mcp-bridge] 远端工具列表读取失败: ${e?.message || e}`);
    }
    startKeepalive();
    console.error('[kfile-mcp-bridge] 已连接 k-File SSE，工具可用。');
  }

  /**
   * 连接断开时清理状态；非主动断开则用 activeToken 自动重连（指数退避）。
   */
  function handleDisconnect(_reason: string): void {
    stopKeepalive();
    if (remoteClient) {
      try { remoteClient.close(); } catch {}
    }
    remoteClient = null;
    connState = 'idle';
    if (intentionalClose) return;         // 主动 logout，不重连
    if (!activeToken) return;             // 无 token，无法重连
    // 异步重连，不阻塞回调。
    reconnectWithBackoff(activeToken, 0);
  }

  /** 带指数退避的自动重连：1s → 2s → 4s → ... 上限 30s。 */
  function reconnectWithBackoff(token: string, attempt: number): void {
    if (intentionalClose || connState === 'connected' || connState === 'connecting') return;
    const delay = Math.min(1000 * 2 ** attempt, 30000);
    console.error(`[kfile-mcp-bridge] ${delay}ms 后尝试重连（第 ${attempt + 1} 次）...`);
    setTimeout(async () => {
      if (intentionalClose || connState === 'connected') return;
      try {
        await connectRemote(token);
        console.error('[kfile-mcp-bridge] 重连成功。');
      } catch (e: any) {
        console.error(`[kfile-mcp-bridge] 重连失败：${e?.message || e}`);
        reconnectWithBackoff(token, attempt + 1);
      }
    }, delay);
  }

  /**
   * 调用工具前确保连接可用：已连接直接返回；断了且持有 token 则同步重连后再返回。
   * 避免工具调用因死连接挂起超时。
   */
  async function ensureConnected(): Promise<boolean> {
    if (connState === 'connected' && remoteClient) return true;
    if (!activeToken) return false;
    // 连接进行中：等待它完成（轮询，最多 ~10s）。connState 在 await 期间会被
    // connectRemote 回调改写，故用普通 string 比较避免 TS 控制流窄化误报。
    const state = (): string => connState;
    if (state() === 'connecting') {
      for (let i = 0; i < 50 && state() === 'connecting'; i++) {
        await new Promise(r => setTimeout(r, 200));
      }
      return state() === 'connected' && !!remoteClient;
    }
    // idle 且有 token：主动重连一次。
    try {
      await connectRemote(activeToken);
      return state() === 'connected' && !!remoteClient;
    } catch (e: any) {
      console.error(`[kfile-mcp-bridge] 调用前重连失败：${e?.message || e}`);
      return false;
    }
  }

  /** 启动 SSE 心跳保活：周期性 ping 探测连接，早发现静默断连。 */
  function startKeepalive(): void {
    stopKeepalive();
    keepaliveTimer = setInterval(async () => {
      if (connState !== 'connected' || !remoteClient) return;
      try {
        await remoteClient.ping();
      } catch (e: any) {
        console.error(`[kfile-mcp-bridge] keepalive ping 失败，连接可能已断：${e?.message || e}`);
        handleDisconnect('ping-failed');
      }
    }, 30000); // 每 30s 一次，低于常见代理空闲超时（60s）
  }

  function stopKeepalive(): void {
    if (keepaliveTimer) {
      clearInterval(keepaliveTimer);
      keepaliveTimer = null;
    }
  }

  /** Disconnect (logout). */
  function disconnectRemote(): void {
    intentionalClose = true; // 阻止 onclose 触发自动重连
    stopKeepalive();
    if (remoteClient) {
      try { remoteClient.close(); } catch {}
      remoteClient = null;
      remoteToolNames = new Set();
      activeToken = null;
      connState = 'idle';
    }
  }

  // tools/list: 始终返回本地全量清单（login/logout + 业务工具），不随登录状态变化。
  // 这样延迟工具发现的客户端（如 workbuddy）启动时就能拿到完整 schema，不依赖登录后刷新。
  server.setRequestHandler(ListToolsRequestSchema, async () => {
    return { tools: ALL_TOOLS };
  });

  // tools/call: route to login/logout or forward to remote.
  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args } = request.params;

    if (name === 'kfile_login') {
      if (connState === 'connected' && remoteClient) {
        return { content: [{ type: 'text', text: '已登录，无需重复授权。可直接调用其他工具。' }] };
      }
      try {
        // 关键：先清掉可能已失效（被吊销/过期）的本地 token，强制走浏览器授权重新签发。
        // 否则 loadToken 复用废 token → connectRemote 401 → 下次 login 又复用，陷入死循环。
        clearToken();
        const token = await runAuthorizationFlow(host, authHost);
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
    // 先确保连接可用——断了会自动用 activeToken 重连，避免工具调用挂起超时。
    const ok = await ensureConnected();
    if (!ok) {
      return {
        content: [{ type: 'text', text: '未登录或连接已断开。请先调用 kfile_login 工具完成授权后再使用此工具。' }],
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
    const result = await remoteClient!.callTool({
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

/**
 * 判断 SSE 握手错误是否为 token 失效（401）。
 * 后端 McpTokenAuthFilter 对 token 失效（吊销/过期/无效）统一返回 401。
 * SDK 抛 SseError，code 字段为 HTTP 状态码。
 */
function isTokenInvalidError(e: any): boolean {
  if (!e) return false;
  const code = (e as any).code;
  const msg = String((e as any)?.message || '');
  // SseError.code === 401，或错误信息含 401（兼容不同 SDK 版本/包装层）
  return code === 401 || msg.includes('401');
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
