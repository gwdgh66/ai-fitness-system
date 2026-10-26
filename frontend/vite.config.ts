import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    watch: {
      // 忽略编辑器 / 代码工具写入的临时文件与临时目录（例如 `.types.ts.<pid>.<uuid>.tmpdir/`）。
      // 否则 chokidar 会在这些转瞬即逝的路径上抛 EBUSY，而且是致命错误会让 dev server 直接退出。
      ignored: ['**/.*.tmpdir/**', '**/*.tmp', '**/.tmp/**', '**/.npm-cache/**'],
    },
    // 后端 Spring Boot 跑在 8080；SSE（/api/ai/chat/stream）走同一代理即可
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        manualChunks: {
          echarts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers'],
        },
      },
    },
  },
})