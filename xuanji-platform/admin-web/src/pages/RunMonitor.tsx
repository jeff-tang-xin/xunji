/**
 * 运行监控 —— 对齐原型 02-run-monitor.html：run 列表 → 点击进入详情视图。
 * 详情 = 左 Card（Steps 垂直步骤条呈现节点序列）+ 右 Card（选中节点 INPUT/OUTPUT）。
 * 数据源 GET /api/runs、GET /api/runs/{id}；SUSPENDED（决策门挂起）时 Alert 提示。
 */
import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Select, Space, Steps, Table, Tag, Typography, message } from 'antd'
import { apiJson } from '../api/client'

/** run 列表行（后端 snake_case） */
interface RunRow {
  id: string
  definition_id: number
  version: number
  trigger_type: string
  status: string
  app_id?: string | null
  started_at?: string
  finished_at?: string
  error?: string | null
}

/** 节点执行行（RunService.getRun().nodes） */
interface NodeRunRow {
  id: number
  node_id: string
  node_name?: string
  seq: number
  status: string
  input_json?: string | null
  output_json?: string | null
  error?: string | null
  started_at?: string
  finished_at?: string
}

interface AssetOptionRow {
  id: number
  name?: string
}

const STATUS_COLOR: Record<string, string> = {
  RUNNING: 'blue', SUCCESS: 'green', FAILED: 'red', SUSPENDED: 'orange',
}

/** run 状态 → 中文文案（列表/详情 Tag） */
const STATUS_TEXT: Record<string, string> = {
  RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败', SUSPENDED: '已挂起', CANCELLED: '已取消',
}

/** 节点状态 → Steps status（对齐原型：finish/process/error/wait） */
function stepStatus(n: NodeRunRow): 'finish' | 'process' | 'error' | 'wait' {
  if (n.status === 'SUCCESS') return 'finish'
  if (n.status === 'RUNNING') return 'process'
  if (n.status === 'FAILED' || n.status === 'SUSPENDED') return 'error'
  return 'wait'
}

function prettyJson(v: string | null | undefined): string {
  if (!v) return '—'
  try {
    return JSON.stringify(JSON.parse(v), null, 2)
  } catch {
    return v
  }
}

/** 详情视图：左 Steps + 右节点 IO（对齐原型 RunDetail） */
function RunDetail({ run, onBack }: { run: RunRow; onBack: () => void }) {
  const [nodes, setNodes] = useState<NodeRunRow[]>([])
  const [sel, setSel] = useState(0)
  const [runError, setRunError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    apiJson<RunRow & { nodes?: NodeRunRow[] }>(`/api/runs/${run.id}`)
      .then((d) => {
        if (!alive) return
        setNodes(d.nodes || [])
        setRunError(d.error || null)
        // 默认选中最后一个已执行的节点
        const list = d.nodes || []
        const lastDone = list.map((n) => n.status).lastIndexOf('SUCCESS')
        setSel(lastDone >= 0 ? lastDone : 0)
      })
      .catch((e: Error) => message.error(e.message))
    return () => { alive = false }
  }, [run.id])

  const suspended = run.status === 'SUSPENDED'
  const cur = nodes[sel]

  return (
    <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
      <Card
        size="small"
        style={{ flex: 1, minWidth: 0 }}
        title={
          <Space>
            <Button size="small" onClick={onBack}>← 返回</Button>
            <span className="xj-mono">执行 {run.id}</span>
            <Tag color={STATUS_COLOR[run.status] || 'default'}>
              {suspended ? '待审批' : STATUS_TEXT[run.status] || run.status}
            </Tag>
          </Space>
        }
      >
        {suspended && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="流程已暂停：等待人工审批，确认后将从当前节点继续执行"
          />
        )}
        {runError && (
          <Alert type="error" showIcon style={{ marginBottom: 12 }} message={runError} />
        )}
        {nodes.length === 0 ? (
          <Typography.Text type="secondary">暂无节点执行记录</Typography.Text>
        ) : (
          <Steps
            direction="vertical"
            size="small"
            current={sel}
            onChange={(i) => setSel(i)}
            items={nodes.map((n, i) => ({
              title: n.node_name || n.node_id,
              description: (
                <span className="xj-mono" style={{ fontSize: 11 }}>
                  {n.node_id}
                  {n.status === 'SUSPENDED' ? ' ⚡等待审批' : ''}
                </span>
              ),
              status: stepStatus(n),
              // Steps onChange 只对可达项触发；已执行/当前项可点
              style: i <= sel ? { cursor: 'pointer' } : undefined,
            }))}
          />
        )}
      </Card>
      <Card size="small" style={{ width: 420, flexShrink: 0 }} title={`节点输入 / 输出 · ${cur ? cur.node_name || cur.node_id : '—'}`}>
        {cur ? (
          <>
            <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>INPUT</Typography.Paragraph>
            <pre className="xj-mono" style={{ background: '#fafafa', padding: 10, borderRadius: 6, maxHeight: 220, overflow: 'auto' }}>
              {prettyJson(cur.input_json)}
            </pre>
            <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>OUTPUT</Typography.Paragraph>
            <pre className="xj-mono" style={{ background: '#fafafa', padding: 10, borderRadius: 6, maxHeight: 220, overflow: 'auto' }}>
              {cur.error ? cur.error : prettyJson(cur.output_json)}
            </pre>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              事件流：run.start / node.start / node.finish / run.suspended / run.finish（detail/03 §7）
            </Typography.Text>
          </>
        ) : (
          <Typography.Text type="secondary">选择左侧节点查看输入 / 输出</Typography.Text>
        )}
      </Card>
    </div>
  )
}

/** 主页面：列表 → 详情（对齐原型 App：sel ? RunDetail : Table） */
export default function RunMonitor() {
  const [rows, setRows] = useState<RunRow[]>([])
  const [loading, setLoading] = useState(false)
  const [definitionId, setDefinitionId] = useState<number | undefined>()
  const [status, setStatus] = useState<string | undefined>()
  const [defs, setDefs] = useState<AssetOptionRow[]>([])
  const [sel, setSel] = useState<RunRow | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const qs = new URLSearchParams()
      if (definitionId !== undefined) qs.set('definitionId', String(definitionId))
      if (status) qs.set('status', status)
      setRows(await apiJson<RunRow[]>(`/api/runs?${qs.toString()}`))
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setLoading(false)
    }
  }, [definitionId, status])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    apiJson<AssetOptionRow[]>('/api/definitions')
      .then((list) => setDefs(list || []))
      .catch(() => undefined)
  }, [])

  const defName = (r: RunRow) => {
    const d = defs.find((x) => x.id === r.definition_id)
    return (d?.name || `#${r.definition_id}`) + `@v${r.version}`
  }

  if (sel) {
    return <RunDetail run={sel} onBack={() => { setSel(null); void load() }} />
  }

  return (
    <div>
      <Space style={{ marginBottom: 16 }} wrap>
        <Select allowClear placeholder="按工作流筛选" style={{ width: 220 }} value={definitionId}
          onChange={(v) => setDefinitionId(v)}
          options={defs.map((d) => ({ value: d.id, label: `#${d.id} ${d.name || '(未命名)'}` }))} />
        <Select allowClear placeholder="按状态筛选" style={{ width: 150 }} value={status}
          onChange={(v) => setStatus(v)}
          options={([['RUNNING', '运行中'], ['SUCCESS', '已完成'], ['FAILED', '失败'], ['SUSPENDED', '待审批']] as const).map(([v, l]) => ({ value: v, label: l }))} />
        <Button onClick={() => void load()}>刷新</Button>
      </Space>
      <Table<RunRow> rowKey="id" loading={loading} dataSource={rows} pagination={{ pageSize: 15 }}
        columns={[
          {
            title: 'Run ID', dataIndex: 'id', width: 260,
            render: (v: string, r) => <a className="xj-mono" onClick={() => setSel(r)}>{v}</a>,
          },
          { title: '工作流（版本）', width: 220, render: (_, r) => defName(r) },
          { title: '触发方式', dataIndex: 'trigger_type', width: 110 },
          { title: 'App', dataIndex: 'app_id', width: 140, render: (v: string | null) => v || '-' },
          { title: '开始时间', dataIndex: 'started_at', width: 170, render: (v: string) => (v || '').replace('T', ' ').slice(0, 19) },
          {
            title: '状态', dataIndex: 'status', width: 110,
            render: (v: string) => <Tag color={STATUS_COLOR[v] || 'default'}>{v}</Tag>,
          },
          { title: '操作', width: 80, render: (_, r) => <a onClick={() => setSel(r)}>查看</a> },
        ]} />
    </div>
  )
}
