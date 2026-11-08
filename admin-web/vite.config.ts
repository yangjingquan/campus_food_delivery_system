import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  return {
    plugins: [vue()],
    server: {
      port: 5190,
      strictPort: true,
      proxy: { '/api': { target: env.VITE_PROXY_TARGET || 'http://localhost:8082', changeOrigin: true } },
    },
  }
})
