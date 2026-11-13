import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// FE-060固定已接受BE-061平台；会话及业务请求始终同源。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5175,
    strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:18460' },
  },
})
