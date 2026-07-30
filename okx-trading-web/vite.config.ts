import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE 长连接：避免开发代理过早超时
        timeout: 0,
        proxyTimeout: 0
      }
    }
  },
  optimizeDeps: {
    include: [
      'markdown-it',
      '@wangeditor/editor',
      '@wangeditor/editor-for-vue',
      'docx-preview',
      'xlsx'
    ]
  },
  css: {
    preprocessorOptions: {
      scss: {
        additionalData: `@import "@/assets/styles/variables.scss";`
      }
    }
  }
})
