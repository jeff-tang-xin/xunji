import { useMemo, useState } from 'react'
import { Typography } from 'antd'
import type { TraceStep } from './TracePanel'

/**
 * 追溯拓扑图（泳道时间线）：行 = 一条需求横穿三阶段的完整链路。
 * - 需求节点在行首，步骤节点按 seq 在所属阶段泳道内从左到右排布
 * - 行内贝塞尔曲线串联（执行顺序），阶段泳道以底色带区分
 * - hover 行 → 整条链路高亮发光、其余淡化（链路归属一眼可辨）
 * - 节点状态色 + 执行中脉冲光圈 + ⚙机器验证/👤人确认标记 + 产出小方块
 * 纯 SVG 手绘，不引图库依赖；点击节点回调父组件开详情。
 */

export interface TraceGraphData {
  stages: { stage: string; label: string; status: string }[]
  requirements: {
    code: string
    title: string
    status: string
    depends_on: string[]
    chain: TraceStep[]
  }[]
  unlinked: TraceStep[]
}

const STAGE_ORDER = ['REQUIREMENT', 'DEVELOP', 'OPERATE']
const STAGE_TINT: Record<string, string> = {
  REQUIREMENT: '#faf5ff',
  DEVELOP: '#f0f7ff',
  OPERATE: '#f0fff4',
}
const STAGE_LINE: Record<string, string> = {
  REQUIREMENT: '#e9d5ff',
  DEVELOP: '#bfdbfe',
  OPERATE: '#bbf7d0',
}
/** 状态 → 节点填充色（低饱和底 + 深色描边，克制不花哨） */
const NODE_FILL: Record<string, string> = {
  DONE: '#22c55e',
  FAILED: '#ef4444',
  EXECUTING: '#3b82f6',
  WAITING: '#f59e0b',
  PLANNED: '#cbd5e1',
  SKIPPED: '#e2e8f0',
}
const ROW_H = 52
const HEADER_H = 34
const REQ_X = 110
const GAP = 34

function nodeColor(s: TraceStep): string {
  return NODE_FILL[s.status] || '#cbd5e1'
}

export function TraceGraph({ data, onOpenStep }: { data: TraceGraphData; onOpenStep: (s: TraceStep) => void }) {
  const [hover, setHover] = useState<number | null>(null)

  const layout = useMemo(() => {
    // 每阶段泳道宽度 = 该阶段内单需求最大步骤数驱动，保证同列节点不重叠
    const maxInStage: Record<string, number> = {}
    for (const st of STAGE_ORDER) {
      maxInStage[st] = 1
    }
    for (const r of data.requirements) {
      const byStage: Record<string, number> = {}
      for (const s of r.chain) {
        byStage[s.stage] = (byStage[s.stage] || 0) + 1
      }
      for (const st of Object.keys(byStage)) {
        if (st in maxInStage) maxInStage[st] = Math.max(maxInStage[st], byStage[st])
      }
    }
    const colX: Record<string, number> = {}
    let x = REQ_X + 60
    for (const st of STAGE_ORDER) {
      colX[st] = x
      x += maxInStage[st] * GAP + 46
    }
    const totalW = x
    const rows = data.requirements.map((r, i) => {
      const y = HEADER_H + i * ROW_H + ROW_H / 2
      const byStage = new Map<string, TraceStep[]>()
      for (const s of r.chain) {
        const arr = byStage.get(s.stage) || []
        arr.push(s)
        byStage.set(s.stage, arr)
      }
      const nodes: { s: TraceStep; x: number; y: number }[] = []
      for (const st of STAGE_ORDER) {
        const arr = (byStage.get(st) || []).slice().sort((a, b) => a.seq - b.seq)
        arr.forEach((s, j) => {
          nodes.push({ s, x: colX[st] + j * GAP + 12, y })
        })
      }
      nodes.sort((a, b) => a.x - b.x)
      return { req: r, y, nodes }
    })
    const unlinkedY = HEADER_H + data.requirements.length * ROW_H + 30
    const unlinkedNodes = data.unlinked
      .slice()
      .sort((a, b) => STAGE_ORDER.indexOf(a.stage) - STAGE_ORDER.indexOf(b.stage) || a.seq - b.seq)
      .map((s, j) => ({ s, x: colX[s.stage] + j * GAP + 12, y: unlinkedY }))
      .filter((n) => n.x > 0)
    return { colX, totalW, rows, unlinkedY, unlinkedNodes, totalH: unlinkedY + 56 }
  }, [data])

  const { colX, totalW, rows, unlinkedY, unlinkedNodes, totalH } = layout
  const dim = (i: number) => (hover === null ? 1 : hover === i ? 1 : 0.16)

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

        {/* 阶段泳道底色带 + 列头 */}
        {STAGE_ORDER.map((st) => {
          const idx = STAGE_ORDER.indexOf(st)
          const next = STAGE_ORDER[idx + 1]
          const colW = next ? colX[next] - colX[st] - 14 : totalW - colX[st] + 14
          return (
          <g key={st}>
            <rect x={colX[st] - 14} y={0} width={colW} height={totalH} fill={STAGE_TINT[st]} opacity={0.55} />
            <line x1={colX[st] - 14} y1={0} x2={colX[st] - 14} y2={totalH} stroke={STAGE_LINE[st]} strokeDasharray="3 4" />
            <text x={colX[st] + 2} y={20} fontSize={12} fontWeight={600} fill="#64748b">
              {st === 'REQUIREMENT' ? '需求阶段' : st === 'DEVELOP' ? '开发阶段' : '运维阶段'}
            </text>
          </g>
          )
        })}

        {/* 需求行 */}
        {rows.map((row, i) => {
          const r = row.req
          const blocked = (r.depends_on || []).some((d) => data.requirements.find((x) => x.code === d && x.status !== 'DONE'))
          const pts = [{ x: REQ_X + 18, y: row.y }, ...row.nodes]
          const path = pts.map((p, j) => {
            if (j === 0) return ''
            const prev = pts[j - 1]
            const mx = (prev.x + p.x) / 2
            return `M ${prev.x} ${prev.y} C ${mx} ${prev.y}, ${mx} ${p.y}, ${p.x} ${p.y}`
          }).join(' ')
          return (
            <g key={r.code} opacity={dim(i)} style={{ transition: 'opacity .25s' }}
               onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
              {/* 行底 hover 热区 */}
              <rect x={0} y={row.y - ROW_H / 2} width={totalW} height={ROW_H} fill={hover === i ? '#f8fafc' : 'transparent'} />
              {blocked && <rect x={0} y={row.y - ROW_H / 2} width={totalW} height={ROW_H} fill="#fffbeb" opacity={0.5} />}
              {/* 链路连线 */}
              <path d={path} fill="none" stroke={hover === i ? '#6366f1' : '#cbd5e1'} strokeWidth={hover === i ? 2 : 1.2} filter={hover === i ? 'url(#glow)' : undefined} />
              {/* 需求节点 */}
              <g onClick={() => onOpenStep(row.nodes[0]?.s || ({ stage: 'REQUIREMENT', iteration: 1, seq: 0, name: r.title, status: r.status } as TraceStep))} style={{ cursor: 'pointer' }}>
                <circle cx={REQ_X} cy={row.y} r={15} fill="#fff" stroke={r.status === 'DONE' ? '#22c55e' : r.status === 'IN_PROGRESS' ? '#3b82f6' : '#94a3b8'} strokeWidth={2} filter={hover === i ? 'url(#glow)' : undefined} />
                <text x={REQ_X} y={row.y + 4} textAnchor="middle" fontSize={9} fontWeight={700} fill="#334155">
                  {r.code.replace('REQ-', '')}
                </text>
                <text x={8} y={row.y - 2} fontSize={11} fontWeight={600} fill="#1e293b">
                  {r.code}
                </text>
                <text x={8} y={row.y + 12} fontSize={9} fill="#94a3b8">
                  {r.title.length > 12 ? r.title.slice(0, 12) + '…' : r.title}
                </text>
              </g>
              {/* 步骤节点 */}
              {row.nodes.map((n) => {
                const c = nodeColor(n.s)
                const live = n.s.status === 'EXECUTING' || n.s.status === 'WAITING'
                return (
                  <g key={`${n.s.stage}-${n.s.iteration}-${n.s.seq}`} onClick={() => onOpenStep(n.s)} style={{ cursor: 'pointer' }}>
                    <title>{`${n.s.name}\n${n.s.status}${n.s.verified_by === 'tool' ? ' · 机器验证' : n.s.verified_by === 'human' ? ' · 人确认' : ''}${n.s.error ? '\n❌ ' + n.s.error.slice(0, 120) : ''}${n.s.output_summary ? '\n' + n.s.output_summary.slice(0, 100) : ''}`}</title>
                    {live && (
                      <circle cx={n.x} cy={n.y} r={7} fill="none" stroke={c} strokeWidth={1.5}>
                        <animate attributeName="r" values="7;13;7" dur="1.6s" repeatCount="indefinite" />
                        <animate attributeName="opacity" values="0.8;0;0.8" dur="1.6s" repeatCount="indefinite" />
                      </circle>
                    )}
                    <circle cx={n.x} cy={n.y} r={6} fill={c} stroke="#fff" strokeWidth={1.5} />
                    {n.s.verified_by === 'tool' && (
                      <text x={n.x + 6} y={n.y - 6} fontSize={8}>⚙</text>
                    )}
                    {n.s.verified_by === 'human' && (
                      <text x={n.x + 6} y={n.y - 6} fontSize={8}>👤</text>
                    )}
                    {n.s.artifact && (
                      <rect x={n.x - 2.5} y={n.y + 9} width={5} height={5} rx={1} fill="#94a3b8" />
                    )}
                  </g>
                )
              })}
            </g>
          )
        })}

        {/* 断链泳道 */}
        {unlinkedNodes.length > 0 && (
          <g opacity={hover === null ? 1 : 0.3}>
            <rect x={0} y={unlinkedY - 22} width={totalW} height={ROW_H} fill="#fffbeb" opacity={0.6} />
            <text x={12} y={unlinkedY - 8} fontSize={11} fontWeight={600} fill="#d97706">
              断链 {data.unlinked.length} 项 · 未声明需求关联
            </text>
            {unlinkedNodes.map((n) => (
              <g key={`${n.s.stage}-${n.s.iteration}-${n.s.seq}`} onClick={() => onOpenStep(n.s)} style={{ cursor: 'pointer' }}>
                <title>{`${n.s.name}\n${n.s.status}${n.s.error ? '\n❌ ' + n.s.error.slice(0, 120) : ''}`}</title>
                <circle cx={n.x} cy={n.y + 6} r={6} fill={nodeColor(n.s)} stroke="#fbbf24" strokeWidth={1.5} strokeDasharray="2 2" />
              </g>
            ))}
          </g>
        )}
      </svg>
      <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', padding: '4px 8px' }}>
        一行 = 一条需求横穿三阶段的链路 · 悬停高亮整条链 · 圆点=步骤（绿完成/红失败/蓝执行中脉冲/黄等决策）· ⚙机器验证 👤人确认 · 方块=有产出 · 虚线圆=断链 · 点击看详情
      </Typography.Text>
    </div>
  )
}
