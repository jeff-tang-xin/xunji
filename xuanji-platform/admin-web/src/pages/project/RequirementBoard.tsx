import { useCallback, useEffect, useMemo, useState } from 'react'
import { Empty, Spin, Tag, Typography, message } from 'antd'
import { DownOutlined, ReloadOutlined, RightOutlined } from '@ant-design/icons'
import { apiJson } from '../../api/client'

/**
 * 需求看板（V34 需求先行）：需求清单 + 依赖拓扑图。
 * 拓扑按 depth 分层（无依赖在第 0 层，依赖链越深越靠下），SVG 连线表达「依赖 → 需求」。
 * 依赖未满足（存在非 DONE 的依赖）的卡片降透明度——一眼看出哪些需求当前可开工。
 */

interface RequirementActivity {
  type: string
  name?: string | null
  summary?: string | null
  date?: string | null
  actor?: string | null
  participants?: string[] | null
  done?: boolean
}

interface RequirementItem {
  code: string
  title: string
  description?: string | null
  status: string
  depends_on: string[]
  acceptance?: string | null
  depth: number
  created_by?: string | null
  updated_at?: string | null
  discuss_count?: number
  clarify_count?: number
  activities?: RequirementActivity[]
}

const STATUS_META: Record<string, { label: string; color: string }> = {
  PROPOSED: { label: '待确认', color: 'default' },
  CONFIRMED: { label: '已确认', color: 'blue' },
  IN_PROGRESS: { label: '进行中', color: 'orange' },
  DONE: { label: '已完成', color: 'green' },
  DROPPED: { label: '已放弃', color: 'default' },
}

const CARD_W = 176
const CARD_H = 62
const GAP_X = 28
const GAP_Y = 96
const PAD = 16

export function RequirementBoard({ projectId, refreshKey = 0 }: { projectId: string; refreshKey?: number }) {
  const [items, setItems] = useState<RequirementItem[] | null>(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    apiJson<{ requirements: RequirementItem[] }>(`/api/projects/${projectId}/requirements`)
      .then((r) => setItems(r?.requirements || []))
      .catch((e: Error) => message.error(e.message))
      .finally(() => setLoading(false))
  }, [projectId])

  // refreshKey：父组件收到 requirement.updated 实时事件时 +1，看板即时刷新（不等手动）
  useEffect(() => { load() }, [load, refreshKey])

  /** 依赖未满足：存在非 DONE 的依赖项 */
  const blocked = useCallback((r: RequirementItem, all: RequirementItem[]) =>
    (r.depends_on || []).some((d) => {
      const dep = all.find((x) => x.code === d)
      return dep ? dep.status !== 'DONE' : false
    }), [])

  const layout = useMemo(() => {
    if (!items || items.length === 0) return null
    const sorted = [...items].sort((a, b) => a.code.localeCompare(b.code))
    const byDepth = new Map<number, RequirementItem[]>()
    for (const r of sorted) {
      const list = byDepth.get(r.depth) || []
      list.push(r)
      byDepth.set(r.depth, list)
    }
    const depths = [...byDepth.keys()].sort((a, b) => a - b)
    const pos = new Map<string, { x: number; y: number }>()
    let maxCols = 1
    depths.forEach((d, row) => {
      const list = byDepth.get(d)!
      maxCols = Math.max(maxCols, list.length)
      list.forEach((r, col) => {
        pos.set(r.code, { x: PAD + col * (CARD_W + GAP_X), y: PAD + row * (CARD_H + GAP_Y) })
      })
    })
    const width = PAD * 2 + maxCols * CARD_W + (maxCols - 1) * GAP_X
    const height = PAD * 2 + depths.length * CARD_H + (depths.length - 1) * GAP_Y
    const edges: { from: string; to: string }[] = []
    for (const r of sorted) {
      for (const dep of r.depends_on || []) {
        if (pos.has(dep)) edges.push({ from: dep, to: r.code })
      }
    }
    return { sorted, pos, width, height, edges }
  }, [items])

  /** 讨论明细默认收起（同一会诊汇总会挂在多张关联卡片上，全部展开又长又重复——实测反馈）；点击展开 */
  const [openActs, setOpenActs] = useState<Set<string>>(new Set())
  const toggleActs = (code: string) => {
    setOpenActs((prev) => {
      const next = new Set(prev)
      if (next.has(code)) next.delete(code)
      else next.add(code)
      return next
    })
  }

  if (loading && !items) {
    return <div style={{ padding: 32, textAlign: 'center' }}><Spin /></div>
  }
  if (!items || items.length === 0) {
    return (
      <div>
        <div style={{ marginBottom: 8, textAlign: 'right' }}>
          <Typography.Link onClick={load}><ReloadOutlined /> 刷新</Typography.Link>
        </div>
        <Empty description="暂无需求项——PM 会在对话中通过 declare_requirements 落项" />
      </div>
    )
  }

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          依赖拓扑：上层是下层的前置；半透明 = 依赖未满足（暂不可开工）
        </Typography.Text>
        <Typography.Link onClick={load}><ReloadOutlined /> 刷新</Typography.Link>
      </div>

      {layout && (
        <div style={{ overflowX: 'auto', marginBottom: 12, border: '1px solid #f0f0f0', borderRadius: 8, background: '#fafafa' }}>
          <svg width={layout.width} height={layout.height} style={{ display: 'block', minWidth: '100%' }}>
            <defs>
              <marker id="req-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                <path d="M0,0 L8,4 L0,8 Z" fill="#8c8c8c" />
              </marker>
            </defs>
            {layout.edges.map(({ from, to }) => {
              const f = layout.pos.get(from)!
              const t = layout.pos.get(to)!
              const x1 = f.x + CARD_W / 2
              const y1 = f.y + CARD_H
              const x2 = t.x + CARD_W / 2
              const y2 = t.y
              const my = (y1 + y2) / 2
              return (
                <path
                  key={`${from}->${to}`}
                  d={`M ${x1} ${y1} C ${x1} ${my}, ${x2} ${my}, ${x2} ${y2}`}
                  fill="none"
                  stroke="#8c8c8c"
                  strokeWidth={1.2}
                  markerEnd="url(#req-arrow)"
                />
              )
            })}
            {layout.sorted.map((r) => {
              const p = layout.pos.get(r.code)!
              const meta = STATUS_META[r.status] || { label: r.status, color: 'default' }
              const dim = blocked(r, items) && r.status !== 'DONE'
              return (
                <g key={r.code} transform={`translate(${p.x}, ${p.y})`} opacity={dim ? 0.45 : 1}>
                  <rect
                    width={CARD_W}
                    height={CARD_H}
                    rx={8}
                    fill="#fff"
                    stroke={r.status === 'DONE' ? '#b7eb8f' : r.status === 'IN_PROGRESS' ? '#ffd591' : '#d9d9d9'}
                    strokeWidth={1.2}
                  />
                  <text x={10} y={20} fontSize={12} fontWeight={600} fill="#262626">
                    {r.code}
                  </text>
                  <text x={10} y={38} fontSize={11} fill="#595959">
                    {r.title.length > 14 ? `${r.title.slice(0, 14)}…` : r.title}
                  </text>
                  <text x={10} y={54} fontSize={10} fill={r.status === 'DONE' ? '#389e0d' : r.status === 'IN_PROGRESS' ? '#d46b08' : '#8c8c8c'}>
                    {meta.label}
                    {(r.discuss_count || 0) + (r.clarify_count || 0) > 0 &&
                      ` · 讨论${r.discuss_count || 0} 澄清${r.clarify_count || 0}`}
                  </text>
                </g>
              )
            })}
          </svg>
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {/* 倒序：最新登记的需求（编号最大）在最上面；拓扑图保持 depth 分层语义不动 */}
        {[...items].reverse().map((r) => {
          const meta = STATUS_META[r.status] || { label: r.status, color: 'default' }
          const dim = blocked(r, items) && r.status !== 'DONE'
          return (
            <div
              key={r.code}
              style={{
                padding: '6px 10px',
                border: '1px solid #f0f0f0',
                borderRadius: 6,
                opacity: dim ? 0.55 : 1,
                background: '#fff',
              }}
            >
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                <Typography.Text strong style={{ fontSize: 12 }}>{r.code}</Typography.Text>
                <Typography.Text style={{ fontSize: 12 }}>{r.title}</Typography.Text>
                <Tag color={meta.color} style={{ marginRight: 0 }}>{meta.label}</Tag>
                {(r.discuss_count || 0) > 0 && (
                  <Tag style={{ marginRight: 0 }}>讨论 {r.discuss_count}</Tag>
                )}
                {(r.clarify_count || 0) > 0 && (
                  <Tag style={{ marginRight: 0 }}>澄清 {r.clarify_count}</Tag>
                )}
                {(r.depends_on || []).length > 0 && (
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    依赖 {r.depends_on.join('、')}
                  </Typography.Text>
                )}
              </div>
              {r.description && (
                <Typography.Paragraph type="secondary" style={{ fontSize: 11, marginBottom: 0, marginTop: 2 }} ellipsis={{ rows: 2 }}>
                  {r.description}
                </Typography.Paragraph>
              )}
              {(r.activities || []).length > 0 && (
                <div style={{ marginTop: 4, borderTop: '1px dashed #f0f0f0', paddingTop: 4 }}>
                  <div
                    onClick={() => toggleActs(r.code)}
                    style={{ fontSize: 11, color: '#595959', cursor: 'pointer', userSelect: 'none', display: 'flex', alignItems: 'center', gap: 4 }}
                  >
                    {openActs.has(r.code) ? <DownOutlined style={{ fontSize: 10 }} /> : <RightOutlined style={{ fontSize: 10 }} />}
                    <span>
                      讨论/澄清 {r.activities!.length} 条{openActs.has(r.code) ? '（点击收起）' : '（点击展开）'}
                    </span>
                  </div>
                  {openActs.has(r.code) && r.activities!.map((a, i) => (
                    <div key={i} style={{ fontSize: 11, color: '#595959', lineHeight: '18px', marginTop: 2 }}>
                      <Tag
                        color={a.type === 'DISCUSS' ? 'purple' : 'cyan'}
                        style={{ marginRight: 4, fontSize: 10, lineHeight: '16px' }}
                      >
                        {a.type === 'DISCUSS' ? '讨论' : '澄清'}
                      </Tag>
                      <Typography.Text type={a.done ? 'secondary' : undefined} style={{ fontSize: 11 }}>
                        {a.summary ? (a.summary.length > 60 ? `${a.summary.slice(0, 60)}…` : a.summary) : a.name || '（无结论）'}
                      </Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 10, marginLeft: 6 }}>
                        {a.date ? a.date.slice(5, 16).replace('T', ' ') : ''} · {a.actor || '?'}
                        {a.done === false && ' · 进行中'}
                      </Typography.Text>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
