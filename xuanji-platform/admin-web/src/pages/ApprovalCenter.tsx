/**
 * 决策工作区（审批中心）—— 对齐原型 03-approval-center.html：左侧待办卡片列表 + 右侧决策面板。
 * 数据源：GET /api/decisions/pending（SUSPENDED run）+ GET /api/runs/{id}（gate 节点上游产物）
 * + GET /api/decisions?runId=（决策历史）；决策提交 POST /api/decisions。
 * 评审循环（docs/detail/03 §5）：批注意见 → Agent 按批注重生成 → 再审批；
 * P0 驳回语义 = run 保持 SUSPENDED + 批注入审计，等下一轮决策。
 */
import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Button, Card, Empty, Input, Space, Spin, Tag, Timeline, Typography, message,
} from 'antd'
import { apiJson } from '../api/client'

/** 待办行（后端 snake_case） */
interface PendingRow {
  id: string
  definition_id: number
  version: number
  trigger_type: string
  started_at: string
  definition_name: string
  gate_node_id: string
  gate_node_name?: string | null
}

/** run 详情节点行 */
interface NodeRunRow {
  node_id: string
  node_name?: string | null
  seq?: number | null
  status: string
  input_json?: string | null
  output_json?: string | null
  error?: string | null
}

interface DecisionRow {
  id: number
  node_id: string
  decision: string
  decided_by?: string | null
  remark?: string | null
  decided_at?: string | null
}

/** 相对等待时长（原型 wait 字段等价） */
function waitSince(startedAt: string): string {
  const ms = Date.now() - new Date(startedAt).getTime()
  const min = Math.floor(ms / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return min + 'm'
  const h = Math.floor(min / 60)
  if (h < 24) return h + 'h ' + (min % 60) + 'm'
  return Math.floor(h / 24) + 'd ' + (h % 24) + 'h'
}

function fmtTime(ts?: string | null): string {
  if (!ts) return '-'
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 渲染待审产物：{output:文本} 取文本直出，其余 JSON 美化 */
function renderArtifact(outputJson: string): string {
  try {
    const obj = JSON.parse(outputJson)
    if (obj && typeof obj === 'object' && typeof obj.output === 'string') {
      return obj.output
    }
    return JSON.stringify(obj, null, 2)
  } catch {
    return outputJson
  }
}

export default function ApprovalCenter() {
  const [todos, setTodos] = useState<PendingRow[]>([])
  const [sel, setSel] = useState<PendingRow | null>(null)
  const [nodes, setNodes] = useState<NodeRunRow[]>([])
  const [history, setHistory] = useState<DecisionRow[]>([])
  const [comment, setComment] = useState('')
  const [loading, setLoading] = useState(false)
  const [acting, setActing] = useState(false)

  const loadPending = useCallback(async (keepSel = true) => {
    setLoading(true)
    try {
      const rows = await apiJson<PendingRow[]>('/api/decisions/pending')
      setTodos(rows)
      setSel(prev => {
        if (keepSel && prev && rows.some(r => r.id === prev.id)) return prev
        return rows[0] ?? null
      })
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void loadPending() }, [loadPending])

  /** 选中待办 → 拉 run 详情（gate 节点上游产物）与决策历史 */
  useEffect(() => {
    if (!sel) { setNodes([]); setHistory([]); return }
    let alive = true
    void (async () => {
      try {
        const run = await apiJson<{ nodes?: NodeRunRow[] }>(`/api/runs/${sel.id}`)
        const decisions = await apiJson<DecisionRow[]>(`/api/decisions?runId=${sel.id}`)
        if (!alive) return
        setNodes(run.nodes ?? [])
        setHistory(decisions)
      } catch (e) {
        if (alive) message.error((e as Error).message)
      }
    })()
    return () => { alive = false }
  }, [sel])

  /** gate 节点最近一次产物（评审门：Agent 先产出再挂起，产物在 output_json） */
  const gateNodeRuns = nodes.filter(n => n.node_id === sel?.gate_node_id)
  const gateLatest = gateNodeRuns.length ? gateNodeRuns[gateNodeRuns.length - 1] : null
  const gateOutput = gateLatest?.output_json ?? null
  /** 版本轮次：首次产出 v1，每驳回重生成一次 +1（由 node_run 条数推导） */
  const artifactVersion = gateNodeRuns.length

  const decide = async (decision: 'APPROVE' | 'REJECT') => {
    if (!sel) return
    if (decision === 'REJECT' && !comment.trim()) {
      message.warning('驳回请填写批注意见——批注将驱动 Agent 重生成下一版')
      return
    }
    setActing(true)
    try {
      const r = await apiJson<{ status: string; regenerated?: boolean; round?: number }>(
        '/api/decisions', 'POST', {
          runId: sel.id, nodeId: sel.gate_node_id, decision, comment: comment.trim(),
        })
      if (r.status === 'SUCCESS') {
        message.success(`已通过：run ${sel.id} 从「${sel.gate_node_name || sel.gate_node_id}」恢复执行并完成`)
      } else if (r.status === 'SUSPENDED' && r.regenerated) {
        message.success(`已驳回：Agent 已按批注产出第 ${r.round} 版，请审阅新版本`)
      } else if (r.status === 'SUSPENDED') {
        message.info('已驳回：批注已记录，流程保持暂停')
      } else {
        message.success(`决策已提交，run 状态：${r.status}`)
      }
      setComment('')
      await loadPending()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setActing(false)
    }
  }

  return (
    <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
      {/* 左：待办列表（原型 250px Card） */}
      <Card size="small" style={{ width: 250, flexShrink: 0 }} title={`待办 ${todos.length}`}>
        {loading && todos.length === 0 ? <Spin style={{ display: 'block', margin: '20px auto' }} /> :
          todos.length === 0 ? <Empty description="全部已决策" image={Empty.PRESENTED_IMAGE_SIMPLE} /> :
            todos.map(t => (
              <div key={t.id} className={'xj-ws-item' + (sel?.id === t.id ? ' xj-ws-active' : '')}
                onClick={() => setSel(t)}>
                <div style={{ fontWeight: 600, fontSize: 13 }}>{t.definition_name}</div>
                <div style={{ fontSize: 12, color: '#888', marginTop: 2 }}>
                  <Tag color="purple" style={{ marginRight: 4 }}>产物审阅</Tag>
                  {t.gate_node_name || t.gate_node_id} · {waitSince(t.started_at)}
                </div>
                <div style={{ fontSize: 11, color: '#aaa', marginTop: 2 }} className="xj-mono">
                  run {t.id} · v{t.version}
                </div>
              </div>
            ))}
      </Card>

      {/* 右：决策面板 */}
      <div style={{ flex: 1, minWidth: 0 }}>
        {!sel ? <Empty description="请选择待办" /> : (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <Space wrap>
              <Typography.Title level={5} style={{ margin: 0 }}>
                {sel.definition_name} · {sel.gate_node_name || sel.gate_node_id}
              </Typography.Title>
              <Tag className="xj-mono">run {sel.id}</Tag>
              <Tag color="purple">待审 v{artifactVersion}</Tag>
              <Tag color="orange">等待 {waitSince(sel.started_at)}</Tag>
            </Space>

            <Alert type="info" showIcon message={
              <span>决策门挂起于节点 <b>{sel.gate_node_name || sel.gate_node_id}</b>。
                评审循环：<b>批注意见 → Agent 按批注重生成新版本 → 再审批</b>（可多轮）；
                通过后 run 从该节点恢复执行，批注历史写回决策审计。</span>} />

            {/* 产物区：评审门 Agent 产出的待审版本（v1/v2…） */}
            <div style={{ border: '2px solid #ffd591', borderRadius: 10, padding: 20, background: '#fffdf7', position: 'relative' }}>
              <span style={{ position: 'absolute', top: -12, left: 16, background: '#fff', padding: '0 8px', fontSize: 12, color: '#d46b08' }}>
                待审产物 · v{artifactVersion}（{sel.gate_node_name || sel.gate_node_id} 生成）
              </span>
              {gateOutput
                ? <pre className="xj-mono" style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 320, overflowY: 'auto', fontSize: 13 }}>
                    {renderArtifact(gateOutput)}
                  </pre>
                : <Spin style={{ display: 'block', margin: '20px auto' }} />}
            </div>

            {/* 决策历史（原型 Timeline） */}
            {history.length > 0 && (
              <Card size="small" title="决策历史">
                <Timeline style={{ margin: '8px 0 0 6px' }} items={history.map(h => ({
                  color: h.decision === 'APPROVE' ? 'green' : 'orange',
                  children: (
                    <span style={{ fontSize: 12 }}>
                      <Tag color={h.decision === 'APPROVE' ? 'green' : 'orange'}>{h.decision}</Tag>
                      {h.remark || '（无批注）'}
                      <span style={{ color: '#999' }}> — {h.decided_by} · {fmtTime(h.decided_at)}</span>
                    </span>
                  ),
                }))} />
              </Card>
            )}

            {/* 决策栏（原型底部 Card） */}
            <Card size="small">
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <Input.TextArea rows={2} value={comment} onChange={e => setComment(e.target.value)}
                  placeholder="批注意见（驳回时必填）：例如筛选区增加「下单渠道」下拉；列表默认按金额降序…" />
                <Space wrap>
                  <Button type="primary" loading={acting} onClick={() => void decide('APPROVE')}>
                    通过 — run 从「{sel.gate_node_name || sel.gate_node_id}」恢复执行
                  </Button>
                  <Button danger loading={acting} onClick={() => void decide('REJECT')}>
                    驳回 — Agent 按批注重生成
                  </Button>
                </Space>
              </Space>
            </Card>
          </Space>
        )}
      </div>
    </div>
  )
}
