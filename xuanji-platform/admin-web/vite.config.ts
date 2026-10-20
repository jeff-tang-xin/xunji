import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 构建产物直接输出到 server 的 static（Maven frontend-maven-plugin 在
// generate-resources 阶段调用 npm run build，单 jar 部署形态不变）。
// emptyOutDir 清空 static——旧静态页已备份至 legacy-frontend-backup/。
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    // 2026-10-20：直写 static 会撞 GraalPy 运行时句柄泄漏的 delete-pending 锁
    // （run_python open() 读过 bundle 后文件永久 EPERM），改为先出 dist 再由构建脚本拷入 static
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
