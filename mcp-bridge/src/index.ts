#!/usr/bin/env node
/**
 * kfile-mcp-bridge entry point.
 *
 * Starts immediately (no blocking on auth). Exposes a `kfile_login` tool the
 * agent can call to authorize via browser. If a token is already stored, it
 * auto-connects silently.
 *
 * Config (env vars):
 *   KFILE_HOST          (optional) k-File base URL, default https://file.ksite.xin
 *   KFILE_TOKEN         (optional) use this token directly (skip kfile_login)
 *   KFILE_TOKEN_FILE    (optional) token file path, default ~/.kfile-mcp/token.json
 *   KFILE_CALLBACK_PORT (optional) local callback port, default random
 */
import { runBridge } from './bridge.js';
import { saveToken } from './auth.js';

const DEFAULT_KFILE_HOST = 'https://file.ksite.xin';

function fail(msg: string): never {
  console.error(`[kfile-mcp-bridge] 错误：${msg}`);
  process.exit(1);
}

async function main(): Promise<void> {
  const host = (process.env.KFILE_HOST || DEFAULT_KFILE_HOST).trim().replace(/\/$/, '');
  if (!/^https?:\/\//i.test(host)) {
    fail(`KFILE_HOST 必须是 http(s) URL，收到: ${host}`);
  }

  // If KFILE_TOKEN is set, persist it so the bridge auto-connects on startup.
  if (process.env.KFILE_TOKEN && process.env.KFILE_TOKEN.trim()) {
    saveToken(process.env.KFILE_TOKEN.trim(), host);
  }

  try {
    // Blocks: serves stdio until the client disconnects.
    await runBridge({ host });
  } catch (e: any) {
    const msg = e?.message || String(e);
    fail(`桥接失败：${msg}`);
  }
}

main();
