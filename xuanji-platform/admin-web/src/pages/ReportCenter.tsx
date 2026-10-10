/**
 * 报表中心 —— 对齐原型 07-report-dashboard.html：报表列表 + 看板详情。
 * 报表 = 可创建的定时任务实体（命名 + 来源 workflow 版本钉住 + SCHEDULE 执行计划）。
 * P0 真实数据：报表 CRUD（/api/reports）+ 执行记录（/api/runs?definitionId=）；
 * 看板快照区为诚实空态——board.render 节点与调度执行器实装后，每次执行的看板
 * 输出固化为快照展示（KPI/趋势/明细随之接入），不预置 mock 业务数据。
 */
import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Button, Card, Drawer, Empty, Input, Modal, Popconfirm, Select, Space, Switch, Table, Tag,
  Typography, message,
} from 'antd'
import type { TableProps } from 'antd'
import { apiJson } from '../api/client'

/** 报表行（后端 snake_case） */
interface ReportRow {
  id: string
  name: string
  definition_id: number
  pinned_version: number
  cron_expr: string
  enabled: boolean
  created_by?: string
  created_at?: string
}

/** workflow 候选（创建报表下拉） */
interface WfOption {
  id: number
  name?: string
  status?: string
  current_version?: number | null
  version?: number | null
}

/** 执行记录行（wf_run，运行监控同源） */
interface RunRow {
  id: string
  definition_id: number
  version: number
  trigger_type: string
  status: string
  started_at?: string
  finished_at?: string
  error?: string | null
}

/** 执行计划预设（中文描述 → Spring cron 6 段表达式） */
const CRON_PRESETS: Array<{ label: string; value: string }> = [
  { label: '每日 08:00', value: '0 0 8 * * ?' },
  { label: '每周一 07:30', value: '0 30 7 ? * MON' },
  { label: '每月 1 日 06:00', value: '0 0 6 1 * ?' },
  { label: '每 15 分钟', value: '0 0/15 * * * ?' },
]
const cronLabel = (expr: string) => CRON_PRESETS.find((p) => p.value === expr)?.label ?? expr

const statusTag = (s: string) => (
  <Tag color={s === 'SUCCESS' ? 'green' : s === 'FAILED' ? 'red' : s === 'RUNNING' ? 'blue' : 'default'}>{s}</Tag>
)

/** 嵌入分享 Modal（规划形态：分享地址默认指向最新快照 /latest，可换 runId 钉住历史） */
function ShareModal({ report, open, onClose }: { report: ReportRow; open: boolean; onClose: () => void }) {
  const url = `${location.origin}/share/reports/${report.id}/latest`
  const iframe = `<iframe src="${url}" width="1280" height="860" frameborder="0" loading="lazy"></iframe>`
  return (
    <Modal title={`嵌入分享 · ${report.name}`} open={open} onCancel={onClose} footer={null} width={640}>
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message="分享功能即将上线：上线后分享地址默认指向最新报表，嵌入方无需改地址即可看到最新内容；如需固定某次历史报表，可将地址中的 latest 替换为该次执行编号。"
      />
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>
        分享链接（只读看板 · 默认最新快照）
      </Typography.Paragraph>
      <Typography.Paragraph copyable className="xj-mono" style={{ fontSize: 12 }}>{url}</Typography.Paragraph>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>iframe 嵌入代码</Typography.Paragraph>
      <Typography.Paragraph
        copyable={{ text: iframe }}
        className="xj-mono"
        style={{ fontSize: 12, background: '#fafafa', border: '1px solid #f0f0f0', borderRadius: 6, padding: 8, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}
      >{iframe}</Typography.Paragraph>
    </Modal>
  )
}

/** 创建报表 Modal（来源 workflow 必须已发布；执行计划预设选择） */
function CreateModal({
  open, onClose, onCreated,
}: {
  open: boolean
  onClose: () => void
  onCreated: () => void
}) {
  const [name, setName] = useState('')
  const [wfId, setWfId] = useState<number | null>(null)
  const [cron, setCron] = useState(CRON_PRESETS[0].value)
  const [wfs, setWfs] = useState<WfOption[]>([])
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open) return
    setName('')
    setWfId(null)
    setCron(CRON_PRESETS[0].value)
    void apiJson<WfOption[]>('/api/definitions')
      .then((list) => setWfs((list || []).filter((w) => w.status === 'PUBLISHED')))
      .catch(() => setWfs([]))
  }, [open])

  const submit = async () => {
    if (!name.trim() || !wfId) {
      message.warning('请填写报表名称并选择来源 workflow')
      return
    }
    setBusy(true)
    try {
      await apiJson('/api/reports', 'POST', {
        name: name.trim(),
        definition_id: wfId,
        cron_expr: cron,
      })
      message.success('报表已创建：调度执行器实装后将按计划定时执行，每次执行固化一份看板快照')
      onCreated()
      onClose()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title="创建报表"
      open={open}
      onCancel={onClose}
      onOk={() => void submit()}
      confirmLoading={busy}
      okText="创建"
      width={580}
      destroyOnClose
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <div>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>报表名称</Typography.Paragraph>
          <Input placeholder="例如：销售运营日报" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>
            来源工作流（固定版本；须包含看板输出节点，创建校验即将上线）
          </Typography.Paragraph>
          <Select
            style={{ width: '100%' }}
            placeholder="选择已发布 workflow"
            value={wfId}
            onChange={setWfId}
            options={wfs.map((w) => ({
              value: w.id,
              label: `${w.name || 'workflow-' + w.id} @v${w.current_version ?? w.version ?? '-'}`,
            }))}
          />
        </div>
        <div>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>
            定时执行计划（SCHEDULE 触发器——调度执行器实装后生效）
          </Typography.Paragraph>
          <Select style={{ width: '100%' }} value={cron} onChange={setCron}
            options={CRON_PRESETS.map((p) => ({ value: p.value, label: p.label }))} />
        </div>
        <Alert
          type="info"
          showIcon
          message="创建后平台按计划定时执行：每次执行 = 一次 run → 固化一份看板快照。报表详情默认展示最新快照，历史快照可回看审计；分享地址默认始终指向最新报表。"
        />
      </Space>
    </Modal>
  )
}

/** 看板快照数据结构（basic.board_render 节点产出） */
interface BoardSnapshot {
  title?: string
  kpis?: Array<{ name?: string; value?: unknown; unit?: string }>
  columns?: Array<{ key: string; title: string }>
  rows?: Array<Record<string, unknown>>
  analysis?: string
  generatedAt?: string
}

/** 快照行（report_snapshot 表） */
interface SnapshotRow {
  id: string
  run_id: string
  snapshot_json: string
  created_at?: string
}

/** 看板快照渲染：KPI 卡片 + 明细表（数据来自真实 run 输出，无快照时诚实空态） */
function SnapshotView({ snapshots }: { snapshots: SnapshotRow[] }) {
  const [idx, setIdx] = useState(0)
  if (snapshots.length === 0) {
    return (
      <Empty
        description={
          <span style={{ fontSize: 12, color: '#888' }}>
            暂无看板快照：为报表来源 workflow 加入「看板渲染」节点（basic.board_render）后，
            每次调度执行的看板输出将固化为快照在此展示（KPI / 明细表）。
          </span>
        }
      />
    )
  }
  const snap: BoardSnapshot = (() => {
    try {
      return JSON.parse(snapshots[idx].snapshot_json) as BoardSnapshot
    } catch {
      return {}
    }
  })()
  const kpis = snap.kpis ?? []
  const columns = (snap.columns ?? []).map((c) => ({ title: c.title, dataIndex: c.key, key: c.key }))
  const rows = (snap.rows ?? []).map((r, i) => ({ ...r, __key: i }))
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {snapshots.length > 1 && (
        <Select
          size="small"
          style={{ width: 320 }}
          value={idx}
          onChange={setIdx}
          options={snapshots.map((s, i) => ({
            value: i,
            label: `${i === 0 ? '最新' : '历史'}快照 · ${String(s.created_at ?? '')} · run ${s.run_id.slice(0, 12)}…`,
          }))}
        />
      )}
      {kpis.length > 0 && (
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          {kpis.map((k, i) => (
            <Card key={i} size="small" style={{ minWidth: 160, flex: '1 1 160px' }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{k.name}</Typography.Text>
              <div style={{ fontSize: 24, fontWeight: 600, lineHeight: '32px' }}>
                {String(k.value ?? '—')}
                {k.unit && <span style={{ fontSize: 12, fontWeight: 400, color: '#888', marginLeft: 4 }}>{k.unit}</span>}
              </div>
            </Card>
          ))}
        </div>
      )}
      {columns.length > 0 && (
        <Table size="small" rowKey="__key" pagination={{ pageSize: 8 }} columns={columns} dataSource={rows} />
      )}
      {snap.analysis && (
        <Card size="small" title="Agent 分析结论">
          <Typography.Paragraph style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
            {snap.analysis}
          </Typography.Paragraph>
        </Card>
      )}
      {kpis.length === 0 && columns.length === 0 && !snap.analysis && (
        <Empty description="快照内容为空（看板渲染节点未产出 KPI/明细/分析）" />
      )}
    </Space>
  )
}

/** 看板详情：头部操作 + 数据来源说明 + 看板快照区（真实快照渲染）+ 执行记录 Drawer */
function Dashboard({ report, onBack }: { report: ReportRow; onBack: () => void }) {
  const [runs, setRuns] = useState<RunRow[]>([])
  const [snapshots, setSnapshots] = useState<SnapshotRow[]>([])
  const [logOpen, setLogOpen] = useState(false)
  const [shareOpen, setShareOpen] = useState(false)

  useEffect(() => {
    void apiJson<RunRow[]>(`/api/runs?definitionId=${report.definition_id}&limit=50`)
      .then(setRuns)
      .catch(() => setRuns([]))
    void apiJson<SnapshotRow[]>(`/api/reports/${report.id}/snapshots`)
      .then(setSnapshots)
      .catch(() => setSnapshots([]))
  }, [report.definition_id, report.id])

  const latest = runs[0]
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Space wrap>
        <Button onClick={onBack}>← 返回列表</Button>
        <Typography.Title level={4} style={{ margin: 0 }}>{report.name}</Typography.Title>
        <Tag color="geekblue" className="xj-mono">
          workflow:{report.definition_id}@v{report.pinned_version}
        </Tag>
        <Tag color="blue">SCHEDULE {cronLabel(report.cron_expr)}</Tag>
        {!report.enabled && <Tag>已停用</Tag>}
        <span style={{ flex: 1 }} />
        <Button onClick={() => setLogOpen(true)}>执行记录</Button>
        <Button onClick={() => setShareOpen(true)}>嵌入分享</Button>
      </Space>
      <Alert
        type="info"
        showIcon
        message={
          <span>
            <b>数据来源：外部业务系统真实业务数据</b>——非平台自身数据。链路：SCHEDULE 定时触发（
            {cronLabel(report.cron_expr)}）→ 数据源连接器查询业务库 → 数据加工节点（聚合/对齐）→ Agent 分析摘要
            → 看板渲染节点生成页面 → <b>固化为本快照</b>。默认展示最新报表，可切换历史快照回看审计。
          </span>
        }
      />
      <Card size="small" title="看板快照">
        <SnapshotView snapshots={snapshots} />
      </Card>
      <Card size="small" title="最近执行">
        {latest ? (
          <Space size={16}>
            <Typography.Text className="xj-mono" style={{ fontSize: 12 }}>{latest.id}</Typography.Text>
            {statusTag(latest.status)}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{latest.started_at}</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              触发：{latest.trigger_type}
            </Typography.Text>
          </Space>
        ) : (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            暂无执行记录：调度执行器实装后，按计划「SCHEDULE {cronLabel(report.cron_expr)}」自动执行
          </Typography.Text>
        )}
      </Card>
      <Drawer title={`执行记录 · ${report.name}`} width={760} open={logOpen} onClose={() => setLogOpen(false)}>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={`按执行计划「SCHEDULE ${cronLabel(report.cron_expr)}」定时执行；每次执行固化一份看板快照，最新一次即报表页默认展示的最新报表。`}
        />
        <Table<RunRow>
          size="small"
          rowKey="id"
          pagination={false}
          dataSource={runs}
          columns={[
            { title: '执行时间', dataIndex: 'started_at' },
            { title: '触发方式', dataIndex: 'trigger_type', render: (v: string) => <Tag color="blue">{v}</Tag> },
            { title: '状态', dataIndex: 'status', render: (v: string) => statusTag(v) },
            { title: '生成 run', dataIndex: 'id', className: 'xj-mono' },
          ]}
        />
      </Drawer>
      <ShareModal report={report} open={shareOpen} onClose={() => setShareOpen(false)} />
    </Space>
  )
}

/** 主页面：报表列表（查询列表模式）→ 点击进入看板详情 */
export default function ReportCenter() {
  const [rows, setRows] = useState<ReportRow[]>([])
  const [latestMap, setLatestMap] = useState<Record<string, RunRow | null>>({})
  const [current, setCurrent] = useState<ReportRow | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [shareRow, setShareRow] = useState<ReportRow | null>(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await apiJson<ReportRow[]>('/api/reports')
      setRows(data)
      // 最新执行列：按报表来源 workflow 并行拉最近一条 run（报表数量少，N+1 可接受）
      const entries = await Promise.all(data.map(async (r) => {
        try {
          const runs = await apiJson<RunRow[]>(`/api/runs?definitionId=${r.definition_id}&limit=1`)
          return [r.id, runs[0] ?? null] as const
        } catch {
          return [r.id, null] as const
        }
      }))
      setLatestMap(Object.fromEntries(entries))
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const remove = async (id: string) => {
    try {
      await apiJson(`/api/reports/${id}`, 'DELETE')
      message.success('已删除')
      void load()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    }
  }

  const toggle = async (r: ReportRow, enabled: boolean) => {
    try {
      await apiJson(`/api/reports/${r.id}`, 'PUT', { enabled })
      message.success(enabled ? '已启用：调度执行器按计划定时执行' : '已停用：调度任务已注销')
      void load()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    }
  }

  const columns: TableProps<ReportRow>['columns'] = [
    {
      title: '报表名称',
      dataIndex: 'name',
      render: (v: string, r) => <a onClick={() => setCurrent(r)}>{v}</a>,
    },
    {
      title: '来源工作流（固定版本）',
      dataIndex: 'definition_id',
      className: 'xj-mono',
      render: (v: number, r) => `workflow:${v}@v${r.pinned_version}`,
    },
    {
      title: '执行计划',
      dataIndex: 'cron_expr',
      render: (v: string, r) => (
        <span>
          <Tag color="blue">SCHEDULE {cronLabel(v)}</Tag>
          {!r.enabled && <Tag>已停用</Tag>}
        </span>
      ),
    },
    {
      title: '最新执行',
      key: 'latest',
      render: (_, r) => {
        const last = latestMap[r.id]
        return last ? (
          <span>
            {String(last.started_at ?? '')} {statusTag(last.status)}
          </span>
        ) : (
          '—'
        )
      },
    },
    {
      title: '操作',
      key: 'op',
      render: (_, r) => (
        <Space>
          <a onClick={() => setCurrent(r)}>查看看板</a>
          <a onClick={() => setShareRow(r)}>嵌入分享</a>
          <Switch
            size="small"
            checked={r.enabled}
            checkedChildren="启用"
            unCheckedChildren="停用"
            onChange={(v) => void toggle(r, v)}
          />
          <Popconfirm title="删除该报表？" onConfirm={() => void remove(r.id)}>
            <a style={{ color: '#ff4d4f' }}>删除</a>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  if (current) {
    return <Dashboard key={current.id} report={current} onBack={() => setCurrent(null)} />
  }
  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Button type="primary" onClick={() => setCreateOpen(true)}>＋ 创建报表</Button>
      </div>
      <Table<ReportRow>
        rowKey="id"
        size="middle"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: <Empty description="暂无报表：点击「＋ 创建报表」创建（来源 workflow 须已发布）" /> }}
      />
      <CreateModal open={createOpen} onClose={() => setCreateOpen(false)} onCreated={() => void load()} />
      {shareRow && <ShareModal report={shareRow} open onClose={() => setShareRow(null)} />}
    </div>
  )
}
