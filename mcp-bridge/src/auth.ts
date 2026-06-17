/**
 * Token management & first-time OAuth-style authorization for k-File MCP.
 *
 * Flow:
 * 1. Try to read token from KFILE_TOKEN env or the token file.
 * 2. If no token: start a local HTTP callback server, open the browser to the
 *    k-File authorization page, receive the token via ?token=, persist it.
 *
 * k-File's authorization is non-standard OAuth: the server returns the plaintext
 * token directly as a `?token=` query param on the redirect_uri (no code exchange).
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { URL } from 'node:url';
import open from 'open';

export interface StoredToken {
  token: string;
  host: string;
  savedAt: number;
}

export function defaultTokenFile(): string {
  return process.env.KFILE_TOKEN_FILE || path.join(os.homedir(), '.kfile-mcp', 'token.json');
}

/** Read a previously stored token; returns null if missing/invalid. */
export function loadToken(host: string): string | null {
  // Manual override wins.
  if (process.env.KFILE_TOKEN && process.env.KFILE_TOKEN.trim()) {
    return process.env.KFILE_TOKEN.trim();
  }
  const file = defaultTokenFile();
  try {
    const raw = fs.readFileSync(file, 'utf8');
    const data = JSON.parse(raw) as StoredToken;
    if (data && data.token) return data.token;
  } catch {
    /* no token yet */
  }
  return null;
}

/** Persist token to file (mode 0600). */
export function saveToken(token: string, host: string): void {
  const file = defaultTokenFile();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const payload: StoredToken = { token, host, savedAt: Date.now() };
  fs.writeFileSync(file, JSON.stringify(payload, null, 2), { mode: 0o600 });
}

/**
 * Ensure we have a token. If none is stored, run the authorization flow:
 * start a local callback server, open browser to k-File authorize page,
 * wait for the ?token= callback, persist, return the token.
 *
 * @param host k-File base URL (defaults to https://file.ksite.xin in the CLI)
 */
export async function ensureToken(host: string): Promise<string> {
  const existing = loadToken(host);
  if (existing) return existing;

  return runAuthorizationFlow(host);
}

/**
 * Run the OAuth-style web authorization flow: local callback server + browser.
 * Returns the plaintext token once the user authorizes in the browser.
 * Exported so the bridge's kfile_login tool can invoke it on-demand.
 *
 * @param host k-File SSE/API backend host (token saved against this)
 * @param authHost host serving the authorization page (frontend).
 *                 In dev this differs from `host` (e.g. 5174 vs 9000);
 *                 in production they are the same. Defaults to `host`.
 */
export function runAuthorizationFlow(host: string, authHost: string = host): Promise<string> {
  return new Promise((resolve, reject) => {
    const preferredPort = process.env.KFILE_CALLBACK_PORT ? parseInt(process.env.KFILE_CALLBACK_PORT, 10) : 0;
    const server = http.createServer((req, res) => {
      try {
        const url = new URL(req.url || '/', `http://127.0.0.1`);
        const token = url.searchParams.get('token');
        if (!token) {
          res.writeHead(400, { 'Content-Type': 'text/html; charset=utf-8' });
          res.end(renderPage('error', '授权失败', '回调缺少 token 参数，请重新发起授权。'));
          return;
        }
        saveToken(token, host);
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(renderPage('success', '授权成功', '令牌已安全保存，有效期 6 个月。现在可以关闭此页面并返回 agent 继续操作。'));
        server.close();
        // Log to stderr so it doesn't corrupt stdio JSON-RPC.
        console.error('[kfile-mcp-bridge] 授权成功，令牌已保存。');
        resolve(token);
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(renderPage('error', '服务器错误', '处理授权回调时发生内部错误，请重试。'));
      }
    });

    server.on('error', (err) => {
      reject(new Error(`无法启动本地回调服务: ${err.message}`));
    });

    server.listen(preferredPort, '127.0.0.1', () => {
      const addr = server.address();
      const port = typeof addr === 'object' && addr ? addr.port : preferredPort;
      const redirectUri = `http://127.0.0.1:${port}/callback`;
      // 授权页是前端路由，用 authHost（开发环境前端 5174；生产同域）
      const authUrl = `${authHost.replace(/\/$/, '')}/admin/mcp/authorize?redirect_uri=${encodeURIComponent(redirectUri)}`;

      console.error(`[kfile-mcp-bridge] 首次使用，请在浏览器中完成授权：`);
      console.error(`[kfile-mcp-bridge]   ${authUrl}`);

      open(authUrl).catch(() => {
        // No GUI / open failed: user must copy the URL manually (already printed above).
        console.error('[kfile-mcp-bridge] 无法自动打开浏览器，请手动复制上方链接到浏览器打开。');
      });
    });

    // Safety timeout (5 min): avoid hanging forever if user never authorizes.
    setTimeout(() => {
      try { server.close(); } catch {}
      reject(new Error('授权超时（5 分钟内未完成）。请重新运行。'));
    }, 5 * 60 * 1000);
  });
}

/** Delete the stored token file (forces re-authorization next run). */
export function clearToken(): void {
  const file = defaultTokenFile();
  try { fs.unlinkSync(file); } catch { /* ignore */ }
}

/**
 * Render a clean, styled result page for the authorization callback.
 * type: 'success' | 'error'
 */
function renderPage(type: 'success' | 'error', title: string, message: string): string {
  const isSuccess = type === 'success';
  const color = isSuccess ? '#10a37f' : '#ef4444';
  const bgTint = isSuccess ? '#f0fdf4' : '#fef2f2';
  const border = isSuccess ? '#bbf7d0' : '#fecaca';
  // SVG icon: check or cross
  const icon = isSuccess
    ? '<path d="M20 6L9 17l-5-5"/>'
    : '<path d="M18 6L6 18M6 6l12 12"/>';
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} - k-File MCP</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    background: #f7f7f8;
    color: #202123;
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
    padding: 20px;
  }
  .card {
    background: #fff;
    border-radius: 16px;
    box-shadow: 0 4px 24px rgba(0,0,0,0.06);
    max-width: 440px;
    width: 100%;
    overflow: hidden;
    text-align: center;
  }
  .icon-wrap {
    width: 72px;
    height: 72px;
    margin: 40px auto 0;
    border-radius: 50%;
    background: ${bgTint};
    border: 2px solid ${border};
    display: flex;
    align-items: center;
    justify-content: center;
  }
  svg { width: 36px; height: 36px; stroke: ${color}; stroke-width: 2.5; fill: none; stroke-linecap: round; stroke-linejoin: round; }
  h1 { font-size: 22px; font-weight: 600; margin: 20px 0 8px; color: ${color}; }
  p { font-size: 14px; line-height: 1.6; color: #6e6e80; padding: 0 32px; margin-bottom: 28px; }
  .brand { font-size: 12px; color: #b4b4b4; padding: 0 0 28px; letter-spacing: 0.5px; }
</style>
</head>
<body>
  <div class="card">
    <div class="icon-wrap"><svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">${icon}</svg></div>
    <h1>${title}</h1>
    <p>${message}</p>
    <div class="brand">k-File MCP Bridge</div>
  </div>
</body>
</html>`;
}
