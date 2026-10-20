// 构建产物拷贝：dist → xuanji-server static（单 jar 部署形态不变）。
// 背景：2026-10-20 vite 直写 static 撞上 GraalPy 运行时句柄泄漏的 delete-pending 锁
// （run_python open() 读过 bundle 后文件永久 EPERM，rename/rmdir 均失败），
// 故 vite outDir 改为 dist，由此脚本负责拷入 static。
// 旧 assets 清不掉时（文件被锁）跳过——残留旧 bundle 无害，index.html 只引用新 hash。
import { rmSync, cpSync } from 'node:fs'

const target = '../xuanji-server/src/main/resources/static'
try {
  rmSync(target + '/assets', { recursive: true, force: true })
} catch {
  console.warn('[copy-dist] 旧 assets 清理失败（文件被锁），残留旧 bundle 无害')
}
cpSync('dist', target, { recursive: true })
console.log('[copy-dist] static updated from dist/')
