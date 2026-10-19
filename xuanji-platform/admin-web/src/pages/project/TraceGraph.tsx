import { useMemo, useState } from 'react'
import { Typography } from 'antd'
import type { TraceStep } from './TracePanel'

/**
 * 需求交付关系图（neo4j 形态的简化版，2026-10-19 用户拍板替换泳道拓扑）：
 * - 每条需求一个聚簇：需求球居中，任务/用例/bug 围绕中心放射排布（圆环均分角度）
 * - 连线 = 交付关系（需求→任务/用例/bug、用例→bug 发现自、父需求→拆分需求）
 * - 节点球状态色 + 执行中脉冲；hover 聚簇高亮、其余淡化；点击节点球开详情
 * - 确定性坐标计算（无力导向模拟、无拖拽缩放），聚簇纵向堆叠
 * 纯 SVG 手绘，不引图库依赖。
 */

export interface TraceGraphData {
  stages: { stage: string; label: string; status: string }[]
  requirements: {
    code: string
    title: string
    status: string
    depends_on: string[]
    chain: TraceStep[]
    description?: string | null
    acceptance?: string | null
    source?: string | null
    parent_code?: string | null
  }[]
  unlinked: TraceStep[]
  test_cases?: { code: string; title: string; status: string; requirement_code: string | null; content?: string | null }[]
  bugs?: { code: string; title: string; severity: string; status: string; requirement_code: string | null; test_case_code: string | null; content?: string | null }[]
}

export type TestCaseItem = NonNullable<TraceGraphData['test_cases']>[number]
export type BugItem = NonNullable<TraceGraphData['bugs']>[number]

/** 步骤状态中文 */
const STATUS_TEXT: Record<string, string> = {
  DONE: '完成', FAILED: '失败', SKIPPED: '已跳过',
  EXECUTING: '执行中', WAITING: '等你答复', PLANNED: '待执行',
}
/** 用例状态中文 */
const CASE_TEXT: Record<string, string> = {
  ACTIVE: '待执行', PASSED: '通过', FAILED: '失败', BLOCKED: '阻塞',
}
/** bug 状态中文 */
const BUG_TEXT: Record<string, string> = {
  OPEN: '待修复', FIXING: '修复中', FIXED: '已修复', CLOSED: '已关闭', REJECTED: '误报',
}

/** 状态 → 节点填充色（步骤/需求沿用泳道版口径；用例/bug 同色系映射） */
const NODE_FILL: Record<string, string> = {
  DONE: '#22c55e', FAILED: '#ef4444', EXECUTING: '#3b82f6',
  WAITING: '#f59e0b', PLANNED: '#cbd5e1', SKIPPED: '#e2e8f0',
  ACTIVE: '#cbd5e1', PASSED: '#22c55e', BLOCKED: '#f59e0b',
  OPEN: '#ef4444', FIXING: '#f59e0b', FIXED: '#22c55e', CLOSED: '#94a3b8', REJECTED: '#e2e8f0',
}

function statusColor(s: string): string {
  return NODE_FILL[s] || '#cbd5e1'
}

/** 聚簇半径按子节点数自适应（子节点多则环大） */
function ringRadius(n: number): number {
  if (n <= 3) return 62
  if (n <= 6) return 84
  if (n <= 10) return 108
  return 128
}

interface RingNode {
  kind: 'task' | 'case' | 'bug'
  key: string
  label: string
  color: string
  live: boolean
  dashed?: boolean
  x: number
  y: number
  step?: TraceStep
  tc?: TestCaseItem
  bug?: BugItem
}

export function TraceGraph({ data, onOpenStep, onOpenReq, onOpenCase, onOpenBug }: {
  data: TraceGraphData
  onOpenStep: (s: TraceStep) => void
  onOpenReq: (r: TraceGraphData['requirements'][number]) => void
  onOpenCase: (c: TestCaseItem) => void
  onOpenBug: (b: BugItem) => void
}) {
  const [hover, setHover] = useState<string | null>(null)

  const layout = useMemo(() => {
    const cases = data.test_cases || []
    const bugs = data.bugs || []
    const byReq = <T extends { requirement_code: string | null }>(items: T[], code: string) =>
      items.filter((it) => it.requirement_code === code)

    const topReqs = data.requirements.filter((r) => !r.parent_code)
    const childReqs = new Map<string, typeof data.requirements>()
    for (const r of data.requirements) {
      if (r.parent_code) {
        const arr = childReqs.get(r.parent_code) || []
        arr.push(r)
        childReqs.set(r.parent_code, arr)
      }
    }

    const CLUSTER_GAP = 26
    let y = 16
    const clusters: {
      req: TraceGraphData['requirements'][number]
      cx: number
      cy: number
      r: number
      height: number
      children: { req: TraceGraphData['requirements'][number]; x: number; y: number }[]
      nodes: RingNode[]
    }[] = []

    for (const req of topReqs) {
      const kids = childReqs.get(req.code) || []
      // 环上节点：任务（按 seq）→ 用例 → bug，同类型聚在一起
      const ring: Omit<RingNode, 'x' | 'y'>[] = []
      for (const s of req.chain.slice().sort((a, b) => a.seq - b.seq)) {
        ring.push({
          kind: 'task', key: `${s.stage}-${s.iteration}-${s.seq}`, label: s.name || '',
          color: statusColor(s.status), live: s.status === 'EXECUTING' || s.status === 'WAITING', step: s,
        })
      }
      for (const c of byReq(cases, req.code)) {
        ring.push({ kind: 'case', key: c.code, label: `${c.code} ${c.title}`, color: statusColor(c.status), live: false, tc: c })
      }
      for (const b of byReq(bugs, req.code)) {
        ring.push({
          kind: 'bug', key: b.code, label: `${b.code}·${b.severity} ${b.title}`,
          color: statusColor(b.status), live: b.status === 'OPEN' || b.status === 'FIXING', dashed: true, bug: b,
        })
      }
      for (const k of kids) {
        for (const s of k.chain.slice().sort((a, b) => a.seq - b.seq)) {
          ring.push({
            kind: 'task', key: `${s.stage}-${s.iteration}-${s.seq}`, label: s.name || '',
            color: statusColor(s.status), live: s.status === 'EXECUTING' || s.status === 'WAITING', step: s,
          })
        }
        for (const c of byReq(cases, k.code)) {
          ring.push({ kind: 'case', key: c.code, label: `${c.code} ${c.title}`, color: statusColor(c.status), live: false, tc: c })
        }
        for (const b of byReq(bugs, k.code)) {
          ring.push({
            kind: 'bug', key: b.code, label: `${b.code}·${b.severity} ${b.title}`,
            color: statusColor(b.status), live: b.status === 'OPEN' || b.status === 'FIXING', dashed: true, bug: b,
          })
        }
      }

      const R = ringRadius(ring.length)
      const cx = 24 + R + 90 // 左侧留需求标签宽
      const cy = y + R + 30
      // 角度均分：从顶部 -90° 顺时针
      const placed: RingNode[] = ring.map((n, i) => {
        const angle = -Math.PI / 2 + (i * 2 * Math.PI) / Math.max(ring.length, 1)
        return { ...n, x: cx + R * Math.cos(angle), y: cy + R * Math.sin(angle) }
      })
      // 拆分需求球：中心右侧近距
      const children = kids.map((k, i) => ({ req: k, x: cx + 44, y: cy + 30 + i * 34 }))

      clusters.push({ req, cx, cy, r: R, height: 2 * R + 60, children, nodes: placed })
      y += 2 * R + 60 + CLUSTER_GAP
    }

    // 断链步骤（未声明需求关联）：末尾一排
    const unlinkedY = y + 8
    const unlinkedNodes = data.unlinked.map((s, i) => ({ s, x: 60 + i * 130, y: unlinkedY + 24 }))

    const maxCx = clusters.reduce((m, c) => Math.max(m, c.cx + c.r + 110), 420)
    return { clusters, unlinkedY, unlinkedNodes, totalW: maxCx, totalH: unlinkedY + (data.unlinked.length ? 70 : 16) }
  }, [data])

  const { clusters, unlinkedY, unlinkedNodes, totalW, totalH } = layout
  const dim = (key: string) => (hover === null ? 1 : hover === key ? 1 : 0.16)

  return (
    <div style={{ overflowX: 'auto' }}>
      <svg width={totalW} height={totalH} style={{ display: 'block', minWidth: '100%' }}>
        <defs>
          <filter id="glow" x="-60%" y="-60%" width="220%" height="220%">
            <feGaussianBlur stdDeviation="3" result="b" />
            <feMerge>
              <feMergeNode in="b" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        {clusters.map((cl) => {
          const key = cl.req.code
          const blocked = (cl.req.depends_on || []).some((d) => data.requirements.find((x) => x.code === d && x.status !== 'DONE'))
          return (
            <g key={key} opacity={dim(key)} style={{ transition: 'opacity .25s' }}
               onMouseEnter={() => setHover(key)} onMouseLeave={() => setHover(null)}>
              {blocked && <rect x={0} y={cl.cy - cl.r - 24} width={totalW} height={cl.height} fill="#fffbeb" opacity={0.5} rx={8} />}

              {/* 放射连线：需求 → 环上节点 */}
              {cl.nodes.map((n) => (
                <line key={`ln-${n.key}`} x1={cl.cx} y1={cl.cy} x2={n.x} y2={n.y}
                      stroke={hover === key ? '#a5b4fc' : '#e2e8f0'} strokeWidth={hover === key ? 1.4 : 1} />
              ))}
              {/* 用例 → bug（发现自）虚线 */}
              {cl.nodes.filter((n) => n.kind === 'bug' && n.bug?.test_case_code).map((n) => {
                const src = cl.nodes.find((m) => m.kind === 'case' && m.key === n.bug!.test_case_code)
                if (!src) return null
                return <line key={`cb-${n.key}`} x1={src.x} y1={src.y} x2={n.x} y2={n.y} stroke="#fecaca" strokeWidth={1} strokeDasharray="3 3" />
              })}
              {/* 父 → 拆分需求连线 */}
              {cl.children.map((ch) => (
                <line key={`pc-${ch.req.code}`} x1={cl.cx + 16} y1={cl.cy + 10} x2={ch.x - 10} y2={ch.y} stroke="#e2e8f0" strokeWidth={1.2} />
              ))}

              {/* 需求中心球 */}
              <g onClick={() => onOpenReq(cl.req)} style={{ cursor: 'pointer' }}>
                <title>{`${cl.req.code} ${cl.req.title}\n状态：${cl.req.status}${cl.req.description ? '\n' + cl.req.description.slice(0, 100) : ''}`}</title>
                <circle cx={cl.cx} cy={cl.cy} r={18} fill="#fff"
                        stroke={cl.req.status === 'DONE' ? '#22c55e' : cl.req.status === 'IN_PROGRESS' ? '#3b82f6' : cl.req.status === 'CONFIRMED' ? '#14b8a6' : cl.req.status === 'DROPPED' ? '#e2e8f0' : '#f59e0b'}
                        strokeWidth={2.4} filter={hover === key ? 'url(#glow)' : undefined} />
                <text x={cl.cx} y={cl.cy + 4} textAnchor="middle" fontSize={10} fontWeight={700} fill="#334155">
                  {cl.req.code.replace('REQ-', '')}
                </text>
                <text x={8} y={cl.cy - 4} fontSize={11.5} fontWeight={600} fill="#1e293b">{cl.req.code}</text>
                <text x={8} y={cl.cy + 10} fontSize={9.5} fill="#94a3b8">
                  {cl.req.title.length > 13 ? cl.req.title.slice(0, 13) + '…' : cl.req.title}
                </text>
              </g>

              {/* 拆分需求球 */}
              {cl.children.map((ch) => (
                <g key={ch.req.code} onClick={() => onOpenReq(ch.req)} style={{ cursor: 'pointer' }}>
                  <title>{`${ch.req.code} ${ch.req.title}\n状态：${ch.req.status}（拆分自 ${cl.req.code}）`}</title>
                  <circle cx={ch.x} cy={ch.y} r={12} fill="#fff"
                          stroke={ch.req.status === 'DONE' ? '#22c55e' : ch.req.status === 'IN_PROGRESS' ? '#3b82f6' : ch.req.status === 'CONFIRMED' ? '#14b8a6' : '#f59e0b'}
                          strokeWidth={1.6} />
                  <text x={ch.x} y={ch.y + 3} textAnchor="middle" fontSize={8} fontWeight={700} fill="#334155">
                    {ch.req.code.replace('REQ-', '')}
                  </text>
                </g>
              ))}

              {/* 环上节点球 */}
              {cl.nodes.map((n) => (
                <g key={n.key} style={{ cursor: 'pointer' }}
                   onClick={() => (n.kind === 'task' && n.step ? onOpenStep(n.step) : n.kind === 'case' && n.tc ? onOpenCase(n.tc) : n.bug ? onOpenBug(n.bug) : undefined)}>
                  <title>
                    {n.kind === 'task' && n.step
                      ? `${n.step.name}\n${STATUS_TEXT[n.step.status] || n.step.status}${n.step.verified_by === 'tool' ? ' · 机器验证' : n.step.verified_by === 'human' ? ' · 人确认' : ''}${n.step.error ? '\n❌ ' + n.step.error.slice(0, 120) : ''}${n.step.output_summary ? '\n' + n.step.output_summary.slice(0, 100) : ''}`
                      : n.kind === 'case' && n.tc
                        ? `${n.tc.code} ${n.tc.title}\n状态：${CASE_TEXT[n.tc.status] || n.tc.status}${n.tc.content ? '\n' + n.tc.content.slice(0, 120) : ''}`
                        : n.bug
                          ? `${n.bug.code} ${n.bug.title}\n严重度：${n.bug.severity} · 状态：${BUG_TEXT[n.bug.status] || n.bug.status}${n.bug.content ? '\n' + n.bug.content.slice(0, 120) : ''}`
                          : ''}
                  </title>
                  {n.live && (
                    <circle cx={n.x} cy={n.y} r={7} fill="none" stroke={n.color} strokeWidth={1.5}>
                      <animate attributeName="r" values="7;13;7" dur="1.6s" repeatCount="indefinite" />
                      <animate attributeName="opacity" values="0.8;0;0.8" dur="1.6s" repeatCount="indefinite" />
                    </circle>
                  )}
                  <circle cx={n.x} cy={n.y} r={n.kind === 'task' ? 6 : 5.5}
                          fill={n.dashed ? '#fff' : n.color}
                          stroke={n.dashed ? n.color : '#fff'} strokeWidth={1.8}
                          strokeDasharray={n.dashed ? '2 2' : undefined} />
                  {n.kind === 'task' && n.step?.verified_by === 'tool' && <text x={n.x + 6} y={n.y - 6} fontSize={8}>⚙</text>}
                  {n.kind === 'task' && n.step?.verified_by === 'human' && <text x={n.x + 6} y={n.y - 6} fontSize={8}>👤</text>}
                  {n.kind === 'task' && n.step?.artifact && <rect x={n.x - 2.5} y={n.y + 9} width={5} height={5} rx={1} fill="#94a3b8" />}
                  {/* 标签：球外侧小字（按角度决定左右对齐） */}
                  <text x={n.x + (n.x >= cl.cx ? 10 : -10)} y={n.y + 3.5} fontSize={9}
                        textAnchor={n.x >= cl.cx ? 'start' : 'end'} fill="#64748b">
                    {n.label.length > 14 ? n.label.slice(0, 14) + '…' : n.label}
                  </text>
                </g>
              ))}
            </g>
          )
        })}

        {/* 断链步骤 */}
        {unlinkedNodes.length > 0 && (
          <g opacity={hover === null ? 1 : 0.3}>
            <rect x={0} y={unlinkedY} width={totalW} height={54} fill="#fffbeb" opacity={0.6} />
            <text x={12} y={unlinkedY + 16} fontSize={11} fontWeight={600} fill="#d97706">
              断链 {data.unlinked.length} 项 · 未声明需求关联
            </text>
            {unlinkedNodes.map((n) => (
              <g key={`${n.s.stage}-${n.s.iteration}-${n.s.seq}`} onClick={() => onOpenStep(n.s)} style={{ cursor: 'pointer' }}>
                <title>{`${n.s.name}\n${n.s.status}${n.s.error ? '\n❌ ' + n.s.error.slice(0, 120) : ''}`}</title>
                <circle cx={n.x} cy={n.y} r={6} fill={statusColor(n.s.status)} stroke="#fbbf24" strokeWidth={1.5} strokeDasharray="2 2" />
                <text x={n.x + 10} y={n.y + 3.5} fontSize={9.5} fill="#475569">{n.s.name}</text>
              </g>
            ))}
          </g>
        )}
      </svg>
      <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', padding: '4px 8px' }}>
        一个圆环 = 一条需求的交付关系（中心需求 → 任务/用例/bug 围绕辐射）· 悬停高亮整簇 · 绿完成/红失败/蓝执行中脉冲/黄等答复或阻塞/灰待执行 · 虚线球=未了结 bug · ⚙机器验证 👤人确认 · 点击看详情
      </Typography.Text>
    </div>
  )
}
