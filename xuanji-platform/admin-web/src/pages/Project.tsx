/**
 * 项目看板 —— 软件项目全生命周期管理（设计文档 docs/sdlc-lifecycle-design.md 阶段 D）。
 * 双线路：项目列表（数据驱动加载）→ 项目详情（阶段链 DAG 可视化 + 节点操作 + 留档）。
 * 创建线路：POST /api/projects（模板驱动生成节点链）；加载线路：GET /api/projects/{id}。
 * 节点操作：run（校验 DAG 依赖）/ complete（显式置 DONE）/ artifacts（查看/登记留档）。
 */
import { useCallback, useEffect, useRef, useState, type ReactNode, type CSSProperties } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert, Button, Card, Col, Collapse, Drawer, Empty, Form, Input,
  Modal, Row, Select, Space, Spin, Steps, Table, Tabs, Tag,
  Tooltip, Typography, Upload, message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  PlusOutlined, ArrowLeftOutlined, PlayCircleOutlined, CheckCircleOutlined,
  FileTextOutlined, FolderOpenOutlined, ReloadOutlined,
  StopOutlined, ThunderboltOutlined, PaperClipOutlined, SendOutlined,
  ProfileOutlined, SyncOutlined, LockOutlined, ForwardOutlined, SettingOutlined,
} from '@ant-design/icons'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { apiFetch, apiJson } from '../api/client'

/**
 * 阶段执行视图（GET /api/projects/{id}/stage-execution）。
 * state 由后端按 run 状态推导，前端不自己判：
 * NO_PLAN 无规划 / PENDING_APPROVAL 规划待批准 / NOT_STARTED 已批准未起跑 /
 * EXECUTING 执行中 / WAITING_DECISION 等人工决策 / FINISHED 本轮跑完。
 */
interface StageExecution {
  stage?: string
  stage_label?: string
  iteration?: number
  plan?: { id?: number; status?: string; tasks?: unknown[]; exec_run_id?: string | null } | null
  state: string
  run_id?: string
  run_status?: string
  definition_id?: number | string
  error?: string | null
  node_runs?: NodeRunRow[]
  waiting_decisions?: WaitingDecision[]
  /** 新内核（AI 编排）模式：step 台账原文；非空即 orchestrated 模式（右边栏只读化） */
  steps?: StageStepRow[]
}

/** 单个节点的执行记录（wf_node_run，按 seq 升序） */
interface NodeRunRow {
  id: number | string
  node_id: string
  node_name: string
  seq: number
  status: string
  error?: string | null
  started_at?: string
  finished_at?: string
  output_json?: string | null
}

/** 待人工决策的节点：引擎语义是「先产出再挂起」，节点自身已记 SUCCESS，挂起点在 wf_run.gate_node_id */
interface WaitingDecision {
  node_run_id: number | string
  node_id: string
  node_name: string
  output?: string | null
}

/** 规划子任务（plan_json.tasks 元素，由 StagePlanCompiler 编译成串行节点） */
interface PlanTask {
  key?: number
  name?: string
  agents?: string[]
  participants?: string[]
  approval_mode?: string
  scope?: string
  depends?: number[]
}


/**
 * 模板节点 —— 阶段「通用层」知识（跨项目复用，作为 AI 的工作指导）。
 * 注意：不含项目任务边界（scope），scope 属于项目层，在新建项目时按节点填写。
 * agent_name：该阶段由哪个内置 agent 执行（留空则后端按 owner_role 映射回退）。
 * workflow_def_id：该阶段默认跑哪个已发布工作流；创建项目时快照进节点 workflow_ref。
 */
interface TemplateNode {
  type: string
  seq: number
  owner_role?: string
  agent_name?: string | null
  workflow_def_id?: number | null
  standards?: string[]
  requirements?: string[]
  methodology?: string
  deliverable?: string
  depends_on?: number[]
  preconditions?: string[]
}

/** 智能体候选（GET /api/agents） */
interface AgentOption {
  id: number
  name: string
  description?: string
  status?: string
}

/** 工作流候选（GET /api/definitions）：仅 PUBLISHED 可绑定 */
interface WorkflowOption {
  id: number
  name: string
  status?: string
  current_version?: number
}

/** 执行态中文 */
const EXEC_STATE_LABEL: Record<string, string> = {
  NO_PLAN: '尚无规划',
  PENDING_APPROVAL: '规划就绪',
  NOT_STARTED: '已批准，未起跑',
  EXECUTING: '执行中',
  WAITING_DECISION: '等待人工决策',
  FINISHED: '本轮已跑完',
}

/** 执行态颜色 */
const EXEC_STATE_COLOR: Record<string, string> = {
  NO_PLAN: 'default',
  PENDING_APPROVAL: 'orange',
  NOT_STARTED: 'blue',
  EXECUTING: 'processing',
  WAITING_DECISION: 'gold',
  FINISHED: 'green',
}

/** 节点执行状态中文（wf_node_run.status） */
const NODE_RUN_STATUS_LABEL: Record<string, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  SUCCESS: '完成',
  FAILED: '失败',
  SKIPPED: '跳过',
}

const NODE_RUN_STATUS_COLOR: Record<string, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  SKIPPED: 'orange',
}

/** 需要持续拉取执行视图的状态（跑完/无规划就不必轮询） */
const EXEC_LIVE_STATES = new Set(['EXECUTING', 'WAITING_DECISION', 'NOT_STARTED'])

/**
 * 编排步骤（新内核黑板台账，GET /stage-execution 的 steps 字段）。
 * 字段名与后端 StageStep record 的 Jackson 序列化一致（驼峰）。
 */
interface StageStepRow {
  id: number
  seq: number
  name: string
  stepType: 'TASK' | 'DISCUSS' | 'DECISION'
  agent?: string | null
  participants?: string[]
  status: 'PLANNED' | 'EXECUTING' | 'DONE' | 'SKIPPED' | 'FAILED' | 'WAITING'
  /** TASK=指令 / DECISION=向用户提出的问题 */
  instruction?: string | null
  outputSummary?: string | null
  outputFull?: string | null
  artifactId?: number | null
  skipReason?: string | null
  error?: string | null
  createdAt?: string
  startedAt?: string
  finishedAt?: string
}

/** 步骤状态中文（与后端 StepStatus 对齐） */
const STEP_STATUS_LABEL: Record<string, string> = {
  PLANNED: '待执行',
  EXECUTING: '执行中',
  DONE: '完成',
  SKIPPED: '已跳过',
  FAILED: '失败',
  WAITING: '等你答复',
}

const STEP_STATUS_COLOR: Record<string, string> = {
  PLANNED: 'default',
  EXECUTING: 'processing',
  DONE: 'success',
  SKIPPED: 'orange',
  FAILED: 'error',
  WAITING: 'gold',
}

/** 步骤类型中文 */
const STEP_TYPE_LABEL: Record<string, string> = {
  TASK: '任务',
  DISCUSS: '讨论',
  DECISION: '决策',
}

/** 项目行（后端 snake_case） */
interface ProjectRow {
  id: string
  name: string
  root_path: string
  status: string
  created_by?: string
  created_at?: string
}

/** 工作节点 */
interface WorkNode {
  id: string
  workspace_id: string
  type: string
  seq: number
  status: string
  depends_on?: string | string[] | null
  preconditions?: string | string[] | null
  workflow_ref?: string | null
  input_refs?: string | string[] | null
  output_refs?: string | string[] | null
  owner_role?: string
}

/** 留档 */
interface Artifact {
  id: number
  workspace_id: string
  node_id?: string
  kind: string
  name: string
  type: string
  file_path?: string
  content_md?: string
  version?: number | null
  status: string
  /** 产出阶段（V29）：阶段对话自动登记的交付物带 stage，前端按阶段分组 */
  stage?: string | null
  created_by?: string
  created_at?: string
}

/** 项目详情 */
interface ProjectDetail {
  id: string
  name: string
  root_path: string
  status: string
  created_by?: string
  created_at?: string
  /** 当前阶段（V27 阶段生命周期） */
  current_stage?: string
  nodes: WorkNode[]
  artifacts: Artifact[]
}

/** 节点类型 → 中文名（产品语言） */
const NODE_TYPE_LABEL: Record<string, string> = {
  DISCUSS: '讨论',
  REQUIREMENT: '需求',
  ARCHITECTURE: '架构',
  DESIGN: '详设',
  TEST_CASE: '测试用例',
  DEVELOP: '开发',
  SMOKE: '冒烟',
  SUBMIT: '提测',
  TEST: '测试执行',
  RELEASE: '发布',
}

/** 节点状态 → 颜色与文案 */
function nodeStatusView(s?: string): { color: string; text: string } {
  switch (s) {
    case 'DONE': return { color: 'green', text: '已完成' }
    case 'RUNNING': return { color: 'blue', text: '执行中' }
    case 'BLOCKED': return { color: 'orange', text: '阻塞' }
    case 'WAITING_APPROVAL': return { color: 'orange', text: '待审批' }
    case 'REJECTED': return { color: 'red', text: '已驳回' }
    default: return { color: 'default', text: '未开始' }
  }
}

/** 项目状态 → 颜色 */
function projectStatusColor(s?: string): string {
  return s === 'ARCHIVED' ? 'default' : 'green'
}

/** 安全格式化日期（非法/空值返回 '—'） */
function fmtDate(v?: string): string {
  if (!v) return '—'
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleDateString()
}

/** 解析 JSON 字符串或数组为字符串数组 */
function parseList(v: string | string[] | null | undefined): string[] {
  if (!v) return []
  if (Array.isArray(v)) return v.map(String)
  try {
    const parsed = JSON.parse(v)
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

/** 新建项目弹窗 */
/** 模板管理入口开关：模板编辑器就绪后置 true（列表页「模板管理」按钮 + 新建项目模板下拉） */
const SHOW_TEMPLATE_ENTRY = true

function CreateProjectModal({ open, onClose, onCreated }: {
  open: boolean
  onClose: () => void
  onCreated: (id: string) => void
}) {
  const [form] = Form.useForm()
  const [busy, setBusy] = useState(false)
  const [templates, setTemplates] = useState<ProjectTemplate[]>([])
  const [workflows, setWorkflows] = useState<WorkflowOption[]>([])
  const [datasources, setDatasources] = useState<DsOption[]>([])

  // 打开时从后端动态加载模板与已发布工作流；失败回退默认，不阻断创建
  useEffect(() => {
    if (!open) return
    let cancelled = false
    apiJson<ProjectTemplate[]>('/api/projects/templates')
      .then((list) => { if (!cancelled && Array.isArray(list) && list.length > 0) setTemplates(list) })
      .catch(() => { /* 加载失败保留默认 */ })
    apiJson<WorkflowOption[]>('/api/definitions')
      .then((list) => { if (!cancelled) setWorkflows((list || []).filter((x) => x.status === 'PUBLISHED')) })
      .catch(() => { /* 加载失败下拉为空 */ })
    apiJson<DsOption[]>('/api/datasources')
      .then((list) => { if (!cancelled) setDatasources(Array.isArray(list) ? list : []) })
      .catch(() => { if (!cancelled) setDatasources([]) })
    return () => { cancelled = true }
  }, [open])

  const submit = async () => {
    const v = await form.validateFields().catch(() => null)
    if (!v) return
    setBusy(true)
    try {
      const body: Record<string, unknown> = { name: v.name, template: v.template || 'standard' }
      // 需求阶段绑定工作流（可跳过）：创建即预设执行路径，批准规划后直接按该工作流执行
      const reqWf = (v.requirement_workflow || '').toString().trim()
      if (reqWf) body.requirement_workflow = reqWf
      // 运维资源绑定（可跳过）：写入 OPERATE 阶段能力绑定，与模板预设 merge
      const servers = (v.servers || []) as string[]
      const connectors = (v.connectors || []) as string[]
      if (servers.length > 0) body.servers = servers
      if (connectors.length > 0) body.connectors = connectors
      const data = await apiJson<{ id: string }>('/api/projects', 'POST', body)
      message.success('项目创建成功')
      onCreated(data.id)
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title="新建项目"
      open={open}
      onCancel={onClose}
      onOk={() => void submit()}
      confirmLoading={busy}
      okText="创建"
      cancelText="取消"
    >
      <Form form={form} layout="vertical" initialValues={{ template: 'standard' }}>
        <Form.Item
          name="name"
          label="项目名"
          rules={[{ required: true, message: '请输入项目名' }]}
        >
          <Input placeholder="如：玄机万象平台" maxLength={128} />
        </Form.Item>
        <Form.Item
          name="template"
          label="项目模板"
          extra="模板提供各阶段的通用规范与方法论（跨项目复用）"
          hidden={!SHOW_TEMPLATE_ENTRY}
        >
          <Select
            options={templates.map((tp) => {
              const hasStages = tp.stages && Object.keys(tp.stages).length > 0
              const desc = hasStages ? tp.description : (tp.description ? `${tp.description}；无阶段预设，按 PM 规划执行` : '无阶段预设，按 PM 规划执行')
              return { value: tp.code, label: desc ? `${tp.name}（${desc}）` : tp.name }
            })}
          />
        </Form.Item>
        <Form.Item
          name="requirement_workflow"
          label="需求阶段绑定工作流（可选）"
          extra="绑定后批准规划即按该工作流执行，不再重新编译；留空则按 PM 规划编译。仅列已发布工作流"
        >
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="不绑定——按 PM 规划编译执行"
            options={workflows.map((w) => ({ value: String(w.id), label: w.name }))}
          />
        </Form.Item>
        <Form.Item
          name="servers"
          label="运维服务器（可选）"
          extra="部署 / 巡检 / 变更的目标机器，写入运维阶段能力绑定；输入后回车添加"
        >
          <Select
            mode="tags"
            placeholder="如 root@1.2.3.4:22（回车确认）"
            tokenSeparators={[' ']}
            open={false}
          />
        </Form.Item>
        <Form.Item
          name="connectors"
          label="可查询数据库（可选）"
          extra="运维排障时 agent 可直接查询的数据源连接器（JDBC）；写入运维阶段能力绑定"
        >
          <Select
            mode="multiple"
            allowClear
            placeholder={datasources.length ? '选择可用的数据库（可留空）' : '未加载数据源，请先在「数据源」页添加'}
            options={datasources.map((d) => ({
              value: `connector:${d.id}`,
              label: `${d.name}（${d.platform || 'jdbc'} #${d.id}）`,
            }))}
          />
        </Form.Item>
      </Form>
    </Modal>
  )
}

function NodeDrawer({ node, nodes, artifacts, onClose, onRun, onComplete }: {
  node: WorkNode | null
  nodes: WorkNode[]
  artifacts: Artifact[]
  onClose: () => void
  onRun: (n: WorkNode) => void
  onComplete: (n: WorkNode) => void
}) {
  const [busy, setBusy] = useState(false)
  if (!node) return null
  const sv = nodeStatusView(node.status)
  const deps = parseList(node.depends_on)
  const pres = parseList(node.preconditions)
  const outputs = parseList(node.output_refs)
  const nodeArtifacts = artifacts.filter((a) => a.node_id === node.id)
  // 节点 id → 可读名称（ws_xxx_n3 → 详设（4）），依赖/前置条件不再显示原始 id
  const nodeName = (id: string) => {
    const n = nodes.find((x) => x.id === id)
    return n ? `${NODE_TYPE_LABEL[n.type] || n.type}（${n.seq}）` : id
  }

  const run = async () => {
    setBusy(true)
    try {
      await onRun(node)
    } finally {
      setBusy(false)
    }
  }
  const complete = async () => {
    setBusy(true)
    try {
      await onComplete(node)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer
      title={`${NODE_TYPE_LABEL[node.type] || node.type} · 节点 ${node.seq}`}
      open={!!node}
      onClose={onClose}
      width={420}
      extra={
        <Space>
          <Button
            size="small"
            icon={<PlayCircleOutlined />}
            type="primary"
            loading={busy}
            onClick={() => void run()}
            disabled={node.status === 'DONE' || node.status === 'RUNNING'}
          >
            执行
          </Button>
          <Button
            size="small"
            icon={<CheckCircleOutlined />}
            loading={busy}
            onClick={() => void complete()}
            disabled={node.status === 'DONE'}
          >
            标记完成
          </Button>
        </Space>
      }
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Typography.Text type="secondary">状态：</Typography.Text>
          <Tag color={sv.color}>{sv.text}</Tag>
          <Typography.Text type="secondary">职能：</Typography.Text>
          <Tag>{node.owner_role || '—'}</Tag>
        </div>

        <div>
          <Typography.Text strong>依赖节点</Typography.Text>
          <div style={{ marginTop: 4 }}>
            {deps.length === 0
              ? <Typography.Text type="secondary">无（起始节点）</Typography.Text>
              : deps.map((d) => <Tag key={d} color="blue">{nodeName(d)}</Tag>)}
          </div>
        </div>

        <div>
          <Typography.Text strong>前置条件</Typography.Text>
          <div style={{ marginTop: 4 }}>
            {pres.length === 0
              ? <Typography.Text type="secondary">无</Typography.Text>
              : pres.map((p) => <Tag key={p} color="orange">{p}</Tag>)}
          </div>
        </div>

        <div>
          <Typography.Text strong>产出留档</Typography.Text>
          <div style={{ marginTop: 4 }}>
            {outputs.length === 0
              ? <Typography.Text type="secondary">暂无产出</Typography.Text>
              : outputs.map((o) => <Tag key={o} color="green">{o}</Tag>)}
          </div>
        </div>

        <div>
          <Typography.Text strong>节点留档（{nodeArtifacts.length}）</Typography.Text>
          <div style={{ marginTop: 4 }}>
            {nodeArtifacts.length === 0
              ? <Empty description="暂无留档" imageStyle={{ height: 40 }} />
              : nodeArtifacts.map((a) => (
                <Card key={a.id} size="small" style={{ marginBottom: 8 }}>
                  <Space direction="vertical" size={2} style={{ width: '100%' }}>
                    <Space>
                      <FileTextOutlined />
                      <Typography.Text strong>{a.name}</Typography.Text>
                      <Tag color={a.kind === 'DELIVERABLE' ? 'green' : 'default'}>
                        {a.kind === 'DELIVERABLE' ? '交付' : '过程'}
                      </Tag>
                      {a.version != null && <Tag>v{a.version}</Tag>}
                    </Space>
                    {a.content_md && (
                      <Typography.Paragraph
                        type="secondary"
                        style={{ fontSize: 12, marginBottom: 0 }}
                        ellipsis={{ rows: 2 }}
                      >
                        {a.content_md}
                      </Typography.Paragraph>
                    )}
                  </Space>
                </Card>
              ))}
          </div>
        </div>
      </Space>
    </Drawer>
  )
}

/** 项目详情：阶段链 + 留档 + 节点操作 */
/** 节点产出展示：output_json 是 jsonb 文本——JSON 包装则提取常见文本字段后按 markdown 渲染，
 *  整体就是 JSON（如 start 节点的规划数据）则格式化展示；滚动由外层容器管（scroll=false）。 */
function OutputBlock({ raw }: { raw?: string | null }) {
  if (!raw) return <Typography.Text type="secondary">（无产出内容）</Typography.Text>
  let text = raw
  let isJson = false
  try {
    const o = JSON.parse(raw) as Record<string, unknown>
    let found = false
    for (const k of ['result', 'content', 'output', 'text', 'summary']) {
      const v = o[k]
      if (typeof v === 'string' && v.trim()) {
        text = v
        found = true
        break
      }
    }
    if (!found) isJson = true
  } catch {
    /* 非 JSON，按 markdown 展示 */
  }
  return (
    <div style={{ maxHeight: 420, overflow: 'auto' }}>
      {isJson ? <MarkdownView json scroll={false} text={raw} /> : <MarkdownView scroll={false} text={text} />}
    </div>
  )
}

/**
 * 阶段执行面板：节点进度 + 待决策审批（契约 docs/stage-lifecycle-contract-v27.md §5）。
 *
 * <p>数据由父组件轮询喂进来：审批是异步放行（`resumeAsync` 立即返回，后续节点后台跑），
 * 单个 agent 节点实测 165s ~ 600s+，所以点完「通过」必须靠刷新看进展，不能等接口。</p>
 */
/** 附件（对话消息可带图片/文本附件） */
interface ChatAttachment {
  name: string
  mediaType?: string
  dataBase64?: string
  content?: string
  dataUrl?: string
}

/** 阶段对话消息（user 右侧蓝底，agent 左侧白底，error 红底，queued 灰底待发送） */
interface StageChatMsg {
  id: number
  type: 'user' | 'agent' | 'error' | 'queued' | 'system'
  text: string
  attachments?: ChatAttachment[]
  /** agent 回复流式生成中 */
  streaming?: boolean
}

/**
 * 阶段对话面板 —— 项目的主工作界面（V28）。
 *
 * <p>为什么不复用节点抽屉：{@code NodeChatDrawer} 挂在 {@code project_work_node} 台账节点上，
 * 而 V27 起规划不再落台账（台账也没有 run 回写通道），新项目 0 节点 → 抽屉打不开 → 界面上
 * 完全没有对话入口。后端 {@code chatByNode} 还依赖已停用的 {@code project_template} 选 agent，
 * 所以整条路走不通，改走 {@code POST /stage/chat}。</p>
 *
 * <p>会话按「阶段 + 迭代」聚合（后端 sessionId 决定），所以下拉切换子任务只是换「本轮聚焦的
 * 任务边界」，不会把已有对话换掉——这一阶段的对话是同一段工作现场。</p>
 */
/** 读 SSE 流：按空行分帧，解析 event:/data: 行（冒号后无空格），回调 (event, data) */
async function readSse(res: Response, onEvent: (event: string, data: string) => void) {
  const reader = res.body?.getReader()
  if (!reader) return
  const dec = new TextDecoder()
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      let ev = 'message'
      const dataLines: string[] = []
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) ev = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (dataLines.length > 0) onEvent(ev, dataLines.join('\n'))
    }
  }
}

/** Markdown 渲染：json=true 时先尝试格式化 JSON，失败按 markdown */
function MarkdownView({ text, json = false, scroll = true }: { text?: string; json?: boolean; scroll?: boolean }) {
  const raw = (text || '').trim()
  if (!raw) return <Typography.Text type="secondary">（无内容）</Typography.Text>
  const boxStyle: CSSProperties = scroll
    ? { maxHeight: '60vh', overflow: 'auto' }
    : {}
  if (json && (raw.startsWith('{') || raw.startsWith('['))) {
    try {
      const pretty = JSON.stringify(JSON.parse(raw), null, 2)
      return (
        <pre style={{ margin: 0, padding: 10, background: '#f6f8fa', borderRadius: 6, fontSize: 12, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word', ...boxStyle }}>
          {pretty}
        </pre>
      )
    } catch {
      /* 不是合法 JSON，按 markdown 渲染 */
    }
  }
  return (
    <div style={{ fontSize: 13, lineHeight: 1.7, wordBreak: 'break-word', ...boxStyle }}>
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{raw}</ReactMarkdown>
    </div>
  )
}


function StageChatPanel({ projectId, exec }: { projectId: string; exec?: StageExecution | null }) {
  const [msgs, setMsgs] = useState<StageChatMsg[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [taskKey, setTaskKey] = useState<string | undefined>(undefined)
  const idRef = useRef(0)
  const nextId = () => ++idRef.current
  const listRef = useRef<HTMLDivElement>(null)
  // 多轮交流：busy 时输入不锁——Enter 入队 / Ctrl+Enter 插队，当前轮结束自动发队首。
  // 队列用 ref 存（同步读写），queueLen 只做渲染镜像。
  const queueRef = useRef<{ id: number; text: string; taskKey?: string; attachments?: ChatAttachment[] }[]>([])
  const [queueLen, setQueueLen] = useState(0)
  const abortRef = useRef<AbortController | null>(null)
  const busyRef = useRef(false)
  // 对话附件（V29）：图片走多模态、文本文件走内容拼接；截图可直接 Ctrl+V 粘贴
  const [files, setFiles] = useState<ChatAttachment[]>([])

  const TEXT_EXT = /\.(md|txt|json|java|xml|yml|yaml|csv|log|sql|ts|tsx|js|jsx|py|sh|html|css|vue|properties|conf|ini)$/i

  const addFile = (f: File) => {
    if (files.length + queueRef.current.reduce((n, q) => n + (q.attachments?.length || 0), 0) >= 4) {
      message.warning('附件最多 4 个/轮')
      return
    }
    if (f.type.startsWith('image/')) {
      if (f.size > 5 * 1024 * 1024) { message.warning(`图片 ${f.name || '截图'} 超过 5MB`); return }
      const reader = new FileReader()
      reader.onload = () => {
        const dataUrl = String(reader.result || '')
        setFiles((x) => [...x, {
          name: f.name || '截图.png', mediaType: f.type,
          dataBase64: dataUrl.split(',')[1] || '', dataUrl,
        }])
      }
      reader.readAsDataURL(f)
      return
    }
    if (!TEXT_EXT.test(f.name)) {
      message.warning('仅支持图片与文本类文件（md/txt/代码/配置等）')
      return
    }
    if (f.size > 150 * 1024) { message.warning(`文本附件 ${f.name} 超过 150KB`); return }
    const reader = new FileReader()
    reader.onload = () => setFiles((x) => [...x, { name: f.name, content: String(reader.result || '') }])
    reader.readAsText(f)
  }

  /** 粘贴截图：clipboard 里的图片直接进附件区 */
  const onPaste = (e: React.ClipboardEvent) => {
    const items = e.clipboardData?.items
    if (!items) return
    for (const it of Array.from(items)) {
      if (it.kind === 'file' && it.type.startsWith('image/')) {
        const f = it.getAsFile()
        if (f) { e.preventDefault(); addFile(f) }
        return
      }
    }
  }

  // 新内核（AI 编排）模式：子任务聚焦是旧链路概念（plan.tasks 草稿与新内核 steps 无关），隐藏
  const orchestrated = (exec?.steps || []).length > 0
  const tasks = orchestrated ? [] : ((exec?.plan?.tasks || []) as PlanTask[])

  // AI 过程可见化：步骤状态变化以系统消息进对话流（讨论完成/任务完成/失败/等待答复），
  // 进行中的步骤由下方「活跃步骤条」实时展示——后台跑几分钟时用户不再干等。
  const prevStepsRef = useRef<Map<number, string>>(new Map())
  useEffect(() => {
    const steps = exec?.steps || []
    const prev = prevStepsRef.current
    const next = new Map<number, string>()
    const events: string[] = []
    for (const s of steps) {
      next.set(s.id, s.status)
      const before = prev.get(s.id)
      if (!before || before === s.status) continue
      if (s.status === 'EXECUTING') {
        events.push(`⚙ #${s.seq} ${s.name} 开始执行${(s.participants || []).length ? `（${(s.participants || []).join('、')}）` : ''}`)
      } else if (s.status === 'DONE') {
        events.push(`✅ #${s.seq} ${s.name} 完成${s.outputSummary ? `：${s.outputSummary.slice(0, 100)}` : ''}`)
      } else if (s.status === 'FAILED') {
        events.push(`❌ #${s.seq} ${s.name} 失败：${(s.error || '').slice(0, 100)}`)
      } else if (s.status === 'SKIPPED') {
        events.push(`⏭ #${s.seq} ${s.name} 已跳过：${(s.skipReason || '').slice(0, 80)}`)
      } else if (s.status === 'WAITING') {
        events.push(`⏸ #${s.seq} ${s.name} 等待你的答复：${(s.instruction || '').slice(0, 100)}`)
      }
    }
    prevStepsRef.current = next
    if (events.length > 0) {
      setMsgs((x) => [...x, { id: nextId(), type: 'system', text: events.join('\n') }])
    }
  }, [exec?.steps])
  const stageText = exec?.stage_label || '当前阶段'
  const chatStage = exec?.stage || ''
  const chatIteration = exec?.iteration || 1

  // 进入/切阶段时回放该阶段会话的历史：agent 侧记忆一直存在 agent_state 里，
  // 只是此前没有读取通道，刷新即空白。会话粒度=阶段，阶段推进后 exec.stage 变化自动换新会话。
  // 卸载时中止进行中的 SSE 流：返回列表页后连接继续读到流结束是泄漏
  useEffect(() => () => { abortRef.current?.abort() }, [])
  useEffect(() => {
    if (!chatStage) return
    // 切阶段：中止旧阶段还在跑的流（否则输入框卡「运行中」直到旧流结束，停止按钮 abort 的也是旧流），
    // 并丢弃未发送的队列（会话都换了，队列没有意义）
    abortRef.current?.abort()
    abortRef.current = null
    queueRef.current = []
    setQueueLen(0)
    setMsgs((x) => x.filter((m) => m.type !== 'queued'))
    let alive = true
    apiJson<{ messages?: { role?: string; text?: string }[] }>(
      `/api/projects/${projectId}/stage/chat/history?stage=${chatStage}&iteration=${chatIteration}`,
    ).then((r) => {
      if (!alive) return
      const hist = (r?.messages || [])
        .filter((m) => (m.text || '').trim())
        .map((m) => ({
          id: nextId(),
          type: (m.role === 'USER' ? 'user' : 'agent') as 'user' | 'agent',
          text: m.text || '',
        }))
      if (hist.length) setMsgs(hist)
    }).catch(() => { /* 历史取不到不打断对话主流程 */ })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, chatStage, chatIteration])

  // 新内容自动滚到底：流式回答时不滚的话用户要自己拖着看
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [msgs])

  /** 发送一条（q 已在 msgs 里以 queued 形态占位）：SSE 流式回填，停止走 AbortController */
  const sendMsg = async (q: { id: number; text: string; taskKey?: string; attachments?: ChatAttachment[] }) => {
    const agentMsgId = nextId()
    setMsgs((x) => [
      ...x.map((m) => (m.id === q.id ? { ...m, type: 'user' as const } : m)),
      { id: agentMsgId, type: 'agent', text: '', streaming: true },
    ])
    busyRef.current = true
    setBusy(true)
    const ac = new AbortController()
    abortRef.current = ac
    try {
      const r = await apiFetch(`/api/projects/${projectId}/stage/chat`, {
        method: 'POST',
        body: JSON.stringify({
          message: q.text,
          ...(q.taskKey ? { task_key: q.taskKey } : {}),
          ...(q.attachments?.length ? {
            attachments: q.attachments.map((a) => ({
              name: a.name, media_type: a.mediaType, data_base64: a.dataBase64, content: a.content,
            })),
          } : {}),
        }),
        signal: ac.signal,
      })
      if (!r.ok) {
        const data = await r.json().catch(() => ({})) as { message?: string; error?: string }
        throw new Error(data.message || data.error || `请求失败 (${r.status})`)
      }
      await readSse(r, (event, data) => {
        let payload: Record<string, unknown> = {}
        try { payload = JSON.parse(data) } catch { /* 忽略非 JSON 帧 */ }
        if (event === 'agent.start') {
          const who = String(payload.agent || 'agent')
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, text: m.text || `（${who} 正在处理…）` } : m)))
        } else if (event === 'agent.delta' && typeof payload.delta === 'string') {
          const delta = payload.delta
          setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, text: (m.text || '') + delta } : m)))
        } else if (event === 'agent.result' && typeof payload.text === 'string') {
          // 只有还没流式出正文时才用整段结果兜底，否则会把已流出的内容重复一遍
          const full = payload.text
          setMsgs((x) => x.map((m) => (m.id === agentMsgId && (!m.text || m.text.startsWith('（'))
            ? { ...m, text: full } : m)))
        } else if (event === 'agent.end' || event === 'stage.finish') {
          setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, streaming: false } : m)))
        } else if (event === 'error') {
          setMsgs((x) => [...x, {
            id: nextId(), type: 'error', text: String(payload.message || '执行出错'),
          }])
        }
      })
    } catch (e) {
      if (ac.signal.aborted) {
        // 用户主动停止：不是错误。后台 agent 会跑完并照常留档，只是前端不再接收
        setMsgs((x) => x.map((m) => (m.id === agentMsgId
          ? { ...m, streaming: false, text: (m.text || '') + '\n\n（已停止接收，后台任务会完成并留档）' } : m)))
      } else {
        setMsgs((x) => [...x, {
          id: nextId(), type: 'error', text: e instanceof Error ? e.message : String(e),
        }])
      }
    } finally {
      busyRef.current = false
      setBusy(false)
      abortRef.current = null
      setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, streaming: false } : m)))
      // 队首自动开跑：多轮交流的接力点
      const next = queueRef.current.shift()
      setQueueLen(queueRef.current.length)
      if (next) void sendMsg(next)
    }
  }

  /** 提交输入：空闲直接发；运行中 Enter 入队 / Ctrl+Enter 插队 */
  const submit = (jump: boolean) => {
    const query = input.trim()
    if (!query && files.length === 0) return
    const atts = files.length ? [...files] : undefined
    const q = { id: nextId(), text: query || '（见附件）', taskKey, attachments: atts }
    setInput('')
    setFiles([])
    setMsgs((x) => [...x, { id: q.id, type: 'queued', text: q.text, attachments: atts }])
    if (!busyRef.current) {
      void sendMsg(q)
    } else if (jump) {
      queueRef.current.unshift(q)
      setQueueLen(queueRef.current.length)
    } else {
      queueRef.current.push(q)
      setQueueLen(queueRef.current.length)
    }
  }

  /** 撤回还没发送的排队消息 */
  const revoke = (id: number) => {
    queueRef.current = queueRef.current.filter((q) => q.id !== id)
    setQueueLen(queueRef.current.length)
    setMsgs((x) => x.filter((m) => m.id !== id))
  }

  /** 停止接收当前流（后台 agent 会跑完并留档） */
  const stop = () => abortRef.current?.abort()

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit(e.ctrlKey || e.metaKey)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <Space wrap size={6}>
        {tasks.length > 0 && (
          <>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>本轮聚焦：</Typography.Text>
            <Select
              size="small"
              style={{ minWidth: 280 }}
              placeholder={`整个${stageText}（不指定子任务）`}
              allowClear
              value={taskKey}
              onChange={(v) => setTaskKey(v)}
              options={tasks.map((t, i) => ({
                value: String(t.key ?? i),
                label: `${t.key ?? i + 1}. ${t.name || '未命名子任务'}`,
              }))}
            />
          </>
        )}
        {msgs.length > 0 && (
          <Button size="small" type="text" onClick={() => setMsgs([])}>清空显示</Button>
        )}
      </Space>

      {/* 活跃步骤条：后台执行中的小流程实时可见（讨论已收集多少发言/任务跑多久），不再空等 */}
      {(() => {
        const active = (exec?.steps || []).filter((s) => s.status === 'EXECUTING' || s.status === 'WAITING')
        if (active.length === 0) return null
        return (
          <div style={{ padding: '6px 10px', background: '#e6f4ff', borderRadius: 6, border: '1px solid #91caff' }}>
            {active.map((s) => (
              <div key={s.id} style={{ fontSize: 12, lineHeight: '20px' }}>
                <Tag color={STEP_STATUS_COLOR[s.status] || 'processing'} style={{ marginInlineEnd: 6 }}>
                  {STEP_STATUS_LABEL[s.status] || s.status}
                </Tag>
                <Typography.Text style={{ fontSize: 12 }}>#{s.seq} {s.name}</Typography.Text>
                {(s.participants || []).length > 0 && (
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    {' '}（{(s.participants || []).join('、')}）
                  </Typography.Text>
                )}
                {s.outputFull && (
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    {' '}· 已收集 {s.outputFull.length} 字
                  </Typography.Text>
                )}
              </div>
            ))}
          </div>
        )
      })()}

      <div
        ref={listRef}
        style={{
          maxHeight: 'calc(100vh - 340px)', minHeight: 320, overflowY: 'auto', padding: '10px 12px',
          background: '#fafafa', borderRadius: 6, border: '1px solid #f0f0f0',
        }}
      >
        {msgs.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={`向${stageText}提问，回复流式展示；最终产出会自动登记为交付物留档`}
            style={{ padding: 24 }}
          />
        ) : (
          msgs.map((m) => (
            <div key={m.id} style={{
              marginBottom: 10,
              textAlign: m.type === 'user' ? 'right' : m.type === 'system' ? 'center' : 'left',
            }}>
              <div
                style={{
                  display: 'inline-block',
                  maxWidth: m.type === 'user' ? '80%' : '94%',
                  padding: '6px 12px',
                  borderRadius: 8,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  textAlign: 'left',
                  background: m.type === 'user' ? '#1677ff' : m.type === 'error' ? '#fff2f0' : m.type === 'queued' ? '#f6f6f6' : m.type === 'system' ? 'transparent' : '#fff',
                  color: m.type === 'user' ? '#fff' : m.type === 'system' ? '#8c8c8c' : undefined,
                  border: m.type === 'user' ? 'none' : `1px solid ${m.type === 'error' ? '#ffccc7' : m.type === 'queued' ? '#e8e8e8' : '#f0f0f0'}`,
                  opacity: m.type === 'queued' ? 0.75 : 1,
                }}
              >
                {m.type === 'queued' && (
                  <Space size={4} style={{ marginBottom: 2 }}>
                    <Tag color="default" style={{ marginRight: 0, fontSize: 11, lineHeight: '16px' }}>待发送</Tag>
                    <Button
                      type="text" size="small" style={{ height: 16, padding: '0 2px', fontSize: 11 }}
                      onClick={() => revoke(m.id)}
                    >
                      撤回
                    </Button>
                  </Space>
                )}
                {m.attachments?.map((a, i) => (a.dataUrl
                  ? <img key={i} src={a.dataUrl} alt={a.name} style={{ maxWidth: 260, maxHeight: 180, borderRadius: 6, display: 'block', marginBottom: 4 }} />
                  : <div key={i} style={{ marginBottom: 4 }}><PaperClipOutlined /> {a.name}</div>))}
                {m.type === 'user' || m.type === 'queued'
                  ? m.text
                  : <MarkdownView scroll={false} text={m.text} />}
                {m.streaming && <Typography.Text type="secondary"> ▍</Typography.Text>}
              </div>
            </div>
          ))
        )}
      </div>

      <div>
        {files.length > 0 && (
          <Space wrap size={6} style={{ marginBottom: 6 }}>
            {files.map((f, i) => (
              <Tag
                key={i} closable onClose={() => setFiles((x) => x.filter((_, j) => j !== i))}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
              >
                {f.dataUrl
                  ? <img src={f.dataUrl} alt={f.name} style={{ width: 22, height: 22, objectFit: 'cover', borderRadius: 3 }} />
                  : <PaperClipOutlined />}
                {f.name}
              </Tag>
            ))}
          </Space>
        )}
        <Input.TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
          placeholder={`说明你要在${stageText}做什么（Enter 发送，Shift+Enter 换行，可直接粘贴截图）`}
          autoSize={{ minRows: 2, maxRows: 5 }}
        />
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6 }}>
          <Typography.Text type="secondary" style={{ fontSize: 12, flex: 1 }}>
            {busy
              ? `运行中 — Enter 入队，Ctrl+Enter 插队${queueLen ? `（队列 ${queueLen} 条）` : ''}`
              : '就绪 — Enter 发送，Shift+Enter 换行'}
          </Typography.Text>
          {busy && (
            <Button danger icon={<StopOutlined />} onClick={stop}>停止</Button>
          )}
          {busy && (
            <Button
              color="orange" variant="solid" icon={<ThunderboltOutlined />}
              disabled={!input.trim()} onClick={() => submit(true)}
            >
              插队
            </Button>
          )}
          <Upload
            accept="image/*,.md,.txt,.json,.java,.xml,.yml,.yaml,.csv,.log,.sql,.ts,.tsx,.js,.jsx,.py,.sh,.html,.css,.vue,.properties,.conf,.ini"
            showUploadList={false}
            beforeUpload={(f) => { addFile(f); return Upload.LIST_IGNORE }}
          >
            <Button icon={<PaperClipOutlined />}>附件</Button>
          </Upload>
          <Button
            type="primary" icon={<SendOutlined />}
            disabled={busy && !input.trim() && files.length === 0} onClick={() => submit(false)}
          >
            {busy ? `入队${queueLen ? ` (${queueLen})` : ''}` : '发送'}
          </Button>
        </div>
      </div>
    </div>
  )
}

/**
 * 右侧栏「会话」tab：项目内全部阶段会话（会话粒度=阶段，阶段推进自动开新会话），
 * 点击展开回放该阶段的对话历史（GET /stage/chat/history）。
 */
function StageSessionsPanel({ projectId }: { projectId: string }) {
  const [sessions, setSessions] = useState<
    { session_id: string; stage: string; iteration: string; last_at?: string }[]
  >([])
  const [loading, setLoading] = useState(false)
  const [openId, setOpenId] = useState<string | null>(null)
  const [history, setHistory] = useState<StageChatMsg[]>([])
  const [histLoading, setHistLoading] = useState(false)

  const loadSessions = useCallback(() => {
    setLoading(true)
    apiJson<{ session_id: string; stage: string; iteration: string; last_at?: string }[]>(
      `/api/projects/${projectId}/stage/chat/sessions`,
    ).then((r) => setSessions(r || []))
      .catch((e: Error) => message.error(e.message))
      .finally(() => setLoading(false))
  }, [projectId])

  useEffect(() => { loadSessions() }, [loadSessions])

  const openSession = (sid: string, stage: string, iteration: string) => {
    if (openId === sid) { setOpenId(null); return }
    setOpenId(sid)
    setHistLoading(true)
    apiJson<{ messages?: { role?: string; text?: string }[] }>(
      `/api/projects/${projectId}/stage/chat/history?stage=${stage}&iteration=${iteration}`,
    ).then((r) => {
      setHistory((r?.messages || [])
        .filter((m) => (m.text || '').trim())
        .map((m, i) => ({
          id: i + 1,
          type: (m.role === 'USER' ? 'user' : 'agent') as 'user' | 'agent',
          text: m.text || '',
        })))
    }).catch((e: Error) => message.error(e.message))
      .finally(() => setHistLoading(false))
  }

  return (
    <div>
      <Space style={{ marginBottom: 8, width: '100%', justifyContent: 'space-between' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          每个阶段就是一个会话，阶段推进自动开新会话
        </Typography.Text>
        <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={loadSessions}>刷新</Button>
      </Space>
      {!loading && sessions.length === 0 ? (
        <Empty description="还没有阶段对话" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        sessions.map((s) => (
          <Card
            key={s.session_id}
            size="small"
            style={{ marginBottom: 8 }}
            title={(
              <Space size={6}>
                <Tag color={stageColor(s.stage)}>{stageLabel(s.stage)}</Tag>
                <span>第 {s.iteration} 轮</span>
                {s.last_at && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {String(s.last_at).replace('T', ' ').slice(0, 16)}
                  </Typography.Text>
                )}
              </Space>
            )}
            extra={(
              <Button size="small" type="link" onClick={() => openSession(s.session_id, s.stage, s.iteration)}>
                {openId === s.session_id ? '收起' : '回放'}
              </Button>
            )}
          >
            {openId === s.session_id && (
              <div style={{ maxHeight: 320, overflowY: 'auto', background: '#fafafa', padding: 10, borderRadius: 6 }}>
                {histLoading ? (
                  <div style={{ textAlign: 'center', padding: 16 }}><Spin /></div>
                ) : history.length === 0 ? (
                  <Empty description="该会话暂无消息" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                ) : (
                  history.map((m) => (
                    <div key={m.id} style={{ marginBottom: 8, textAlign: m.type === 'user' ? 'right' : 'left' }}>
                      <div style={{
                        display: 'inline-block', maxWidth: '90%', padding: '5px 10px', borderRadius: 8,
                        whiteSpace: 'pre-wrap', wordBreak: 'break-word', textAlign: 'left', fontSize: 12,
                        background: m.type === 'user' ? '#1677ff' : '#fff',
                        color: m.type === 'user' ? '#fff' : undefined,
                        border: m.type === 'user' ? 'none' : '1px solid #f0f0f0',
                      }}>
                        {m.text}
                      </div>
                    </div>
                  ))
                )}
              </div>
            )}
          </Card>
        ))
      )}
    </div>
  )
}

/**
 * 阶段设置：绑定工作流 + 阶段智能体。
 * 绑定工作流后，批准规划不再重新编译画布，直接复用该已发布定义起跑——
 * 同一套调好的流程（含决策门）跨项目/跨轮次复用，定义列表也不再同名膨胀。
 * 阶段智能体在对话与执行时优先于规划草稿里的 agents。
 */
function StageSettingsModal({ open, projectId, stage, onClose, onSaved }: {
  open: boolean
  projectId: string
  stage: string
  onClose: () => void
  onSaved: () => void
}) {
  const [defs, setDefs] = useState<WorkflowOption[]>([])
  const [agentOpts, setAgentOpts] = useState<AgentOption[]>([])
  const [cap, setCap] = useState<Record<string, unknown>>({})
  const [note, setNote] = useState('')
  const [workflow, setWorkflow] = useState<string | undefined>()
  const [boundAgents, setBoundAgents] = useState<string[]>([])
  const [boundServers, setBoundServers] = useState<string[]>([])
  const [boundConnectors, setBoundConnectors] = useState<string[]>([])
  const [dsOpts, setDsOpts] = useState<DsOption[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!open) return
    setLoading(true)
    // 四个接口并行拉：定义列表、agent 列表、数据源候选、当前阶段绑定
    Promise.all([
      apiJson<WorkflowOption[]>('/api/definitions'),
      apiJson<AgentOption[]>('/api/agents'),
      apiJson<DsOption[]>('/api/datasources').catch(() => [] as DsOption[]),
      apiJson<{ capability?: Record<string, unknown>; note?: string | null }>(
        `/api/projects/${projectId}/stage/${stage}/capability`),
    ]).then(([d, a, ds, c]) => {
      setDefs((d || []).filter((x) => x.status === 'PUBLISHED'))
      setAgentOpts((a || []).filter((x) => x.status === 'PUBLISHED'))
      setDsOpts(ds || [])
      const cp = c?.capability || {}
      setCap(cp)
      setNote(c?.note || '')
      setWorkflow(cp.workflow ? String(cp.workflow) : undefined)
      setBoundAgents(Array.isArray(cp.agents) ? (cp.agents as string[]) : [])
      setBoundServers(Array.isArray(cp.servers) ? (cp.servers as string[]) : [])
      setBoundConnectors(Array.isArray(cp.connectors) ? (cp.connectors as string[]) : [])
    }).catch((e: Error) => message.error(e.message))
      .finally(() => setLoading(false))
  }, [open, projectId, stage])

  const save = async () => {
    setSaving(true)
    try {
      // saveCapability 是全量覆盖：必须把现有绑定原样带上、只改 agents/workflow，
      // 否则一次保存会把已绑的 skills/mcps/tools 全部清空
      await apiJson(`/api/projects/${projectId}/stage/${stage}/capability`, 'PUT', {
        ...cap,
        agents: boundAgents,
        workflow: workflow || '',
        servers: boundServers,
        connectors: boundConnectors,
        note,
      })
      message.success('阶段设置已保存')
      onSaved()
      onClose()
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      title={`阶段设置 · ${stageLabel(stage)}`}
      open={open}
      onCancel={onClose}
      onOk={() => void save()}
      confirmLoading={saving}
      okText="保存"
      width={560}
      destroyOnClose
    >
      <Form layout="vertical">
        <Form.Item
          label="绑定工作流"
          extra="绑定后批准规划即按该工作流执行（不再重新编译）；留空则按 PM 规划编译。仅列已发布工作流"
        >
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="不绑定——按 PM 规划编译执行"
            value={workflow}
            onChange={(v?: string) => setWorkflow(v)}
            loading={loading}
            options={defs.map((d) => ({ value: String(d.id), label: `#${d.id} ${d.name || ''}` }))}
          />
        </Form.Item>
        <Form.Item
          label="阶段智能体"
          extra="对话与执行时优先使用；留空则按规划 tasks 里的 agent，再退回阶段默认"
        >
          <Select
            mode="multiple"
            placeholder="不指定——按规划 / 阶段默认"
            value={boundAgents}
            onChange={(v: string[]) => setBoundAgents(v)}
            loading={loading}
            options={agentOpts
              .filter((a) => a.name)
              .map((a) => ({
                value: a.name,
                label: a.description ? `${a.description}（${a.name}）` : (a.name as string),
              }))}
          />
        </Form.Item>
        <Form.Item
          label="运维服务器"
          extra="部署 / 巡检 / 变更的目标机器（如 root@1.2.3.4:22），输入后回车添加；执行侧消费开发中"
        >
          <Select
            mode="tags"
            placeholder="如 root@1.2.3.4:22（回车确认）"
            tokenSeparators={[' ']}
            open={false}
            value={boundServers}
            onChange={(v: string[]) => setBoundServers(v)}
            loading={loading}
          />
        </Form.Item>
        <Form.Item
          label="可查询数据库"
          extra="运维排障时 agent 可直接查询的数据源连接器（JDBC）；执行侧消费开发中"
        >
          <Select
            mode="multiple"
            allowClear
            placeholder={dsOpts.length ? '选择可用的数据库（可留空）' : '未加载数据源，请先在「数据源」页添加'}
            value={boundConnectors}
            onChange={(v: string[]) => setBoundConnectors(v)}
            loading={loading}
            options={dsOpts.map((d) => ({
              value: `connector:${d.id}`,
              label: `${d.name}（${d.platform || 'jdbc'} #${d.id}）`,
            }))}
          />
        </Form.Item>
        <Form.Item label="备注" extra="这批绑定给 AI 用来干什么的说明，会随上下文注入">
          <Input.TextArea rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
        </Form.Item>
      </Form>
    </Modal>
  )
}

function StageExecutionPanel({ projectId, exec, busy, onRefresh, onChanged }: {
  projectId: string
  exec: StageExecution | null
  busy: boolean
  onRefresh: () => void
  onChanged: () => void
}) {
  const [gate, setGate] = useState<WaitingDecision | null>(null)
  const [decision, setDecision] = useState<'APPROVE' | 'REJECT' | 'SKIP'>('APPROVE')
  const [comment, setComment] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [gateErr, setGateErr] = useState<string | null>(null)
  const [approving, setApproving] = useState(false)
  const [planErr, setPlanErr] = useState<string | null>(null)
  const [planRejectOpen, setPlanRejectOpen] = useState(false)
  const [planReason, setPlanReason] = useState('')
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [rerunning, setRerunning] = useState(false)
  /** 阶段对话里用户的最近批注：决策门审批时「抄」过来做上下文——批注与决策本是同一条交互链 */
  const [stageNotes, setStageNotes] = useState<{ text: string; timestamp?: string }[]>([])

  // 决策门挂起时拉取本阶段对话的用户批注（最近 3 条），供审批时参考
  useEffect(() => {
    const stage = exec?.stage
    if (!stage || !(exec?.waiting_decisions || []).length) {
      setStageNotes([])
      return
    }
    let alive = true
    apiJson<{ messages?: { role?: string; text?: string; timestamp?: string }[] }>(
      `/api/projects/${projectId}/stage/chat/history?stage=${stage}&iteration=${exec?.iteration || 1}`,
    )
      .then((r) => {
        if (!alive) return
        const notes = (r?.messages || [])
          .filter((m) => m.role === 'user' && (m.text || '').trim())
          .slice(-3)
          .map((m) => ({ text: m.text || '', timestamp: m.timestamp }))
        setStageNotes(notes)
      })
      .catch(() => { if (alive) setStageNotes([]) })
    return () => { alive = false }
  }, [projectId, exec?.stage, exec?.iteration, (exec?.waiting_decisions || []).length])
  /** 重跑当前阶段：旧执行（孤儿 RUNNING / FAILED）整体废弃，按既有定义直接重新起跑 */
  const rerunStage = async () => {
    const stage = exec?.stage
    if (!stage) return
    setRerunning(true)
    try {
      const r = await apiJson<{ run_id?: string }>(`/api/projects/${projectId}/stage/${stage}/rerun`, 'POST', {})
      message.success(`已重新起跑：run ${r?.run_id || ''}`)
      onRefresh()
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e))
    } finally {
      setRerunning(false)
    }
  }


  /**
   * 批准 / 驳回 PM 规划（POST /plan/{planId}/decide）。
   * 批准即「编译成画布 + 发布 + 起跑」；返回里的 skipped 是引用了未发布 agent 而被跳过的子任务，
   * 必须显式告知操作人——否则用户以为整条流程都跑了，实际少一步。
   */
  const decidePlan = async (planId: number, d: 'APPROVE' | 'REJECT', reason?: string) => {
    setApproving(true)
    setPlanErr(null)
    try {
      const r = await apiJson<{ skipped?: unknown[] }>(
        `/api/projects/${projectId}/plan/${planId}/decide`,
        'POST',
        d === 'APPROVE' ? { decision: 'APPROVE' } : { decision: 'REJECT', reason },
      )
      if (d === 'APPROVE') {
        const skipped = (r?.skipped || [])
          .map((s) => (typeof s === 'string' ? s : (s as { name?: string })?.name || ''))
          .filter(Boolean)
        message.success(skipped.length
          ? `规划已批准并起跑；${skipped.length} 个子任务被跳过：${skipped.join('、')}`
          : '规划已批准并起跑，节点在后台执行')
      } else {
        message.success('已驳回，PM 会重出一版草稿')
      }
      setPlanRejectOpen(false)
      onChanged()
    } catch (e) {
      setPlanErr(e instanceof Error ? e.message : String(e))
    } finally {
      setApproving(false)
    }
  }

  const openGate = (w: WaitingDecision, d: 'APPROVE' | 'REJECT' | 'SKIP') => {
    setGate(w)
    setDecision(d)
    setComment('')
    setGateErr(null)
  }

  const submitGate = async () => {
    if (!gate) return
    // 后端对驳回强制要求批注（空批注只会让 agent 重出一版同样的东西），前端先拦一道省一次往返
    if (decision === 'REJECT' && comment.trim().length === 0) {
      setGateErr('驳回必须写批注，否则无法告知 agent 要改什么')
      return
    }
    setSubmitting(true)
    setGateErr(null)
    try {
      await apiJson(`/api/projects/${projectId}/gate/decide`, 'POST', {
        node_id: gate.node_id,
        decision,
        comment: comment.trim() || undefined,
      })
      message.success(decision === 'APPROVE'
        ? `已放行「${gate.node_name}」，后续节点在后台执行`
        : decision === 'SKIP'
          ? `已跳过「${gate.node_name}」，从其后继续执行`
          : `已驳回「${gate.node_name}」，agent 将按批注重生成`)
      setGate(null)
      onChanged()
    } catch (e) {
      const m = e instanceof Error ? e.message : String(e)
      // 409 表示门已被别人放行或 run 状态已变；后端 reason 不进响应体，这里补可行动提示
      setGateErr(/409/.test(m) ? `${m}——待决策状态可能已变化，请刷新后重试` : m)
    } finally {
      setSubmitting(false)
    }
  }

  if (!exec) {
    return <Empty description="未取到执行信息" image={Empty.PRESENTED_IMAGE_SIMPLE} />
  }
  const nodes = exec.node_runs || []
  const waiting = exec.waiting_decisions || []
  const planId = exec.plan?.id
  // 新内核（AI 编排）模式：step 台账非空。右边栏只读化——进度/产出/决策仅展示，
  // 人驱动动作（重跑/门审批按钮）只在旧链路模式保留；决策答复走左侧对话。
  const orchestrated = (exec.steps || []).length > 0
  const steps = exec.steps || []
  const cols: ColumnsType<NodeRunRow> = [
    { title: '序号', dataIndex: 'seq', width: 52 },
    {
      title: '节点', dataIndex: 'node_name', width: 150,
      // 名称与 node_id 上下两行堆叠：右栏空间窄，横排 Space 会把列压成一字一行
      render: (v: string, r) => (
        <div>
          <div>{v || r.node_id}</div>
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>{r.node_id}</Typography.Text>
        </div>
      ),
    },
    {
      title: '状态', dataIndex: 'status', width: 84,
      render: (v: string) => <Tag color={NODE_RUN_STATUS_COLOR[v] || 'default'}>{NODE_RUN_STATUS_LABEL[v] || v}</Tag>,
    },
    {
      title: '开始', dataIndex: 'started_at', width: 80,
      render: (v?: string) => (v ? String(v).replace('T', ' ').slice(11, 19) : '—'),
    },
    {
      title: '结束', dataIndex: 'finished_at', width: 80,
      render: (v?: string) => (v ? String(v).replace('T', ' ').slice(11, 19) : '—'),
    },
    {
      title: '错误', dataIndex: 'error', width: 90, ellipsis: true,
      render: (v?: string | null) => (v
        ? <Tooltip title={v}><Typography.Text type="danger" style={{ fontSize: 12 }}>{v}</Typography.Text></Tooltip>
        : '—'),
    },
  ]

  /** 编排步骤表（新内核只读台账）：产出摘要直接看，全文/理由/错误展开看 */
  const stepCols: ColumnsType<StageStepRow> = [
    { title: '#', dataIndex: 'seq', width: 44 },
    {
      title: '步骤', dataIndex: 'name', width: 170,
      render: (v: string, r) => (
        <div>
          <div>{v || `步骤 ${r.seq}`}</div>
          {(r.participants || []).length > 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>{(r.participants || []).join('、')}</Typography.Text>
          )}
        </div>
      ),
    },
    {
      title: '类型', dataIndex: 'stepType', width: 64,
      render: (v: string) => <Tag style={{ marginInlineEnd: 0 }}>{STEP_TYPE_LABEL[v] || v}</Tag>,
    },
    {
      title: '执行者', dataIndex: 'agent', width: 110, ellipsis: true,
      render: (v?: string | null) => (v ? <Tooltip title={v}><span style={{ fontSize: 12 }}>{v}</span></Tooltip> : '—'),
    },
    {
      title: '状态', dataIndex: 'status', width: 84,
      render: (v: string) => <Tag color={STEP_STATUS_COLOR[v] || 'default'}>{STEP_STATUS_LABEL[v] || v}</Tag>,
    },
    {
      title: '产出 / 说明', dataIndex: 'outputSummary', ellipsis: true,
      render: (v, r) => {
        const txt = v || r.skipReason || r.error || (r.status === 'WAITING' ? r.instruction : '') || '—'
        return (
          <Tooltip title={txt}>
            <Typography.Text style={{ fontSize: 12 }} type={r.status === 'FAILED' ? 'danger' : undefined}>
              {txt}
            </Typography.Text>
          </Tooltip>
        )
      },
    },
  ]

  return (
    <>
      <Space wrap style={{ marginBottom: 12 }} align="center">
        <Tag color={EXEC_STATE_COLOR[exec.state] || 'default'}>
          {EXEC_STATE_LABEL[exec.state] || exec.state}
        </Tag>
        {exec.run_id && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            run {exec.run_id}（{exec.run_status}）{exec.definition_id ? ` · def ${exec.definition_id}` : ''}
          </Typography.Text>
        )}
        <Button size="small" icon={<ReloadOutlined />} loading={busy} onClick={onRefresh}>刷新</Button>
        {exec.stage && (
          <Button size="small" icon={<ProfileOutlined />} onClick={() => setSettingsOpen(true)}>
            阶段设置
          </Button>
        )}
        {!orchestrated && exec.state === 'EXECUTING' && exec.run_status === 'FAILED' && (
          <Button danger size="small" icon={<SyncOutlined />} loading={rerunning} onClick={() => void rerunStage()}>
            重跑当前阶段
          </Button>
        )}
      </Space>

      {/* 阶段设置：绑定工作流 / 阶段智能体；保存后刷新执行视图（绑定影响下一次批准起跑） */}
      {exec.stage && (
        <StageSettingsModal
          open={settingsOpen}
          projectId={projectId}
          stage={exec.stage}
          onClose={() => setSettingsOpen(false)}
          onSaved={onRefresh}
        />
      )}

      {exec.error && (
        <Alert type="error" showIcon message="执行报错" description={exec.error} style={{ marginBottom: 12 }} />
      )}

      {exec.state === 'PENDING_APPROVAL' && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="在左侧对话里说一句话即可启动"
          description={
            <Space direction="vertical" size={4}>
              <span>AI 驱动：你的第一句话就是需求，主控会自动生成执行计划并起跑，不需要手动批准。规划内容可在对话里随时调整。</span>
              {exec.plan?.id != null && (
                <Space>
                  <Button size="small" type="primary" loading={approving} onClick={() => { const pid = exec.plan?.id; if (pid != null) void decidePlan(pid, 'APPROVE') }}>
                    立即批准并起跑
                  </Button>
                  <Button size="small" loading={approving} onClick={() => { setPlanErr(null); setPlanRejectOpen(true) }}>
                    驳回重规划
                  </Button>
                </Space>
              )}
            </Space>
          }
        />
      )}

      {waiting.length > 0 && (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          {waiting.map((w) => (
            <Card
              key={String(w.node_run_id)}
              size="small"
              type="inner"
              title={<Space><LockOutlined /><span>待决策：{w.node_name || w.node_id}</span></Space>}
              extra={orchestrated ? (
                <Tag color="gold" style={{ marginInlineEnd: 0 }}>在左侧对话中答复</Tag>
              ) : (
                <Space>
                  <Button type="primary" size="small" icon={<CheckCircleOutlined />} onClick={() => openGate(w, 'APPROVE')}>
                    通过
                  </Button>
                  <Button size="small" onClick={() => openGate(w, 'SKIP')}>跳过</Button>
                  <Button danger size="small" onClick={() => openGate(w, 'REJECT')}>驳回</Button>
                </Space>
              )}
            >
              <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
                {orchestrated
                  ? '主控 AI 在等待你的决定——直接在左侧对话里回复即可（例如「同意，按方案二执行」），主控会记录答复并继续推进。'
                  : '该节点已产出并挂起等待人工决策。通过后继续执行后续节点（后台跑，页面会自动刷新）；驳回必须写批注。'}
              </Typography.Paragraph>
              {stageNotes.length > 0 && (
                <div style={{ marginBottom: 8, padding: '6px 10px', background: '#fafafa', borderRadius: 4 }}>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    阶段对话里的最近批注（决策与批注是同一条交互链，审批时一并参考）：
                  </Typography.Text>
                  {stageNotes.map((n, i) => (
                    <Typography.Paragraph key={i} style={{ fontSize: 12, margin: '4px 0 0', whiteSpace: 'pre-wrap' }}>
                      · {n.text.length > 160 ? n.text.slice(0, 160) + '…' : n.text}
                    </Typography.Paragraph>
                  ))}
                </div>
              )}
              <Collapse
                size="small"
                items={[{ key: 'out', label: '查看节点产出', children: <OutputBlock raw={w.output} /> }]}
              />
            </Card>
          ))}
        </Space>
      )}

      {orchestrated ? (
        steps.length > 0 ? (
          <Table
            size="small"
            rowKey={(r) => String(r.id)}
            dataSource={steps}
            columns={stepCols}
            pagination={false}
            expandable={{
              expandedRowRender: (r) => (
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  {r.instruction && (
                    <div>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>指令 / 问题：</Typography.Text>
                      <Typography.Paragraph style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap' }}>{r.instruction}</Typography.Paragraph>
                    </div>
                  )}
                  {r.skipReason && (
                    <Typography.Text type="warning" style={{ fontSize: 12 }}>跳过理由：{r.skipReason}</Typography.Text>
                  )}
                  {r.error && <Typography.Text type="danger" style={{ fontSize: 12 }}>错误：{r.error}</Typography.Text>}
                  {r.outputFull && <MarkdownView scroll={false} text={r.outputFull} />}
                </Space>
              ),
              rowExpandable: (r) => !!(r.outputFull || r.skipReason || r.error || r.instruction),
            }}
          />
        ) : (
          <Empty description="主控尚未规划本阶段步骤——在左侧对话里说一句话即可启动" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        )
      ) : nodes.length > 0 ? (
        <Table
          size="small"
          rowKey={(r) => String(r.id)}
          dataSource={nodes}
          columns={cols}
          pagination={false}
          scroll={{ x: 540 }}
          expandable={{
            expandedRowRender: (r) => <OutputBlock raw={r.output_json} />,
            rowExpandable: (r) => !!r.output_json,
          }}
        />
      ) : (
        <Empty
          description={exec.state === 'NO_PLAN'
            ? '在左侧对话里说一句话即可启动——你的第一句话就是需求，主控 AI 会自动规划并执行'
            : '本阶段还没有执行记录（规划未批准或未起跑）'}
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        />
      )}

      <Modal
        open={!!gate}
        title={`${decision === 'APPROVE' ? '通过' : decision === 'SKIP' ? '跳过' : '驳回'}决策门：${gate?.node_name || gate?.node_id || ''}`}
        okText={decision === 'APPROVE' ? '确认通过' : decision === 'SKIP' ? '确认跳过' : '确认驳回'}
        okButtonProps={{ danger: decision === 'REJECT', loading: submitting }}
        onOk={() => void submitGate()}
        onCancel={() => setGate(null)}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          节点 {gate?.node_id} · run {exec.run_id}
        </Typography.Paragraph>
        <Typography.Text style={{ display: 'block', marginBottom: 6 }}>
          {decision === 'REJECT'
            ? '驳回批注（必填，agent 会按它重生成）'
            : decision === 'SKIP'
              ? '跳过原因（可选，仅留档）——该节点不执行、无产出，下游按缺省处理'
              : '通过批注（可选，仅留档）'}
        </Typography.Text>
        <Input.TextArea
          rows={4}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          placeholder={decision === 'REJECT' ? '例如：接口错误码未覆盖并发场景，补齐 409/429 的处理与用例' : '可留空'}
        />
        {gateErr && <Alert type="error" showIcon message={gateErr} style={{ marginTop: 12 }} />}
      </Modal>

      <Modal
        open={planRejectOpen}
        title="驳回规划草稿"
        okText="确认驳回"
        okButtonProps={{ danger: true, loading: approving, disabled: planReason.trim().length === 0 }}
        onOk={() => {
          if (planId == null) return
          if (planReason.trim().length === 0) {
            setPlanErr('驳回必须写理由，否则 PM 不知道要改什么')
            return
          }
          void decidePlan(planId, 'REJECT', planReason.trim())
        }}
        onCancel={() => setPlanRejectOpen(false)}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          驳回后 PM 会重出一版草稿，本轮不会起跑。
        </Typography.Paragraph>
        <Input.TextArea
          rows={4}
          value={planReason}
          onChange={(e) => setPlanReason(e.target.value)}
          placeholder="例如：子任务缺少数据迁移与回滚方案，补齐后再排开发"
        />
        {planErr && <Alert type="error" showIcon message={planErr} style={{ marginTop: 12 }} />}
      </Modal>
    </>
  )
}

/** 模板 code 规则：小写字母/数字/下划线/连字符，1-32 位（与后端校验一致） */
const TEMPLATE_CODE_RE = /^[a-z0-9_-]{1,32}$/

/**
 * 模板编辑器（V30 新形态）：3 阶段 × 执行路径预设。
 *
 * 模板 = 三个固定阶段（需求 → 开发 → 运维）各自的执行路径预设：
 * 每阶段可绑定已发布工作流（批准规划后按它执行，不再重新编译）与执行智能体；
 * 运维阶段额外可绑定服务器（部署/巡检目标）与数据库（数据源连接器）。
 * 创建项目选模板时，这些预设按阶段写入 project_stage_capability，立即生效。
 *
 * 旧「节点链」编辑已下线：project_template_node 仅历史兼容，不再参与执行。
 */

/** 数据源候选（GET /api/datasources） */
interface DsOption {
  id: number
  name: string
  platform?: string
}

interface TemplateDetail {
  id: number
  code: string
  name: string
  description?: string
  builtin?: boolean
  nodes?: TemplateNode[]
  /** V30 新形态：3 阶段执行路径预设 {REQUIREMENT:{workflow,agents},...,OPERATE:{...,servers,connectors}} */
  stages?: Record<string, { workflow?: string; agents?: string[]; servers?: string[]; connectors?: string[] }>
}
interface ProjectTemplate {
  id: number
  code: string
  name: string
  description?: string
  node_count?: number
  builtin?: boolean
  /** V30：3 阶段预设摘要（列表页展示用） */
  stages?: Record<string, { workflow?: string; agents?: string[]; servers?: string[]; connectors?: string[] }>
}


/** 表单字段封装：label + hint + children（模板编辑器用） */
function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <Form.Item label={label} style={{ marginBottom: 12 }}>
      {children}
      {hint && (
        <Typography.Text type='secondary' style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
          {hint}
        </Typography.Text>
      )}
    </Form.Item>
  )
}

function TemplateEditor({ open, code, onClose, onSaved }: {
  open: boolean
  /** null = 新建；非空 = 编辑该 code 的模板 */
  code: string | null
  onClose: () => void
  onSaved: () => void
}) {
  const [form] = Form.useForm()
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [builtin, setBuiltin] = useState(false)
  /** 内置模板「另存为副本」：置真后改走 POST 新建 */
  const [asNew, setAsNew] = useState(false)
  const creating = code === null || asNew

  /** 候选：内置 agent、已发布工作流、数据源连接器（打开编辑器时拉一次，失败退化为空不阻塞保存） */
  const [agents, setAgents] = useState<AgentOption[]>([])
  const [workflows, setWorkflows] = useState<WorkflowOption[]>([])
  const [datasources, setDatasources] = useState<DsOption[]>([])

  /** 3 阶段预设：{workflow, agents, servers?, connectors?} */
  const [stages, setStages] = useState<
    Record<string, { workflow?: string; agents?: string[]; servers?: string[]; connectors?: string[] }>
  >({})

  useEffect(() => {
    if (!open) return
    let cancelled = false
    apiJson<AgentOption[]>('/api/agents')
      .then((d) => { if (!cancelled) setAgents(Array.isArray(d) ? d : []) })
      .catch(() => { if (!cancelled) setAgents([]) })
    apiJson<WorkflowOption[]>('/api/definitions')
      .then((d) => { if (!cancelled) setWorkflows(Array.isArray(d) ? d : []) })
      .catch(() => { if (!cancelled) setWorkflows([]) })
    apiJson<DsOption[]>('/api/datasources')
      .then((d) => { if (!cancelled) setDatasources(Array.isArray(d) ? d : []) })
      .catch(() => { if (!cancelled) setDatasources([]) })
    return () => { cancelled = true }
  }, [open])

  useEffect(() => {
    if (!open) return
    form.resetFields()
    setBuiltin(false)
    setAsNew(false)
    setLoadError(null)
    setStages({})
    if (code === null) return
    let cancelled = false
    setLoading(true)
    apiJson<TemplateDetail>('/api/projects/templates/' + code)
      .then((d) => {
        if (cancelled) return
        form.setFieldsValue({ code: d.code, name: d.name, description: d.description })
        setBuiltin(!!d.builtin)
        const s = d.stages || {}
        setStages({ REQUIREMENT: s.REQUIREMENT || {}, DEVELOP: s.DEVELOP || {}, OPERATE: s.OPERATE || {} })
      })
      .catch((e: Error) => { if (!cancelled) setLoadError(e.message || '模板加载失败') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [open, code, form])

  const patchStage = (stage: string, patch: Partial<{ workflow?: string; agents?: string[]; servers?: string[]; connectors?: string[] }>) =>
    setStages((x) => ({ ...x, [stage]: { ...(x[stage] || {}), ...patch } }))

  const submit = async () => {
    const v = await form.validateFields().catch(() => null)
    if (!v) return
    const payload = { code: v.code, name: v.name, description: v.description || '', stages }
    setBusy(true)
    try {
      if (creating) await apiJson('/api/projects/templates', 'POST', payload)
      else await apiJson('/api/projects/templates/' + code, 'PUT', payload)
      message.success(creating ? '模板已创建' : '模板已保存')
      onSaved()
      onClose()
    } catch (e) {
      // 后端 400（code 重复、内置模板受限等）原样透出，不吞错误
      if (e instanceof Error) message.error(e.message)
    } finally {
      setBusy(false)
    }
  }

  const agentOptions = agents
    .filter((a) => a.status === 'PUBLISHED')
    .map((a) => ({ value: a.name, label: a.description ? `${a.description}（${a.name}）` : a.name }))

  const workflowOptions = workflows
    .filter((w) => w.status === 'PUBLISHED')
    .map((w) => ({ value: String(w.id), label: `${w.name}（#${w.id} v${w.current_version ?? '?'}）` }))

  // capability.connectors 沿用画布引用格式 connector:{id}
  const dsOptions = datasources.map((d) => ({
    value: `connector:${d.id}`,
    label: `${d.name}（${d.platform || 'jdbc'} #${d.id}）`,
  }))

  const stageDefs: { key: string; label: string; color: string; desc: string }[] = [
    { key: 'REQUIREMENT', label: '需求阶段', color: 'blue', desc: '需求澄清与范围共识' },
    { key: 'DEVELOP', label: '开发阶段', color: 'gold', desc: '架构设计 → 开发 → 测试' },
    { key: 'OPERATE', label: '运维阶段', color: 'green', desc: '部署、巡检与变更；可绑定服务器与数据库' },
  ]

  const stageItems = stageDefs.map((def) => {
    const s = stages[def.key] || {}
    const bound = [
      s.workflow ? `流程 #${s.workflow}` : null,
      (s.agents || []).length > 0 ? `${s.agents!.length} 个智能体` : null,
      def.key === 'OPERATE' && (s.servers || []).length > 0 ? `${s.servers!.length} 台服务器` : null,
      def.key === 'OPERATE' && (s.connectors || []).length > 0 ? `${s.connectors!.length} 个数据库` : null,
    ].filter(Boolean) as string[]
    return {
      key: def.key,
      label: (
        <Space size={6} wrap>
          <Tag color={def.color} style={{ marginInlineEnd: 0 }}>{def.label}</Tag>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{def.desc}</Typography.Text>
          {bound.map((b, i) => <Tag key={i} style={{ marginInlineEnd: 0 }}>{b}</Tag>)}
          {bound.length === 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>未预设</Typography.Text>
          )}
        </Space>
      ),
      children: (
        <div>
          <Field
            label="绑定工作流"
            hint="批准规划后直接按该已发布工作流执行（不再重新编译画布）；留空则按 PM 规划编译"
          >
            <Select
              style={{ width: '100%' }}
              value={s.workflow || undefined}
              options={workflowOptions}
              onChange={(val) => patchStage(def.key, { workflow: val ? String(val) : '' })}
              placeholder={workflowOptions.length ? '选择已发布工作流（可留空）' : '无已发布工作流'}
              showSearch
              optionFilterProp="label"
              allowClear
              onClear={() => patchStage(def.key, { workflow: '' })}
            />
          </Field>
          <Field
            label="执行智能体"
            hint="该阶段对话与执行优先使用的智能体（可多选，第一个为主执行者）"
          >
            <Select
              mode="multiple"
              style={{ width: '100%' }}
              value={s.agents || []}
              options={agentOptions}
              onChange={(val) => patchStage(def.key, { agents: val as string[] })}
              placeholder={agentOptions.length ? '选择执行智能体（可留空按阶段默认）' : '未加载到 agent 列表'}
              maxTagCount={3}
            />
          </Field>
          {def.key === 'OPERATE' && (
            <>
              <Field
                label="服务器"
                hint="部署 / 巡检 / 变更的目标机器，如 root@39.96.116.199:2259；输入后回车添加"
              >
                <Select
                  mode="tags"
                  style={{ width: '100%' }}
                  value={s.servers || []}
                  onChange={(val) => patchStage('OPERATE', { servers: val as string[] })}
                  placeholder="如 root@1.2.3.4:22（回车确认）"
                  tokenSeparators={[' ']}
                />
              </Field>
              <Field
                label="数据库"
                hint="该阶段可查询的数据源连接器（JDBC）；运维排障时 agent 可直接查库"
              >
                <Select
                  mode="multiple"
                  style={{ width: '100%' }}
                  value={s.connectors || []}
                  options={dsOptions}
                  onChange={(val) => patchStage('OPERATE', { connectors: val as string[] })}
                  placeholder={dsOptions.length ? '选择可用的数据库（可留空）' : '未加载数据源，请先在「数据源」页添加'}
                  maxTagCount={3}
                />
              </Field>
            </>
          )}
        </div>
      ),
    }
  })

  return (
    <Drawer
      title={code === null ? '新建项目模板' : (asNew ? '另存为新模板' : '编辑模板 ' + code)}
      open={open}
      onClose={onClose}
      width={640}
      extra={
        <Space>
          {code !== null && builtin && !asNew && (
            <Button size="small" onClick={() => { setAsNew(true); form.setFieldsValue({ code: code + '_copy' }) }}>
              另存为副本
            </Button>
          )}
          <Button size="small" onClick={onClose}>取消</Button>
          <Button size="small" type="primary" loading={busy} disabled={loading} onClick={() => void submit()}>
            保存
          </Button>
        </Space>
      }
    >
      <Spin spinning={loading}>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="模板 = 3 阶段执行路径预设"
          description="需求 → 开发 → 运维三个固定阶段，各自预设「绑定工作流 + 执行智能体」；运维阶段还可绑定服务器与数据库。创建项目选模板时，预设按阶段写入项目能力绑定，立即生效。"
        />
        {loadError && (
          <Alert type="error" message="模板加载失败" description={loadError} style={{ marginBottom: 12 }} />
        )}
        {builtin && !asNew && (
          <Alert
            type="warning"
            showIcon
            icon={<LockOutlined />}
            style={{ marginBottom: 12 }}
            message="内置模板：可参考与编辑，但删除接口会拒绝（后端返回 400）。如需定制请点「另存为副本」。"
          />
        )}
        <Form form={form} layout="vertical">
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item
                name="code"
                label="模板编码 code"
                rules={[
                  { required: true, message: '请输入模板编码' },
                  { pattern: TEMPLATE_CODE_RE, message: '仅小写字母、数字、下划线、连字符，1-32 位' },
                ]}
              >
                <Input
                  className="xj-mono"
                  disabled={!creating}
                  maxLength={32}
                  placeholder="如：my_flow"
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="name"
                label="模板名称"
                rules={[{ required: true, message: '请输入模板名称' }]}
              >
                <Input maxLength={128} placeholder="如：我的研发流程" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} maxLength={512} placeholder="适用场景说明" />
          </Form.Item>
        </Form>

        <Typography.Text strong>阶段预设</Typography.Text>
        <div style={{ marginTop: 8 }}>
          <Collapse items={stageItems} size="small" defaultActiveKey={['REQUIREMENT']} />
        </div>
      </Spin>
    </Drawer>
  )
}



/** 阶段顺序（单向流转，不可回退） */
const STAGE_ORDER = ['REQUIREMENT', 'DEVELOP', 'OPERATE']

/** 阶段中文标签 */
function stageLabel(stage?: string | null): string {
  const m: Record<string, string> = { REQUIREMENT: '需求', DEVELOP: '开发', OPERATE: '运维' }
  return m[stage || ''] || stage || '—'
}

/** 阶段颜色 */
function stageColor(stage?: string | null): string {
  const m: Record<string, string> = { REQUIREMENT: 'blue', DEVELOP: 'gold', OPERATE: 'green' }
  return m[stage || ''] || 'default'
}

/** 下一阶段；OPERATE 之后无（返回 null） */
function nextStageOf(stage: string): string | null {
  const i = STAGE_ORDER.indexOf(stage)
  return i >= 0 && i < STAGE_ORDER.length - 1 ? STAGE_ORDER[i + 1] : null
}

/** 阶段状态 → 中文 + 颜色（生命周期总览用） */
function stageStatusView(status?: string | null): { text: string; color: string } {
  const s = status || 'NOT_STARTED'
  const m: Record<string, { text: string; color: string }> = {
    DONE: { text: '已完成', color: 'success' },
    IN_PROGRESS: { text: '进行中', color: 'processing' },
    BLOCKED: { text: '受阻', color: 'error' },
    NOT_STARTED: { text: '未开始', color: 'default' },
  }
  return m[s] || { text: s, color: 'default' }
}

/** 阶段视图（生命周期总览）：status 为后端 project_stage.status，iterations 为运维阶段迭代轮次列表 */
interface StageView {
  stage: string
  label: string
  status: string
  resident?: boolean
  iterations: string[]
}

/**
 * 推进按钮可用性 + 禁用原因。
 *
 * <p>判定必须与后端推进门<b>同源</b>：V27 起完成度的真相是「批准规划编译出来的那条 run」，
 * 而 {@code project_work_node} 台账既不再被规划写入、也没有 run 回写通道（实测 V27 之后新项目
 * 一个节点都没有，老项目的台账 status 永远停在 NOT_STARTED）。原先按台账节点判定的结果是
 * 前端按钮亮着、后端回 400，两边长期不一致，用户只看到一个没有原因的 Bad Request。</p>
 */
function advanceCheck(view: StageView, exec?: StageExecution | null): { enabled: boolean; reason: string } {
  if (view.resident) {
    return { enabled: false, reason: `「${view.label}」是常驻阶段，不推进；新需求 / Bug 走新一轮迭代` }
  }
  if (!exec) {
    return { enabled: false, reason: '还没取到本阶段执行状态，稍候会自动刷新' }
  }
  switch (exec.state) {
    case 'NO_PLAN':
      return { enabled: false, reason: `「${view.label}」还没有规划，先让 PM 出规划草稿` }
    case 'PENDING_APPROVAL':
      return { enabled: false, reason: '规划就绪——在右侧「阶段执行」提示卡里批准，或直接在左侧对话里说一句话启动' }
    case 'NOT_STARTED':
    case 'EXECUTING':
      return { enabled: false, reason: '本阶段还在执行中，等所有子任务跑完再推进' }
    case 'WAITING_DECISION':
      return { enabled: false, reason: '有决策门正在等审批——先在下方放行或驳回' }
    default:
      // FINISHED：run 已成功，剩下两道门（收口总结批准、未决项豁免）由后端把关
      return {
        enabled: true,
        reason: '本阶段执行已完成；推进前需产出并批准收口总结，若有未决项要显式豁免',
      }
  }
}

/** 阶段链可视化：横向节点链 + 状态着色 + 依赖箭头 */
function NodeChain({ nodes, onNodeClick }: {
  nodes: WorkNode[]
  onNodeClick: (n: WorkNode) => void
}) {
  if (nodes.length === 0) {
    return <Empty description="暂无工作节点" style={{ padding: 24 }} />
  }
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, overflowX: 'auto', padding: '8px 4px' }}>
      {nodes.map((n, i) => {
        const sv = nodeStatusView(n.status)
        return (
          <div key={n.id} style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
            {i > 0 && <span style={{ color: '#bbb' }}>→</span>}
            <div
              onClick={() => onNodeClick(n)}
              style={{
                border: `1px solid ${sv.color === 'default' ? '#d9d9d9' : sv.color}`,
                borderRadius: 8,
                padding: '8px 12px',
                minWidth: 96,
                textAlign: 'center',
                cursor: 'pointer',
                background: sv.color === 'default' ? '#fafafa' : '#fff',
                transition: 'box-shadow 0.2s',
              }}
              onMouseEnter={(e) => { e.currentTarget.style.boxShadow = '0 2px 8px rgba(0,0,0,0.12)' }}
              onMouseLeave={(e) => { e.currentTarget.style.boxShadow = 'none' }}
            >
              <div style={{ fontWeight: 600, fontSize: 13 }}>
                {NODE_TYPE_LABEL[n.type] || n.type}
              </div>
              <div style={{ fontSize: 11, color: '#999' }}>{n.seq}</div>
              <Tag color={sv.color} style={{ marginTop: 4 }}>{sv.text}</Tag>
              {n.owner_role && (
                <div style={{ fontSize: 11, color: '#999', marginTop: 2 }}>{n.owner_role}</div>
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}


/**
 * 生命周期总览（V27 口径 + AI 自动流转理念）。
 *
 * <p>设计理念：AI 推动整个流程，用户只是介入——页面展示「AI 推进到哪了」，
 * 需要用户介入的点（批准规划 / 决策门 / 澄清对话）突出出来。阶段内容不再读
 * project_work_node 台账（V27 起规划不落台账，新项目 0 节点，读它必然与阶段执行
 * 对不上）：当前阶段读 exec（规划子任务 + run 进度），历史阶段读交付物留档计数。</p>
 */
function StageGroups({ views, currentStage, exec, artifacts, onAdvance, advancing }: {
  views: StageView[]
  currentStage: string
  /** 阶段执行视图：推进判定要以它（即 run）为准，不能用台账节点状态 */
  exec?: StageExecution | null
  /** 交付物留档：按 stage 归属计数（V29 起阶段对话登记的交付物带 stage） */
  artifacts: Artifact[]
  onAdvance: (v: StageView) => void
  advancing: boolean
}) {
  const stageArts = (stage: string) => artifacts.filter((a) => a.stage === stage)
  const runStateTag = (st?: string) => {
    const s = st || 'PENDING'
    const color = s === 'SUCCESS' ? 'success' : s === 'RUNNING' ? 'processing'
      : s === 'FAILED' ? 'error' : s === 'SUSPENDED' ? 'warning' : 'default'
    const text = s === 'SUCCESS' ? '完成' : s === 'RUNNING' ? '执行中' : s === 'FAILED' ? '失败'
      : s === 'SUSPENDED' ? '待决策' : s === 'PENDING' ? '未起跑' : s
    return <Tag color={color} style={{ marginInlineEnd: 0 }}>{text}</Tag>
  }
  return (
    <div>
      <Steps
        size="small"
        current={Math.max(0, STAGE_ORDER.indexOf(currentStage))}
        style={{ marginBottom: 16 }}
        items={views.map((v) => ({
          title: v.label,
          description: stageStatusView(v.status).text + (v.resident ? ' · 常驻' : ''),
          status: v.status === 'DONE'
            ? 'finish'
            : v.status === 'BLOCKED'
              ? 'error'
              : v.stage === currentStage
                ? 'process'
                : 'wait',
        }))}
      />
      <Collapse
        size="small"
        defaultActiveKey={views.map((v) => v.stage)}
        items={views.map((v) => {
          const sv = stageStatusView(v.status)
          const arts = stageArts(v.stage)
          const isCurrent = v.stage === currentStage
          const isOperate = v.stage === 'OPERATE'
          const check = advanceCheck(v, exec)
          // 新内核（AI 编排）模式：主控 complete_stage 自动收口推进，右边栏不提供人驱动按钮
          const orchestrated = (exec?.steps || []).length > 0
          const tasks = isCurrent && exec?.plan?.tasks ? (exec.plan.tasks as PlanTask[]) : []
          const nodeRuns = exec?.node_runs || []
          return {
            key: v.stage,
            label: (
              <Space size={6} wrap>
                <Tag color={stageColor(v.stage)} style={{ marginInlineEnd: 0 }}>{v.label}</Tag>
                <Tag color={sv.color} style={{ marginInlineEnd: 0 }}>{sv.text}</Tag>
                {arts.length > 0 && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    交付物 {arts.length}
                  </Typography.Text>
                )}
                {isOperate && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    迭代 {(isCurrent && exec?.iteration) || 1} 轮
                  </Typography.Text>
                )}
                {v.resident && <Tag color="purple" style={{ marginInlineEnd: 0 }}>常驻</Tag>}
                {isCurrent && <Tag color="processing" style={{ marginInlineEnd: 0 }}>当前阶段</Tag>}
              </Space>
            ),
            extra: isCurrent && !isOperate ? (
              orchestrated ? (
                <Tag color="processing" style={{ marginInlineEnd: 0 }}>AI 自动流转</Tag>
              ) : (
              <Tooltip
                title={check.enabled
                  ? `AI 会自动推进；此按钮用于自动流转失败时人工兜底。目标「${stageLabel(nextStageOf(v.stage))}」（单向，不可回退）`
                  : check.reason}
              >
                <span style={{ display: 'inline-block' }}>
                  <Button
                    size="small"
                    type="primary"
                    icon={<ForwardOutlined />}
                    disabled={!check.enabled}
                    loading={advancing}
                    onClick={(e) => { e.stopPropagation(); onAdvance(v) }}
                  >
                    手动推进
                  </Button>
                </span>
              </Tooltip>
              )
            ) : isCurrent && isOperate ? (
              orchestrated ? (
                <Tag color="processing" style={{ marginInlineEnd: 0 }}>AI 自主运维</Tag>
              ) : (
              <Tooltip title="迭代入口开发中（后端 startIteration 端点未上线）：当前请直接在对话里提出新需求，AI 会自主承接">
                <span style={{ display: 'inline-block' }}>
                  <Button size="small" icon={<SyncOutlined />} disabled>
                    发起迭代
                  </Button>
                </span>
              </Tooltip>
              )
            ) : null,
            children: isCurrent ? (
              <Space direction="vertical" size={6} style={{ width: '100%' }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  AI 自动流转：本阶段流程跑完后自动收口并推进下一阶段；需要你介入的只有决策门审批与对话澄清。
                </Typography.Text>
                {orchestrated ? (
                  // 新内核：plan.tasks 是创建时的规划草稿（不参与执行），显示真实 steps 台账
                  (exec?.steps || []).length > 0 ? (
                    (exec?.steps || []).map((s) => (
                      <Space key={String(s.id)} size={6} wrap>
                        <Typography.Text style={{ fontSize: 13 }}>#{s.seq} {s.name}</Typography.Text>
                        <Tag color={STEP_STATUS_COLOR[s.status] || 'default'} style={{ marginInlineEnd: 0 }}>
                          {STEP_STATUS_LABEL[s.status] || s.status}
                        </Tag>
                        {s.outputSummary && (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {s.outputSummary.length > 30 ? s.outputSummary.slice(0, 30) + '…' : s.outputSummary}
                          </Typography.Text>
                        )}
                      </Space>
                    ))
                  ) : (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      主控尚未规划本阶段步骤——在左侧对话里说一句话即可启动。
                    </Typography.Text>
                  )
                ) : tasks.length > 0 ? (
                  tasks.map((tk) => {
                    const run = nodeRuns.find((nr) => String(nr.seq) === String(tk.key))
                    return (
                      <Space key={String(tk.key ?? tk.name)} size={6} wrap>
                        <Typography.Text style={{ fontSize: 13 }}>{tk.name || `子任务 ${tk.key}`}</Typography.Text>
                        {((tk.agents || tk.participants) || []).length > 0 && (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {((tk.agents || tk.participants) as string[]).join('、')}
                          </Typography.Text>
                        )}
                        {runStateTag(run?.status)}
                      </Space>
                    )
                  })
                ) : (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {exec?.state === 'PENDING_APPROVAL' ? '规划就绪——在左侧对话里说一句话即可启动' : '尚无规划子任务'}
                  </Typography.Text>
                )}
              </Space>
            ) : v.status === 'DONE' ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                AI 已自动收口并推进{arts.length > 0 ? `；交付物 ${arts.length} 份见「交付物」页签` : ''}
              </Typography.Text>
            ) : (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                前序阶段完成后 AI 自动推进至此
              </Typography.Text>
            ),
          }
        })}
      />
    </div>
  )
}



function ProjectDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selectedNode, setSelectedNode] = useState<WorkNode | null>(null)
  const [advancing, setAdvancing] = useState(false)
  /** 阶段执行视图数据，由本页轮询后喂给 StageExecutionPanel */
  const [exec, setExec] = useState<StageExecution | null>(null)
  const [execBusy, setExecBusy] = useState(false)
  const [artifactDrawer, setArtifactDrawer] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const d = await apiJson<ProjectDetail>('/api/projects/' + id)
      setDetail(d)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [id])

  /** 拉阶段执行视图；silent=true 时不置 busy，避免轮询把面板闪成加载态 */
  const loadExec = useCallback(async (silent = false) => {
    if (!silent) setExecBusy(true)
    try {
      setExec(await apiJson<StageExecution>(`/api/projects/${id}/stage-execution`))
    } catch {
      // 执行视图取不到不打断详情页主流程（例如规划尚未生成），保留上一次数据即可
    } finally {
      if (!silent) setExecBusy(false)
    }
  }, [id])

  useEffect(() => {
    void load()
    void loadExec()
  }, [load, loadExec])

  // 执行中 / 等决策 / 已批准未起跑时 5s 轮询：审批走 resumeAsync 立即返回、后续节点后台跑，
  // 单个 agent 节点实测 165s~600s+，不轮询的话点完「通过」页面会一直停在旧状态。
  // 跑完（FINISHED）与无规划（NO_PLAN）不轮询，避免空转打后端。
  // 依赖必须是 state 字符串而非 exec 对象：每次轮询响应都是新对象，依赖对象会让 interval
  // 每 5s 被拆建一次（且守卫一改就会触发 cleanup 后不再重建、轮询永久丢失的老 bug）。
  const execState = exec?.state
  useEffect(() => {
    if (!execState || !EXEC_LIVE_STATES.has(execState)) return
    const t = setInterval(() => { void loadExec(true) }, 5000)
    return () => clearInterval(t)
  }, [execState, loadExec])

  const runNode = async (n: WorkNode) => {
    try {
      await apiJson(`/api/projects/${id}/nodes/${n.id}/run`, 'POST', {})
      message.success(`节点「${NODE_TYPE_LABEL[n.type] || n.type}」已启动`)
      await load()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    }
  }

  const completeNode = async (n: WorkNode) => {
    try {
      await apiJson(`/api/projects/${id}/nodes/${n.id}/complete`, 'POST', {})
      message.success(`节点「${NODE_TYPE_LABEL[n.type] || n.type}」已完成`)
      await load()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    }
  }

  if (loading) {
    return <div style={{ padding: 80, textAlign: 'center' }}><Spin /></div>
  }
  if (error || !detail) {
    return (
      <div style={{ padding: 60 }}>
        <Alert type="error" message="加载失败" description={error} action={<Button onClick={() => void load()}>重试</Button>} />
      </div>
    )
  }

  const currentStage = detail.current_stage || 'REQUIREMENT'

  /** 生命周期视图：3 阶段状态按当前阶段推导（V27 起完成度真相在 run，台账仅历史兼容） */
  const views = STAGE_ORDER.map((st) => ({
    stage: st,
    label: stageLabel(st),
    status: STAGE_ORDER.indexOf(st) < STAGE_ORDER.indexOf(currentStage) ? 'DONE'
      : st === currentStage ? 'IN_PROGRESS' : 'NOT_STARTED',
    resident: st === 'OPERATE',
    iterations: [] as string[],
  }))

  /** 推进阶段（AI 自动流转失败时的人工兜底；单向不可回退） */
  const advanceStage = async (v: StageView) => {
    setAdvancing(true)
    try {
      const r = await apiJson<{ current_stage?: string }>(`/api/projects/${id}/stage/advance`, 'POST', {})
      const to = stageLabel(r?.current_stage || nextStageOf(v.stage))
      message.success(`项目已推进到「${to}」阶段`)
      await load()
      // 推进后新阶段会自动产出规划草稿，执行视图要跟着换成新阶段那条
      await loadExec()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setAdvancing(false)
    }
  }

  /** 运维阶段发起新一轮迭代（后端 iterate 端点未建，暂复用 advance，由后端校验拒绝时提示） */
  const deliverables = detail.artifacts.filter((a) => a.kind === 'DELIVERABLE')
  const processes = detail.artifacts.filter((a) => a.kind === 'PROCESS')

  return (
    <div>
      <Space style={{ marginBottom: 16 }} align="center">
        <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回</Button>
        <Typography.Title level={4} style={{ margin: 0 }}>{detail.name}</Typography.Title>
        <Tag color={projectStatusColor(detail.status)}>{detail.status === 'ARCHIVED' ? '已归档' : '进行中'}</Tag>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {detail.root_path}
        </Typography.Text>
        <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </Space>

      <Card title="阶段链" style={{ marginBottom: 16 }}>
        <NodeChain nodes={detail.nodes} onNodeClick={(n) => setSelectedNode(n)} />
      </Card>

      {/* 左右分栏（参考 Easy-Claw 工作区交互）：左侧对话是主工作界面，右侧栏聚合执行/生命周期/交付物/会话 */}
      <Row gutter={16} align="top">
        <Col xs={24} lg={15}>
          {/* 阶段对话（V28）：项目的主工作界面。V27 起规划不再落 project_work_node，
              新项目一个台账节点都没有，原先挂在节点抽屉里的「对话」按钮已无入口，
              所以对话对象改为「当前阶段 + 可选聚焦子任务」，走 POST /stage/chat。 */}
          <Card
            title={`阶段对话（${exec?.stage_label || stageLabel(currentStage)} · 第 ${exec?.iteration || 1} 轮）`}
            size="small"
            extra={
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                阶段规范已作为 skill 绑定到 agent，对话自动携带；最终产出自动登记为交付物留档
              </Typography.Text>
            }
          >
            <StageChatPanel projectId={id} exec={exec} />
          </Card>
        </Col>

        <Col xs={24} lg={9}>
          <Tabs
            defaultActiveKey="exec"
            size="small"
            items={[
              {
                key: 'exec',
                label: '阶段执行',
                children: execBusy && !exec ? (
                  <div style={{ padding: 32, textAlign: 'center' }}><Spin /></div>
                ) : (
                  <StageExecutionPanel
                    projectId={id}
                    exec={exec}
                    busy={execBusy}
                    onRefresh={() => void loadExec()}
                    onChanged={() => { void loadExec(); void load() }}
                  />
                ),
              },
              {
                key: 'stages',
                label: '生命周期',
                children: (
                  <StageGroups
                    views={views}
                    currentStage={currentStage}
                    exec={exec}
                    artifacts={detail.artifacts}
                    onAdvance={(v) => void advanceStage(v)}
                    advancing={advancing}
                  />
                ),
              },
              {
                key: 'artifacts',
                label: `交付物 ${deliverables.length}`,
                children: (
                  <>
                    <div style={{ marginBottom: 8, textAlign: 'right' }}>
                      <Button size="small" type="link" onClick={() => setArtifactDrawer(true)}>登记留档</Button>
                    </div>
                    {deliverables.length === 0
                      ? <Empty description="暂无交付留档" />
                      : deliverables.map((a) => (
                        <Card key={a.id} size="small" style={{ marginBottom: 8 }}>
                          <Space direction="vertical" size={2} style={{ width: '100%' }}>
                            <Space>
                              <FileTextOutlined />
                              <Typography.Text strong>{a.name}</Typography.Text>
                              {a.version != null && <Tag>v{a.version}</Tag>}
                              <Tag color="green">交付</Tag>
                            </Space>
                            {a.content_md && (
                              <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }} ellipsis={{ rows: 2 }}>
                                {a.content_md}
                              </Typography.Paragraph>
                            )}
                          </Space>
                        </Card>
                      ))}
                    {processes.length > 0 && (
                      <>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          过程留档（{processes.length}）
                        </Typography.Text>
                        {processes.map((a) => (
                          <Card key={a.id} size="small" style={{ marginBottom: 8 }}>
                            <Space>
                              <FileTextOutlined />
                              <Typography.Text strong>{a.name}</Typography.Text>
                              <Tag>过程</Tag>
                            </Space>
                          </Card>
                        ))}
                      </>
                    )}
                  </>
                ),
              },
              {
                key: 'sessions',
                label: '会话',
                children: <StageSessionsPanel projectId={id} />,
              },
            ]}
          />
        </Col>
      </Row>

            <NodeDrawer
        node={selectedNode ? detail.nodes.find((n) => n.id === selectedNode.id) ?? selectedNode : null}
        nodes={detail.nodes}
        artifacts={detail.artifacts}
        onClose={() => setSelectedNode(null)}
        onRun={runNode}
        onComplete={completeNode}
      />
      <ArtifactDrawer
        open={artifactDrawer}
        projectId={id}
        nodes={detail.nodes}
        onClose={() => setArtifactDrawer(false)}
        onSaved={() => void load()}
      />
    </div>
  )
}

/** 登记留档抽屉 */
function ArtifactDrawer({ open, projectId, nodes, onClose, onSaved }: {
  open: boolean
  projectId: string
  nodes: WorkNode[]
  onClose: () => void
  onSaved: () => void
}) {
  const [form] = Form.useForm()
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    const v = await form.validateFields().catch(() => null)
    if (!v) return
    setBusy(true)
    try {
      await apiJson(`/api/projects/${projectId}/nodes/${v.node_id}/artifacts`, 'POST', {
        kind: v.kind,
        name: v.name,
        type: v.type || 'DOC',
        content_md: v.content_md,
        version: v.version,
      })
      message.success('留档已登记')
      form.resetFields()
      onSaved()
      onClose()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer
      title="登记留档"
      open={open}
      onClose={onClose}
      width={420}
      extra={<Button type="primary" loading={busy} onClick={() => void submit()}>保存</Button>}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ kind: 'DELIVERABLE', type: 'DOC', node_id: nodes.length ? nodes[0].id : 'none' }}
      >
        <Form.Item
          name="node_id"
          label="所属节点"
          rules={[{ required: true, message: '请选择节点' }]}
          extra={nodes.length ? undefined : '当前项目无节点（新版阶段制），留档挂在项目级'}
        >
          <Select
            options={[
              { value: 'none', label: '不关联节点（项目级留档）' },
              ...nodes.map((n) => ({
                value: n.id,
                label: `${NODE_TYPE_LABEL[n.type] || n.type}（${n.seq}）`,
              })),
            ]}
          />
        </Form.Item>
        <Form.Item name="kind" label="留档类型" rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'DELIVERABLE', label: '交付留档（跨阶段传递，下一节点只读）' },
              { value: 'PROCESS', label: '过程留档（本阶段内部，可追溯）' },
            ]}
          />
        </Form.Item>
        <Form.Item name="name" label="留档名称" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="如：需求规格说明书" />
        </Form.Item>
        <Form.Item name="type" label="内容类型">
          <Select
            options={[
              { value: 'DOC', label: '文档' },
              { value: 'CODE', label: '代码' },
              { value: 'REPORT', label: '报告' },
              { value: 'CONFIG', label: '配置' },
            ]}
          />
        </Form.Item>
        <Form.Item name="version" label="版本号">
          <Input placeholder="如：1（交付留档固化版本）" />
        </Form.Item>
        <Form.Item name="content_md" label="内容摘要">
          <Input.TextArea rows={4} placeholder="留档内容摘要（可检索）" />
        </Form.Item>
      </Form>
    </Drawer>
  )
}

/** 项目看板主页面：列表 + 详情双视图 */
export default function Project() {
  const navigate = useNavigate()
  const { id } = useParams()
  const [projects, setProjects] = useState<ProjectRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [tplOpen, setTplOpen] = useState(false)
  const [tplCode, setTplCode] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await apiJson<ProjectRow[]>('/api/projects')
      setProjects(data)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { if (!id) void load() }, [id, load])

  // 有 id 参数 → 详情视图；返回列表时重拉（列表→详情→返回，组件未重挂载，需手动刷新）
  if (id) {
    return <ProjectDetail id={id} onBack={() => { navigate('/project'); void load() }} />
  }

  return (
    <div>
      <Space style={{ marginBottom: 16 }} align="center">
        <Typography.Title level={4} style={{ margin: 0 }}>项目列表</Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建项目</Button>
        {SHOW_TEMPLATE_ENTRY && (
          <Button size="small" icon={<SettingOutlined />} onClick={() => { setTplCode(null); setTplOpen(true) }}>
            模板管理
          </Button>
        )}
        <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </Space>

      {error && <Alert type="error" message="加载失败" description={error} style={{ marginBottom: 16 }} />}

      {loading ? (
        <div style={{ padding: 80, textAlign: 'center' }}><Spin /></div>
      ) : projects.length === 0 ? (
        <Empty description="暂无项目，点击「新建项目」开始">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建项目</Button>
        </Empty>
      ) : (
        <Row gutter={[16, 16]}>
          {projects.map((p) => (
            <Col key={p.id} xs={24} sm={12} md={8} lg={6}>
              <Card
                hoverable
                onClick={() => navigate(`/project/${p.id}`)}
                title={
                  <Space>
                    <FolderOpenOutlined />
                    <span>{p.name}</span>
                  </Space>
                }
                extra={<Tag color={projectStatusColor(p.status)}>{p.status === 'ARCHIVED' ? '已归档' : '进行中'}</Tag>}
              >
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }} ellipsis>
                  {p.root_path}
                </Typography.Paragraph>
                <Space size="small" style={{ fontSize: 12, color: '#999' }}>
                  <span>创建人：{p.created_by || '—'}</span>
                  <span>· {fmtDate(p.created_at)}</span>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      )}

      <CreateProjectModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(newId) => {
          setCreateOpen(false)
          navigate(`/project/${newId}`)
        }}
      />
      <TemplateEditor open={tplOpen} code={tplCode} onClose={() => setTplOpen(false)} onSaved={() => void load()} />
    </div>
  )
}