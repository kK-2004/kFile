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
      // MCP SSE 传输：/mcp/sse 与 /mcp/messages 须直通后端（vite dev proxy 默认不缓冲，适配 SSE 长连接）
      '/mcp': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:9000',
        changeOrigin: true
      }
    }
  }
})

