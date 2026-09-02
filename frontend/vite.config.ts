import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Docker Compose에서는 브라우저가 /api를 Vite 개발 서버로 보내고,
// Vite가 Compose 내부 DNS 이름(core-api)으로 프록시한다.
// 네이티브 개발의 기본 대상은 기존 localhost:8080을 유지한다.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const usePolling = env.CHOKIDAR_USEPOLLING === 'true'

  return {
    plugins: [react(), tailwindcss()],
    server: {
      host: '0.0.0.0',
      port: 5173,
      strictPort: true,
      watch: usePolling ? { usePolling: true, interval: 300 } : undefined,
      proxy: {
        '/api': {
          target: env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
