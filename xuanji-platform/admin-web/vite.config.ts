import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 构建产物直接输出到 server 的 static（Maven frontend-maven-plugin 在
// generate-resources 阶段调用 npm run build，单 jar 部署形态不变）。
// emptyOutDir 清空 static——旧静态页已备份至 legacy-frontend-backup/。
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: '../xuanji-server/src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
