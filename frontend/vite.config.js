import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  base: '/',
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:9000',
        changeOrigin: true
      },
      // 稳定的 CDN 预览链接及非直连存储源代理到后端，保证 localhost:5174 可直接打开。
      '/file': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:9000',
        changeOrigin: true
      },
      // MCP Streamable HTTP 传输：/mcp 单端点须直通后端，不缓冲流式响应，
      // 并透传 Authorization / WWW-Authenticate 头（OAuth bearer 在 HTTP 边界鉴权）
      '/mcp': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:9000',
        changeOrigin: true
      },
      // OAuth endpoints（authorize/token/register/revoke）与元数据发现
      '/oauth2': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:9000',
        changeOrigin: true
      },
      '/.well-known': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:9000',
        changeOrigin: true
      }
    }
  }
})
