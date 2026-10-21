import { useMemo, useState } from 'react'
import { Typography } from 'antd'
import type { TraceStep } from './TracePanel'

/**
 * 需求泳道追溯图（2026-10-20 重做，替换环形簇布局——环形标签外溢重叠、时序不可见）：
 * - 行 = 需求（拆分需求缩进挂父行下），列 = 阶段（REQUIREMENT → DEVELOP → OPERATE）
 * - 行内步骤按 seq 从左到右排进各自阶段列；圆角矩形节点，文字在框内（零重叠）
 * - 连线 = 行内时序（实线，跨阶段延续）+ 需求依赖（左侧虚线）；用例/bug 徽章挂行尾
 * - 断链步骤底部独立泳道；hover 高亮整行其余淡化；点击节点开详情
 * 纯 SVG 手绘，确定性坐标，不引图库依赖。
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

const STATUS_TEXT: Record<string, string> = {
  DONE: '完成', FAILED: '失败', SKIPPED: '已跳过',
  EXECUTING: '执行中', WAITING: '等你答复', PLANNED: '待执行',
}
const CASE_TEXT: Record<string, string> = {
  ACTIVE: '待执行', PASSED: '通过', FAILED: '失败', BLOCKED: '阻塞',
}
const BUG_TEXT: Record<string, string> = {
  OPEN: '待修复', FIXING: '修复中', FIXED: '已修复', CLOSED: '已关闭', REJECTED: '误报',
}

const NODE_FILL: Record<string, string> = {
  DONE: '#22c55e', FAILED: '#ef4444', EXECUTING: '#3b82f6',
  WAITING: '#f59e0b', PLANNED: '#cbd5e1', SKIPPED: '#e2e8f0',
  ACTIVE: '#cbd5e1', PASSED: '#22c55e', BLOCKED: '#f59e0b',
  OPEN: '#ef4444', FIXING: '#f59e0b', FIXED: '#22c55e', CLOSED: '#94a3b8', REJECTED: '#e2e8f0',
}

function statusColor(s: string): string {
  return NODE_FILL[s] || '#cbd5e1'
}

function reqColor(s: string): string {
  return s === 'DONE' ? '#22c55e' : s === 'IN_PROGRESS' ? '#3b82f6'
    : s === 'CONFIRMED' ? '#14b8a6' : s === 'DROPPED' ? '#e2e8f0' : '#f59e0b'
}

// ---- 布局常量 ----
const LANE_W = 168          // 左侧需求标签列
const NODE_W = 118          // 步骤节点宽
const NODE_H = 36           // 步骤节点高
const GAP_X = 16            // 节点水平间距
const ROW_H = NODE_H + 18   // 行高
const HEAD_H = 36           // 阶段表头高
const STAGE_GAP = 26        // 阶段列组间距

interface LaneRow {
  req: TraceGraphData['requirements'][number]
  y: number
  indent: boolean
  nodes: {
    key: string; x: number; y: number; label: string; color: string
    live: boolean; step: TraceStep
  }[]
  cases: TestCaseItem[]
  bugs: BugItem[]
  blocked: boolean
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

    const stages = data.stages.length
      ? data.stages
      : [{ stage: 'REQUIREMENT', label: '需求', status: '' }]

    // 行序：顶层需求 + 其拆分需求紧随（缩进）
    const ordered: { req: TraceGraphData['requirements'][number]; indent: boolean }[] = []
    for (const r of data.requirements.filter((x) => !x.parent_code)) {
      ordered.push({ req: r, indent: false })
      for (const k of data.requirements.filter((x) => x.parent_code === r.code)) {
        ordered.push({ req: k, indent: true })
      }
    }
    for (const r of data.requirements) {
      if (!r.parent_code && !ordered.some((o) => o.req.code === r.code)) ordered.push({ req: r, indent: false })
    }

    // 阶段列偏移：列宽 = 各需求在该阶段最大步骤数
    const stageCols = new Map<string, number>()
    for (const st of stages) stageCols.set(st.stage, 1)
    for (const o of ordered) {
      const cnt = new Map<string, number>()
      for (const s of o.req.chain) cnt.set(s.stage, (cnt.get(s.stage) || 0) + 1)
      for (const [st, n] of cnt) stageCols.set(st, Math.max(stageCols.get(st) || 1, n))
    }
    const stageX = new Map<string, number>()
    let x = LANE_W
    for (const st of stages) {
      const cols = stageCols.get(st.stage) || 1
      stageX.set(st.stage, x)
      x += cols * (NODE_W + GAP_X) + STAGE_GAP
    }
    const totalW = Math.max(x + 40, 640)

    // 行布局：步骤按 seq 排序，落进各自阶段列（行内列号按出现顺序）
    const rows: LaneRow[] = []
    let y = HEAD_H + 8
    for (const o of ordered) {
      const sorted = o.req.chain.slice().sort((a, b) => a.seq - b.seq)
      const colIdx = new Map<string, number>()
      const nodes = sorted.map((s) => {
        const ci = colIdx.get(s.stage) || 0
        colIdx.set(s.stage, ci + 1)
        return {
          key: `${s.stage}-${s.iteration}-${s.seq}`,
          x: (stageX.get(s.stage) || LANE_W) + ci * (NODE_W + GAP_X),
          y: y + (ROW_H - NODE_H) / 2,
          label: s.name || `#${s.seq}`,
          color: statusColor(s.status),
          live: s.status === 'EXECUTING' || s.status === 'WAITING',
          step: s,
        }
      })
      rows.push({
        req: o.req, y, indent: o.indent, nodes,
        cases: byReq(cases, o.req.code),
        bugs: byReq(bugs, o.req.code),
        blocked: (o.req.depends_on || []).some((d) => data.requirements.find((q) => q.code === d && q.status !== 'DONE')),
      })
      y += ROW_H
    }

    // 断链泳道
    const unlinkedY = y + 10
    const unlinkedNodes = data.unlinked.map((s, i) => ({
      s, x: LANE_W + 8 + i * (NODE_W + GAP_X), y: unlinkedY + (ROW_H - NODE_H) / 2,
    }))
    const totalH = unlinkedY + (data.unlinked.length ? ROW_H + 40 : 20)
    return { stages, stageX, rows, unlinkedY, unlinkedNodes, totalW, totalH }
  }, [data])

  const { stages, stageX, rows, unlinkedY, unlinkedNodes, totalW, totalH } = layout
  const dim = (key: string | null) => (hover === null || hover === key ? 1 : 0.18)

  const truncate = (t: string, n: number) => (t.length > n ? t.slice(0, n) + '…' : t)

  return (
    <div style={{ overflowX: 'auto' }}>
      <svg width={totalW} height={totalH} style={{ display: 'block', minWidth: '100%' }}>
        <defs>
          <filter id="tg-glow" x="-60%" y="-60%" width="220%" height="220%">
            <feGaussianBlur stdDeviation="3" result="b" />
            <feMerge>
              <feMergeNode in="b" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        {/* 阶段表头 + 列分隔 */}
        {stages.map((st) => {
          const sx = stageX.get(st.stage) || LANE_W
          const cols = Math.max(1, ...rows.map((r) => r.nodes.filter((n) => n.step.stage === st.stage).length), 1)
          const w = cols * (NODE_W + GAP_X) - GAP_X
          return (
            <g key={st.stage}>
              <rect x={sx - 8} y={4} width={w + 16} height={HEAD_H - 10} rx={6}
                    fill={st.status === 'DONE' ? '#f0fdf4' : st.status === 'IN_PROGRESS' ? '#eff6ff' : '#f8fafc'}
                    stroke="#e2e8f0" />
              <text x={sx + w / 2} y={25} textAnchor="middle" fontSize={12} fontWeight={700} fill="#334155">
                {st.label}{st.status === 'DONE' ? ' ✓' : st.status === 'IN_PROGRESS' ? ' …' : ''}
              </text>
            </g>
          )
        })}

        {/* 需求依赖虚线（标签列内：依赖 → 需求） */}
        {rows.map((r) => (r.req.depends_on || []).map((d) => {
          const from = rows.find((q) => q.req.code === d)
          if (!from) return null
          const y1 = from.y + ROW_H / 2, y2 = r.y + ROW_H / 2
          return (
            <path key={`dep-${r.req.code}-${d}`}
                  d={`M ${LANE_W - 10} ${y1} C ${LANE_W - 34} ${y1}, ${LANE_W - 34} ${y2}, ${LANE_W - 10} ${y2}`}
                  fill="none" stroke="#c7d2fe" strokeWidth={1.2} strokeDasharray="4 3" opacity={dim(r.req.code)} />
          )
        }))}

        {/* 泳道行 */}
        {rows.map((r) => {
          const key = r.req.code
          const last = r.nodes[r.nodes.length - 1]
          const tailX = last ? last.x + NODE_W : LANE_W + 8
          return (
            <g key={key} opacity={dim(key)} style={{ transition: 'opacity .25s' }}
               onMouseEnter={() => setHover(key)} onMouseLeave={() => setHover(null)}>
              {r.blocked && (
                <rect x={0} y={r.y - 4} width={totalW} height={ROW_H} fill="#fffbeb" opacity={0.55} rx={6} />
              )}
              {/* 需求标签 */}
              <g onClick={() => onOpenReq(r.req)} style={{ cursor: 'pointer' }}>
                <title>{`${r.req.code} ${r.req.title}\n状态：${r.req.status}${r.req.parent_code ? `（拆分自 ${r.req.parent_code}）` : ''}${r.req.description ? '\n' + r.req.description.slice(0, 120) : ''}`}</title>
                <circle cx={(r.indent ? 26 : 14) + 5} cy={r.y + ROW_H / 2} r={5.5}
                        fill="#fff" stroke={reqColor(r.req.status)} strokeWidth={2.2} />
                <text x={r.indent ? 40 : 28} y={r.y + ROW_H / 2 - 2} fontSize={11.5} fontWeight={700} fill="#1e293b">
                  {r.req.code}
                </text>
                <text x={r.indent ? 40 : 28} y={r.y + ROW_H / 2 + 11} fontSize={9.5} fill="#94a3b8">
                  {truncate(r.req.title, 12)}
                </text>
              </g>
              {/* 行内时序连线 */}
              {r.nodes.slice(1).map((n, i) => {
                const p = r.nodes[i]
                return (
                  <line key={`seq-${n.key}`} x1={p.x + NODE_W} y1={p.y + NODE_H / 2} x2={n.x} y2={n.y + NODE_H / 2}
                        stroke="#cbd5e1" strokeWidth={1.3} />
                )
              })}
              {/* 步骤节点（矩形，文字在框内） */}
              {r.nodes.map((n) => (
                <g key={n.key} style={{ cursor: 'pointer' }} onClick={() => onOpenStep(n.step)}>
                  <title>
                    {`${n.step.name}\n${STATUS_TEXT[n.step.status] || n.step.status} · ${n.step.stage}${n.step.verified_by === 'tool' ? ' · ⚙机器验证' : n.step.verified_by === 'human' ? ' · 👤人确认' : ''}${n.step.error ? '\n❌ ' + n.step.error.slice(0, 140) : ''}${n.step.output_summary ? '\n' + n.step.output_summary.slice(0, 120) : ''}`}
                  </title>
                  {n.live && (
                    <rect x={n.x - 4} y={n.y - 4} width={NODE_W + 8} height={NODE_H + 8} rx={10} fill="none" stroke={n.color} strokeWidth={1.4}>
                      <animate attributeName="opacity" values="0.9;0.1;0.9" dur="1.6s" repeatCount="indefinite" />
                    </rect>
                  )}
                  <rect x={n.x} y={n.y} width={NODE_W} height={NODE_H} rx={7}
                        fill={n.step.status === 'SKIPPED' ? '#fff' : n.color}
                        fillOpacity={n.step.status === 'SKIPPED' ? 1 : 0.14}
                        stroke={n.color} strokeWidth={1.6}
                        strokeDasharray={n.step.status === 'PLANNED' || n.step.status === 'SKIPPED' ? '4 3' : undefined} />
                  <text x={n.x + NODE_W / 2} y={n.y + 15} textAnchor="middle" fontSize={9.5} fontWeight={600} fill="#334155">
                    {truncate(n.label, 13)}
                  </text>
                  <text x={n.x + NODE_W / 2} y={n.y + 28} textAnchor="middle" fontSize={8.5} fill="#64748b">
                    {(STATUS_TEXT[n.step.status] || n.step.status)
                      + (n.step.verified_by === 'tool' ? ' ⚙' : n.step.verified_by === 'human' ? ' 👤' : '')}
                  </text>
                  {(n.step.consults?.length ?? 0) > 0 && (
                    <g onClick={(e) => { e.stopPropagation(); onOpenStep(n.step.consults![n.step.consults!.length - 1]) }}
                       style={{ cursor: 'pointer' }}>
                      <title>{`${n.step.consults!.length} 次执行中求助，点击查看最近一次问答`}</title>
                      <circle cx={n.x + NODE_W - 7} cy={n.y + 7} r={7.5} fill="#ede9fe" stroke="#8b5cf6" strokeWidth={1.1} />
                      <text x={n.x + NODE_W - 7} y={n.y + 10.5} textAnchor="middle" fontSize={8.5} fill="#6d28d9" fontWeight={700}>
                        {'💬' + n.step.consults!.length}
                      </text>
                    </g>
                  )}
                </g>
              ))}
              {/* 用例/bug 徽章（行尾） */}
              {r.cases.map((c, i) => (
                <g key={c.code} style={{ cursor: 'pointer' }} onClick={() => onOpenCase(c)}>
                  <title>{`${c.code} ${c.title}\n用例：${CASE_TEXT[c.status] || c.status}${c.content ? '\n' + c.content.slice(0, 140) : ''}`}</title>
                  <rect x={tailX + 6 + i * 58} y={r.y + ROW_H / 2 - 9} width={52} height={18} rx={9}
                        fill="#fff" stroke={statusColor(c.status)} strokeWidth={1.2} />
                  <text x={tailX + 6 + i * 58 + 26} y={r.y + ROW_H / 2 + 3} textAnchor="middle" fontSize={8.5} fill="#475569">
                    🧪{truncate(c.code.replace('TC-', ''), 5)}
                  </text>
                </g>
              ))}
              {r.bugs.map((b, i) => (
                <g key={b.code} style={{ cursor: 'pointer' }} onClick={() => onOpenBug(b)}>
                  <title>{`${b.code} ${b.title}\n严重度：${b.severity} · 状态：${BUG_TEXT[b.status] || b.status}${b.content ? '\n' + b.content.slice(0, 140) : ''}`}</title>
                  <rect x={tailX + 6 + (r.cases.length + i) * 58} y={r.y + ROW_H / 2 - 9} width={52} height={18} rx={9}
                        fill="#fff" stroke={statusColor(b.status)} strokeWidth={1.2} strokeDasharray={b.status === 'OPEN' || b.status === 'FIXING' ? '3 2' : undefined} />
                  <text x={tailX + 6 + (r.cases.length + i) * 58 + 26} y={r.y + ROW_H / 2 + 3} textAnchor="middle" fontSize={8.5} fill="#475569">
                    🐞{truncate(b.code.replace('BUG-', ''), 5)}
                  </text>
                </g>
              ))}
            </g>
          )
        })}

        {/* 未挂需求泳道：global=全局步骤（["*"] 显式声明，中性灰蓝）；其余=真断链（黄警示） */}
        {unlinkedNodes.length > 0 && (
          <g opacity={hover === null ? 1 : 0.35}>
            <rect x={0} y={unlinkedY} width={totalW} height={ROW_H + 8} fill="#fffbeb" opacity={0.6} rx={6} />
            <text x={12} y={unlinkedY + ROW_H / 2 - 2} fontSize={11} fontWeight={700} fill="#d97706">
              未挂需求
            </text>
            <text x={12} y={unlinkedY + ROW_H / 2 + 12} fontSize={9} fill="#b45309">
              {data.unlinked.filter((s) => !s.global).length} 断链
              {data.unlinked.some((s) => s.global) ? ` · ${data.unlinked.filter((s) => s.global).length} 全局` : ''}
            </text>
            {unlinkedNodes.map((n) => {
              const isGlobal = !!n.s.global
              const stroke = isGlobal ? '#64748b' : '#fbbf24'
              return (
                <g key={`${n.s.stage}-${n.s.iteration}-${n.s.seq}`} style={{ cursor: 'pointer' }} onClick={() => onOpenStep(n.s)}>
                  <title>
                    {`${n.s.name}\n${isGlobal ? '全局步骤（不专属某条需求）' : '断链：未声明服务任何需求'}\n${STATUS_TEXT[n.s.status] || n.s.status} · ${n.s.stage}${n.s.error ? '\n❌ ' + n.s.error.slice(0, 140) : ''}`}
                  </title>
                  <rect x={n.x} y={n.y} width={NODE_W} height={NODE_H} rx={7}
                        fill={statusColor(n.s.status)} fillOpacity={0.12}
                        stroke={stroke} strokeWidth={1.4}
                        strokeDasharray={isGlobal ? undefined : '4 3'} />
                  <text x={n.x + NODE_W / 2} y={n.y + 15} textAnchor="middle" fontSize={9.5} fontWeight={600} fill="#334155">
                    {truncate(n.s.name, 13)}
                  </text>
                  <text x={n.x + NODE_W / 2} y={n.y + 28} textAnchor="middle" fontSize={8.5} fill={isGlobal ? '#64748b' : '#b45309'}>
                    {isGlobal ? '全局' : '断链'} · {STATUS_TEXT[n.s.status] || n.s.status}
                  </text>
                </g>
              )
            })}
          </g>
        )}
      </svg>
      <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', padding: '4px 8px' }}>
        行 = 需求（缩进 = 拆分需求）· 列 = 阶段推进方向（左→右）· 实线 = 行内时序 · 左侧虚线 = 需求依赖 · 🧪用例 🐞bug 挂行尾 · 黄底行 = 依赖未闭环或断链 · 绿完成/红失败/蓝执行中/黄等答复/灰虚线待执行 · ⚙机器验证 👤人确认 · 点击看详情
      </Typography.Text>
    </div>
  )
}
