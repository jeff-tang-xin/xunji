import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Button, Select, Tag, Typography, Space, Collapse, message, Modal, Alert, Drawer, Form,
  Input, InputNumber, Checkbox, Tooltip, Divider, Popconfirm, Switch,
} from 'antd'
import {
  PlayCircleFilled, StopFilled, PlusCircleOutlined, RobotOutlined, ApiOutlined,
  ToolOutlined, ZoomInOutlined, ZoomOutOutlined, OneToOneOutlined, CompressOutlined,
  SaveOutlined, SendOutlined, PlusOutlined,
} from '@ant-design/icons'
import LogicFlow from '@logicflow/core'
import '@logicflow/core/lib/style/index.css'
import { apiJson } from '../api/client'
import type { CanvasJson, CanvasNode, DefinitionSummary, AssetRow } from '../types/api'

/* ---------- 节点类型视觉（对齐原型：START 绿 / END 红 / AGENT 蓝 / ATOMIC 紫 / WORKFLOW 青） ---------- */
const KIND_STYLE: Record<string, { stroke: string; fill: string; label: string }> = {
  START: { stroke: '#52c41a', fill: '#f6ffed', label: '开始' },
  END: { stroke: '#ff4d4f', fill: '#fff1f0', label: '结束' },
  AGENT: { stroke: '#1677ff', fill: '#e6f4ff', label: 'Agent' },
  ATOMIC: { stroke: '#722ed1', fill: '#f9f0ff', label: '连接器' },
  WORKFLOW: { stroke: '#13c2c2', fill: '#e6fffb', label: '工作流' },
}

const kindStyle = (kind?: string) => KIND_STYLE[kind || ''] || KIND_STYLE.AGENT

/* ---------- 模板（新建时初始画布：START → AGENT → END） ---------- */
const GRAPH = {
  nodes: [
    { id: 'start', type: 'rect', x: 260, y: 80, text: '开始', kind: 'START' },
    { id: 'n1', type: 'rect', x: 260, y: 200, text: 'Agent 节点', kind: 'AGENT' },
    { id: 'end', type: 'rect', x: 260, y: 320, text: '结束', kind: 'END' },
  ],
  edges: [
    { id: 'e0', sourceNodeId: 'start', targetNodeId: 'n1', type: 'polyline' },
    { id: 'e1', sourceNodeId: 'n1', targetNodeId: 'end', type: 'polyline' },
  ],
}

/** 决策门配置（canvasJson nodes[].decisionGate，后端 DecisionGateDef） */
interface GateConfig {
  interaction?: 'WORKSPACE' | 'FORM'
  onReject?: 'REDO' | 'TERMINATE' | 'ROLLBACK'
  timeout?: string
}

interface NodeDetail {
  name: string
  kind: string
  refId?: string
  params?: Record<string, unknown> | string
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  /** 决策门开关（decisionGate.enabled） */
  gate?: boolean
  decisionGate?: GateConfig
  /** 重试策略（后端 RetryPolicy：retry: {maxAttempts, intervalMs} 对象） */
  retryMaxAttempts?: number
  retryIntervalMs?: number
  timeoutMs?: number
  /** AGENT 节点能力绑定（capabilityBinding，"skill:{id}@{v}" / "mcp:{id}@{v}"） */
  skills?: string[]
  mcps?: string[]
}

const NODE_DETAIL: Record<string, NodeDetail> = {
  start: { name: '开始', kind: 'START', inputSchema: { message: 'string' } },
  n1: { name: 'Agent 节点', kind: 'AGENT', refId: '', params: {}, retryMaxAttempts: 0, timeoutMs: 60000 },
  end: { name: '结束', kind: 'END', outputSchema: {} },
}

const PANEL: Array<{
  key: string
  header: string
  builtin?: boolean
  items: Array<{ name: string; icon?: React.ReactNode }>
}> = [
  {
    key: 'builtin',
    header: '平台内置',
    builtin: true,
    items: [
      { name: '开始节点', icon: <PlayCircleFilled style={{ color: '#52c41a', fontSize: 15 }} /> },
      { name: '结束节点', icon: <StopFilled style={{ color: '#ff4d4f', fontSize: 15 }} /> },
    ],
  },
  {
    key: 'agent',
    header: 'Agent 节点',
    items: [{ name: 'Agent 节点', icon: <RobotOutlined style={{ color: '#1677ff', fontSize: 14 }} /> }],
  },
  {
    key: 'conn',
    header: '组件节点',
    items: [],
  },
]

/* ---------- 组件面板：按 category 分组渲染（新工具/插件/连接器自动成组，前端零改动） ---------- */
const CATEGORY_LABEL: Record<string, string> = {
  basic: '基础节点',
  tool: '工具',
  llm: '模型',
  agent: 'Agent',
}
const catIcon = (cat?: string) => {
  switch (cat) {
    case 'tool':
      return <ToolOutlined style={{ color: '#fa8c16', fontSize: 13 }} />
    case 'basic':
      return <ApiOutlined style={{ color: '#1677ff', fontSize: 13 }} />
    default:
      return <ApiOutlined style={{ color: '#722ed1', fontSize: 13 }} />
  }
}
/** components 按 category 分组，保持后端返回顺序 */
function groupByCategory(comps: ComponentMeta[]) {
  const m = new Map<string, ComponentMeta[]>()
  for (const c of comps) {
    const k = c.category || 'other'
    if (!m.has(k)) m.set(k, [])
    m.get(k)!.push(c)
  }
  return [...m.entries()].map(([cat, list]) => ({ cat, list }))
}

/* ---------- canvasJson v1 ↔ LogicFlow graph 双向转换 ----------
   节点 x/y 坐标随 canvasJson 持久化（后端 CanvasJsonParser 按字段名读取、
   未知字段忽略且 canvas_json 原样存列——坐标往返不丢）。 */

/** 节点渲染样式：白底 + 按 kind 彩色描边（对齐原型干净风格，类型区分靠描边与文字色） */
function nodeVisual(kind: string) {
  const s = kindStyle(kind)
  return {
    style: { fill: '#fff', stroke: s.stroke, strokeWidth: 1.5, radius: 6 },
    textStyle: { color: s.stroke, fontSize: 12, fontWeight: 600 },
  }
}

function canvasToGraph(canvas: CanvasJson) {
  return {
    nodes: canvas.nodes.map((n, i) => ({
      id: n.id, type: 'rect',
      // 已存坐标用原值；旧数据（无坐标）按序瀑布布局
      x: Number((n as CanvasNode & { x?: number }).x) || 260,
      y: Number((n as CanvasNode & { y?: number }).y) || 80 + i * 120,
      text: n.name, kind: n.kind, ...nodeVisual(n.kind),
    })),
    edges: canvas.edges.map((e, i) => ({
      id: 'e' + i, sourceNodeId: e.from, targetNodeId: e.to, type: 'polyline',
    })),
  }
}

function canvasToDetails(canvas: CanvasJson): Record<string, NodeDetail> {
  const dt: Record<string, NodeDetail> = {}
  canvas.nodes.forEach((n) => {
    const raw = n as CanvasNode & {
      decisionGate?: GateConfig & { enabled?: boolean }
      retry?: { maxAttempts?: number; intervalMs?: number }
      capabilityBinding?: { skills?: string[]; mcps?: string[] }
    }
    dt[n.id] = {
      name: n.name, kind: n.kind, refId: n.refId, params: n.params || {},
      inputSchema: n.inputSchema || {}, outputSchema: n.outputSchema || {},
      gate: raw.decisionGate?.enabled === true,
      decisionGate: raw.decisionGate
        ? { interaction: raw.decisionGate.interaction, onReject: raw.decisionGate.onReject, timeout: raw.decisionGate.timeout }
        : undefined,
      retryMaxAttempts: raw.retry?.maxAttempts ?? 0,
      retryIntervalMs: raw.retry?.intervalMs ?? 0,
      timeoutMs: n.timeoutMs,
      skills: raw.capabilityBinding?.skills || [],
      mcps: raw.capabilityBinding?.mcps || [],
    }
  })
  return dt
}

interface GraphNodeData {
  id: string
  x?: number
  y?: number
  text?: string | { value: string }
}

function graphToCanvas(
  g: { nodes: GraphNodeData[]; edges: Array<{ sourceNodeId: string; targetNodeId: string }> },
  details: Record<string, NodeDetail>,
  name: string,
): CanvasJson {
  return {
    meta: { name, description: '' },
    triggers: [{ type: 'MANUAL' }],
    nodes: g.nodes.map((n) => {
      const d = details[n.id] || ({} as NodeDetail)
      const label = typeof n.text === 'object' && n.text ? n.text.value : n.text
      const node: CanvasNode & {
        x?: number; y?: number
        decisionGate?: GateConfig & { enabled: boolean }
        capabilityBinding?: { skills: string[]; mcps: string[] }
      } = {
        id: n.id, kind: (d.kind || 'ATOMIC') as CanvasNode['kind'],
        name: label || d.name || n.id, refId: '',
        x: Math.round(n.x || 0), y: Math.round(n.y || 0),
      }
      if (node.kind === 'START') node.inputSchema = d.inputSchema || {}
      else if (node.kind === 'END') node.outputSchema = d.outputSchema || {}
      else {
        node.refId = d.refId || ''
        node.params = (typeof d.params === 'object' ? d.params : {}) || {}
        node.retry = { maxAttempts: d.retryMaxAttempts ?? 0, intervalMs: d.retryIntervalMs ?? 0 }
        if (d.timeoutMs != null) node.timeoutMs = d.timeoutMs
        if (d.gate) {
          node.decisionGate = {
            enabled: true,
            interaction: d.decisionGate?.interaction || 'WORKSPACE',
            onReject: d.decisionGate?.onReject || 'REDO',
            timeout: d.decisionGate?.timeout,
          }
        }
        const skills = (d.skills || []).filter(Boolean)
        const mcps = (d.mcps || []).filter(Boolean)
        if (node.kind === 'AGENT' && (skills.length > 0 || mcps.length > 0)) {
          node.capabilityBinding = { skills, mcps }
        }
      }
      return node
    }),
    edges: g.edges.map((e) => ({ from: e.sourceNodeId, to: e.targetNodeId, type: 'SEQ', mapping: [] })),
  }
}

/* ---------- 画布容器 ---------- */
function Canvas({ graph, lfRef, onNodeClick, onNodeDelete, onGraphChange }: {
  graph: typeof GRAPH
  lfRef: React.MutableRefObject<LogicFlow | null>
  onNodeClick: (id: string) => void
  onNodeDelete: (id: string) => void
  onGraphChange: () => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const lfInner = useRef<LogicFlow | null>(null)

  useEffect(() => {
    if (!box.current) return
    const lf = new LogicFlow({
      container: box.current,
      grid: { size: 10, visible: true },
      keyboard: { enabled: true },
      edgeType: 'polyline',
      history: false,
    })
    lf.setTheme({
      rect: { fill: '#fff', stroke: '#1677ff', strokeWidth: 1.5, radius: 6 },
      polyline: { stroke: '#bfbfbf', strokeWidth: 1.5 },
      anchor: { stroke: '#1677ff', fill: '#fff', r: 4 },
      anchorLine: { stroke: '#1677ff', strokeWidth: 1, strokeDasharray: '4,4' },
    })
    lf.on('node:click', ({ data }: { data: { id?: string } }) => {
      if (data.id) onNodeClick(data.id)
    })
    // 键盘 Delete/Backspace 删除选中节点 → 同步清理属性状态
    lf.on('node:delete', ({ data }: { data: { id?: string } }) => {
      if (data.id) onNodeDelete(data.id)
    })
    lf.on('edge:delete', () => onGraphChange())
    lf.on('node:drop', () => onGraphChange())
    lfInner.current = lf
    lfRef.current = lf
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (lfInner.current && graph) lfInner.current.render(graph)
  }, [graph])

  return <div ref={box} className="xj-canvas-wrap" />
}

/* ---------- Schema 展示块 ---------- */
function SchemaBlock({ title, tag, tagColor, schema, note }: {
  title: string; tag?: string; tagColor?: string
  schema?: Record<string, unknown>; note?: string
}) {
  const entries = Object.entries(schema || {})
  return (
    <div style={{ border: '1px solid #f0f0f0', borderRadius: 6, padding: '8px 12px', background: '#fafafa' }}>
      <Space size={6}>
        <Typography.Text strong style={{ fontSize: 13 }}>{title}</Typography.Text>
        {tag && <Tag color={tagColor} style={{ marginInlineEnd: 0, fontSize: 11, lineHeight: '18px' }}>{tag}</Tag>}
      </Space>
      {entries.length === 0 ? (
        <div style={{ color: 'rgba(0,0,0,0.45)', fontSize: 12, marginTop: 4 }}>未声明</div>
      ) : (
        entries.map(([k, t]) => (
          <div key={k} style={{ fontSize: 12, marginTop: 4 }}>
            <code style={{ color: '#1677ff' }}>{k}</code>
            <span style={{ color: '#999', margin: '0 6px' }}>:</span>
            <code style={{ color: '#555' }}>{typeof t === 'string' ? t : JSON.stringify(t)}</code>
          </div>
        ))
      )}
      {note && <div style={{ color: '#999', fontSize: 12, marginTop: 6 }}>{note}</div>}
    </div>
  )
}

interface AgentOption { value: string; label: string }
type RefOption = AgentOption

/** 参数技术名 → 中文标签（已知字段友好化；未知字段回退技术名） */
const PARAM_LABELS: Record<string, string> = {
  message: '通知内容',
  requiredFields: '必填字段',
  title: '看板标题',
}

/**
 * 动态参数表单：按组件 paramSchema 渲染结构化字段，替代裸 JSON。
 * 支持 string / number / boolean / string[]；对象与对象数组标注「由上游提供」。
 */
function ParamFields({
  schema, params,
}: {
  schema?: ParamSchema | null
  params: Record<string, unknown>
}) {
  const props = schema?.properties
  if (!props || Object.keys(props).length === 0) {
    return (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        该组件无需配置参数
      </Typography.Text>
    )
  }
  const required = new Set(schema?.required || [])
  return (
    <Space direction="vertical" style={{ width: '100%' }} size={0}>
      {Object.entries(props).map(([key, field]) => {
        const initial = params[key]
        const label = PARAM_LABELS[key] || key
        const itemType = field.items?.type
        const fromUpstream = field.type === 'object' || (field.type === 'array' && itemType === 'object')
        return (
          <Form.Item
            key={key}
            label={label}
            name={key}
            initialValue={initial}
            rules={
              required.has(key)
                ? [{ required: true, message: `请填写${label}` }]
                : undefined
            }
            extra={
              fromUpstream
                ? '该数据通常由上游节点提供，无需在此填写'
                : field.description || undefined
            }
          >
            {fromUpstream ? (
              <Input disabled placeholder="由上游节点提供" />
            ) : field.type === 'number' || field.type === 'integer' ? (
              <InputNumber style={{ width: '100%' }} placeholder={`请输入${label}`} />
            ) : field.type === 'boolean' ? (
              <Switch />
            ) : field.type === 'array' ? (
              <Select mode="tags" placeholder="输入后回车添加" style={{ width: '100%' }} />
            ) : (
              <Input placeholder={`请输入${label}`} />
            )}
          </Form.Item>
        )
      })}
    </Space>
  )
}

/** 已注册组件元数据（GET /api/components，后端 NodeMetadata 视图） */
interface ComponentMeta {
  nodeId: string
  name: string
  kind: string
  category: string
  description: string
  paramSchema?: ParamSchema | null
}

/** 节点参数 JSON Schema（仅取动态表单需要的字段） */
interface ParamSchema {
  type?: string
  properties?: Record<string, ParamField>
  required?: string[]
}
interface ParamField {
  type?: string
  description?: string
  items?: ParamField
}

/** 决策门区块（S2 已实装：执行到该节点挂起等人决策，决策后从该节点恢复） */
function GateSection() {
  return (
    <>
      <Divider style={{ margin: '16px 0 12px' }} />
      <Typography.Text strong style={{ fontSize: 13 }}>决策门</Typography.Text>
      <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', margin: '2px 0 8px' }}>
        执行到该节点时暂停，等人在审批中心或工作区里确认后再继续
      </div>
      <Form.Item name="gate" valuePropName="checked" style={{ marginBottom: 12 }}>
        <Checkbox>启用决策门</Checkbox>
      </Form.Item>
      <Form.Item noStyle shouldUpdate={(prev, cur) => prev.gate !== cur.gate}>
        {({ getFieldValue }) =>
          getFieldValue('gate') ? (
            <div style={{ background: '#fafafa', border: '1px solid #f0f0f0', borderRadius: 6, padding: '10px 12px' }}>
              <Form.Item
                label="审批方式"
                name={['decisionGate', 'interaction']}
                initialValue="WORKSPACE"
                style={{ marginBottom: 8 }}
              >
                <Select
                  options={[
                    { value: 'WORKSPACE', label: '产物审阅（查看产出 + 批注 + 修改）' },
                    { value: 'FORM', label: '简单确认（通过 / 驳回）' },
                  ]}
                />
              </Form.Item>
              <Form.Item
                label="驳回后"
                name={['decisionGate', 'onReject']}
                initialValue="REDO"
                style={{ marginBottom: 8 }}
              >
                <Select
                  options={[
                    { value: 'REDO', label: '重新生成 — 按批注意见重做后再审' },
                    { value: 'TERMINATE', label: '终止流程 — 本次执行按失败结束' },
                    { value: 'ROLLBACK', label: '回退重跑 — 从指定节点重新执行' },
                  ]}
                />
              </Form.Item>
              <Form.Item label="审批超时" name={['decisionGate', 'timeout']} extra="超时后的自动处理（升级 / 通过 / 驳回）将在调度实装后生效">
                <Input placeholder="如 24h" allowClear />
              </Form.Item>
            </div>
          ) : null
        }
      </Form.Item>
    </>
  )
}

/** 属性抽屉 */
function PropDrawer({ open, nodeId, onClose, detail, onSaveDetail, onDeleteNode, components, agents, skills, mcps }: {
  open: boolean
  nodeId: string | null
  onClose: () => void
  detail?: NodeDetail
  onSaveDetail: (nodeId: string, nd: NodeDetail) => void
  onDeleteNode: (nodeId: string) => void
  components: ComponentMeta[]
  agents: RefOption[]
  skills: RefOption[]
  mcps: RefOption[]
}) {
  const d: NodeDetail = detail || {
    name: '新节点', kind: 'AGENT', refId: '', params: {}, retryMaxAttempts: 0, timeoutMs: 60000,
    inputSchema: {}, outputSchema: {}, skills: [], mcps: [],
  }
  const isStart = d.kind === 'START'
  const isEnd = d.kind === 'END'
  const isBuiltin = isStart || isEnd
  const isAgent = d.kind === 'AGENT'
  const [form] = Form.useForm()

  const saveDetail = () => {
    form
      .validateFields()
      .then((vals: Record<string, unknown>) => {
        const nd = { ...d, ...vals } as NodeDetail
        // 结构化参数：按所选组件 paramSchema 收集字段组装为 params
        if (isAgent) {
          nd.params = {}
        } else {
          const comp = components.find((c) => c.nodeId === vals.refId)
          const keys = Object.keys(comp?.paramSchema?.properties || {})
          const params: Record<string, unknown> = {}
          for (const k of keys) {
            const v = vals[k]
            if (v !== undefined && v !== null && v !== '') params[k] = v
            delete (nd as unknown as Record<string, unknown>)[k]
          }
          nd.params = params
        }
        // 决策门关闭时清空配置
        if (!nd.gate) nd.decisionGate = undefined
        if (nodeId) onSaveDetail(nodeId, nd)
        message.success('节点属性已保存')
        onClose()
      })
      .catch(() => undefined)
  }

  const removeNode = () => {
    if (!nodeId) return
    Modal.confirm({
      title: `删除节点「${d.name || nodeId}」？`,
      content: '将同时删除与该节点相连的连线；平台内置节点不可删除。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => {
        onDeleteNode(nodeId)
        onClose()
      },
    })
  }

  return (
    <Drawer
      title={
        <Space size={8}>
          <span>{d.name || '未命名节点'}</span>
          <Tag color={kindStyle(d.kind).stroke} style={{ marginInlineEnd: 0 }}>{kindStyle(d.kind).label}</Tag>
          {isBuiltin && <Tag color="orange" style={{ marginInlineEnd: 0 }}>内置</Tag>}
        </Space>
      }
      width={440}
      open={open}
      onClose={onClose}
      footer={
        !isBuiltin ? (
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Popconfirm
              title="删除该节点？"
              description="将同时删除与它相连的连线。"
              okText="删除"
              okButtonProps={{ danger: true }}
              cancelText="取消"
              onConfirm={removeNode}
            >
              <Button danger type="text" size="small">删除节点</Button>
            </Popconfirm>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>ID：{nodeId}</Typography.Text>
          </div>
        ) : (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>ID：{nodeId} · 平台内置节点不可删除</Typography.Text>
        )
      }
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" onClick={saveDetail}>保存</Button>
        </Space>
      }
    >
      {isBuiltin ? (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <Alert type="warning" showIcon message="平台内置节点：画布固定存在、不可删除，也无法由插件提供。" />
          <SchemaBlock
            title={isStart ? '流程输入' : '流程输出'}
            tag={isStart ? '来自工作区输入' : '返回给调用方'}
            tagColor={isStart ? 'green' : 'red'}
            schema={isStart ? d.inputSchema : d.outputSchema}
            note={isStart ? '工作区里输入的内容会作为流程的起点数据' : '流程结束时返回给调用方（工作区对话 / 开放 API）'}
          />
        </Space>
      ) : (
        <Form
          layout="vertical"
          form={form}
          initialValues={{
            ...d,
            decisionGate: d.decisionGate || {},
          }}
          key={nodeId}
        >
          <Form.Item label="节点名称" name="name" rules={[{ required: true, message: '请输入节点名称' }]}>
            <Input placeholder="如：技术调研" />
          </Form.Item>
          <Form.Item
            label={isAgent ? '引用 Agent' : '引用组件'}
            name="refId"
            required
            extra={isAgent ? '运行时由所选 Agent 调用模型执行' : '发布时会校验组件存在且已启用'}
          >
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder={isAgent ? '选择已发布的 Agent' : '选择已注册的组件'}
              options={
                isAgent
                  ? agents
                  : components.map((c) => ({ value: c.nodeId, label: c.name }))
              }
            />
          </Form.Item>
          {isAgent ? (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 4 }}
              message="Agent 的模型与参数在「Agent 管理」中配置，此处无需设置。"
            />
          ) : (
            <>
              <Typography.Text strong style={{ fontSize: 13 }}>节点参数</Typography.Text>
              <div style={{ marginTop: 8 }}>
                <Form.Item noStyle shouldUpdate={(prev, cur) => prev.refId !== cur.refId}>
                  {({ getFieldValue }) => {
                    const refId = getFieldValue('refId') as string | undefined
                    const comp = components.find((c) => c.nodeId === refId)
                    return (
                      <ParamFields
                        key={refId || 'none'}
                        schema={comp?.paramSchema}
                        params={typeof d.params === 'object' && d.params !== null ? d.params : {}}
                      />
                    )
                  }}
                </Form.Item>
              </div>
            </>
          )}
          <Divider style={{ margin: '8px 0 12px' }} />
          <Typography.Text strong style={{ fontSize: 13 }}>执行策略</Typography.Text>
          <Space size="large" style={{ marginTop: 8 }}>
            <Form.Item label="失败重试（次）" name="retryMaxAttempts">
              <InputNumber min={0} max={5} placeholder="0" />
            </Form.Item>
            <Form.Item label="重试间隔（毫秒）" name="retryIntervalMs">
              <InputNumber min={0} step={1000} placeholder="0" />
            </Form.Item>
            <Form.Item label="超时时间（毫秒）" name="timeoutMs">
              <InputNumber step={30000} placeholder="60000" />
            </Form.Item>
          </Space>
          <GateSection />
          {isAgent && (
            <>
              <Divider style={{ margin: '16px 0 12px' }} />
              <Typography.Text strong style={{ fontSize: 13 }}>能力绑定（技能 / MCP）</Typography.Text>
              <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', margin: '2px 0 8px' }}>
                此处勾选将覆盖 Agent 自身的默认绑定：相同项以节点配置为准，其余保留
              </div>
              <Form.Item label="技能（Skills）" name="skills">
                <Checkbox.Group
                  options={skills.map((s) => ({ value: s.value, label: s.label }))}
                  style={{ display: 'flex', flexDirection: 'column', gap: 4 }}
                />
              </Form.Item>
              <Form.Item label="MCP 服务" name="mcps">
                <Checkbox.Group
                  options={mcps.map((s) => ({ value: s.value, label: s.label }))}
                  style={{ display: 'flex', flexDirection: 'column', gap: 4 }}
                />
              </Form.Item>
            </>
          )}
          <Divider style={{ margin: '16px 0 12px' }} />
          <Typography.Text strong style={{ fontSize: 13 }}>输入 / 输出</Typography.Text>
          <Space direction="vertical" style={{ width: '100%' }} size={8}>
            <SchemaBlock
              title="输入"
              tag="由上游节点提供"
              tagColor="blue"
              schema={d.inputSchema}
              note="连线后自动从上游节点的输出引入"
            />
            <SchemaBlock
              title="输出"
              tag="供下游节点使用"
              tagColor="blue"
              schema={d.outputSchema}
              note="下游节点按字段引用这些输出"
            />
          </Space>
        </Form>
      )}
    </Drawer>
  )
}

/* ---------- 主页面 ---------- */
export default function CanvasEditor() {
  const lfRef = useRef<LogicFlow | null>(null)
  const [drawer, setDrawer] = useState<{ open: boolean; nodeId: string | null }>({ open: false, nodeId: null })
  const [graph, setGraph] = useState(GRAPH)
  const [details, setDetails] = useState<Record<string, NodeDetail>>(NODE_DETAIL)
  const [defs, setDefs] = useState<DefinitionSummary[]>([])
  const [current, setCurrent] = useState<DefinitionSummary | null>(null)
  const [components, setComponents] = useState<ComponentMeta[]>([])
  const [agents, setAgents] = useState<RefOption[]>([])
  const [skills, setSkills] = useState<RefOption[]>([])
  const [mcps, setMcps] = useState<RefOption[]>([])
  const [pubOpen, setPubOpen] = useState(false)
  const [pubResult, setPubResult] = useState<{ version: number; ruleContent: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [stat, setStat] = useState({ nodes: 3, edges: 2 })
  const [nameDraft, setNameDraft] = useState('')

  const loadDefs = useCallback(() => {
    apiJson<DefinitionSummary[]>('/api/definitions')
      .then(setDefs)
      .catch((e: Error) => message.error(e.message))
  }, [])

  useEffect(() => {
    loadDefs()
    apiJson<ComponentMeta[]>('/api/components')
      .then((d) => setComponents(d || []))
      .catch(() => undefined)
    // 已发布 Agent 列表（AGENT 节点 refId 下拉）
    apiJson<AssetRow[]>('/api/agents')
      .then((list) =>
        setAgents(
          (list || [])
            .filter((a) => a.status === 'PUBLISHED')
            .map((a) => ({ value: `agent:${a.id}@${a.version ?? 1}`, label: `${a.name} (#${a.id}@v${a.version ?? 1})` })),
        ),
      )
      .catch(() => undefined)
    // 已发布 Skill / MCP（AGENT 节点能力绑定选项）
    apiJson<AssetRow[]>('/api/skills')
      .then((list) =>
        setSkills(
          (list || [])
            .filter((a) => a.status === 'PUBLISHED')
            .map((a) => ({ value: `skill:${a.id}@${a.version ?? 1}`, label: `${a.name} (#${a.id}@v${a.version ?? 1})` })),
        ),
      )
      .catch(() => undefined)
    apiJson<AssetRow[]>('/api/mcp')
      .then((list) =>
        setMcps(
          (list || [])
            .filter((a) => a.status === 'PUBLISHED')
            .map((a) => ({ value: `mcp:${a.id}@${a.version ?? 1}`, label: `${a.name} (#${a.id}@v${a.version ?? 1})` })),
        ),
      )
      .catch(() => undefined)
  }, [loadDefs])

  const refreshStat = useCallback(() => {
    const g = lfRef.current?.getGraphData() as { nodes?: unknown[]; edges?: unknown[] } | undefined
    if (g) setStat({ nodes: g.nodes?.length || 0, edges: g.edges?.length || 0 })
  }, [])

  const openDef = (id: number) => {
    apiJson<DefinitionDetail0>('/api/definitions/' + id)
      .then((d) => {
        const raw = d.canvas_json ?? d.canvasJson
        if (raw === undefined) throw new Error('定义缺少 canvasJson')
        const canvas: CanvasJson = typeof raw === 'string' ? (JSON.parse(raw) as CanvasJson) : raw
        setCurrent({ id: d.id, name: d.name, version: d.version ?? d.current_version, status: d.status })
        setNameDraft(d.name || '')
        setGraph(canvasToGraph(canvas))
        setDetails(canvasToDetails(canvas))
        setPubResult(null)
      })
      .catch((e: Error) => message.error('加载定义失败：' + e.message))
  }

  const newDef = () => {
    setCurrent(null)
    setNameDraft('')
    setGraph(GRAPH)
    setDetails(NODE_DETAIL)
    setPubResult(null)
  }

  /** 未配置 refId 的非内置节点（保存提醒 / 发布拦截） */
  const missingRefNodes = () =>
    Object.entries(details)
      .filter(([, d]) => d.kind !== 'START' && d.kind !== 'END' && !d.refId)
      .map(([id, d]) => `${d.name || id}(${id})`)

  const saveDraft = () => {
    const lf = lfRef.current
    if (!lf) return
    const missing = missingRefNodes()
    const doSave = () => {
      const name = (nameDraft || '').trim() || (current ? current.name : '未命名流程')
      const canvas = graphToCanvas(lf.getGraphData() as never, details, name)
      const url = current ? '/api/definitions/' + current.id : '/api/definitions'
      setSaving(true)
      apiJson<{ id: number }>(url, current ? 'PUT' : 'POST', {
        name, description: canvas.meta.description, canvasJson: JSON.stringify(canvas),
      })
        .then((d) => {
          message.success('草稿已保存' + (current ? '' : '（id=' + d.id + '）'))
          if (!current) setCurrent({ id: d.id, name, version: 1, status: 'DRAFT' })
          setNameDraft(name)
          loadDefs()
        })
        .catch((e: Error) => message.error(e.message))
        .finally(() => setSaving(false))
    }
    if (missing.length > 0) {
      Modal.confirm({
        title: `${missing.length} 个节点未配置引用（refId）`,
        content: `未配置：${missing.join('、')}。草稿允许暂缺，发布前必须补齐。`,
        okText: '仍要保存草稿',
        cancelText: '返回配置',
        onOk: doSave,
      })
      return
    }
    doSave()
  }

  const doPublish = () => {
    if (!current) {
      message.warning('请先保存草稿')
      return
    }
    const missing = missingRefNodes()
    if (missing.length > 0) {
      message.error(`发布校验未通过：${missing.length} 个节点未配置引用（refId）——${missing.join('、')}`)
      return
    }
    apiJson<{ version: number; ruleContent: string }>('/api/definitions/' + current.id + '/publish', 'POST')
      .then((d) => {
        setPubResult(d)
        message.success('已发布 v' + (d.version || 1))
        loadDefs()
      })
      .catch((e: Error) => message.error(e.message))
  }

  const saveDetail = (nodeId: string, nd: NodeDetail) =>
    setDetails((prev) => ({ ...prev, [nodeId]: nd }))

  const addNode = (name: string, kind: 'AGENT' | 'ATOMIC', refId?: string) => {
    if (!lfRef.current) return
    const id = 'n' + (Date.now() % 100000)
    // 新节点放在现有节点包围盒右侧，避免随机位置互相遮挡
    const g = lfRef.current.getGraphData() as { nodes?: Array<{ x?: number; y?: number }> }
    const xs = (g.nodes || []).map((n) => n.x || 0)
    const ys = (g.nodes || []).map((n) => n.y || 0)
    const x = (xs.length ? Math.max(...xs) : 260) + 180
    const y = ys.length ? Math.min(...ys) + 60 : 160
    lfRef.current.addNode({ type: 'rect', id, x, y, text: name, ...nodeVisual(kind) })
    setDetails((prev) => ({
      ...prev,
      [id]: { name, kind, refId: refId || '', params: {}, retryMaxAttempts: 0, inputSchema: {}, outputSchema: {}, skills: [], mcps: [] },
    }))
    refreshStat()
    message.info(`已添加节点「${name}」，点击节点配置属性；从节点锚点拖拽可连线`)
  }

  const deleteNode = (nodeId: string) => {
    const d = details[nodeId]
    if (d && (d.kind === 'START' || d.kind === 'END')) {
      message.warning('平台内置节点（开始/结束）不可删除')
      return
    }
    lfRef.current?.deleteElement(nodeId)
    setDetails((prev) => {
      const next = { ...prev }
      delete next[nodeId]
      return next
    })
    refreshStat()
    message.success('节点已删除')
  }

  const zoom = (inward: boolean) => {
    try { lfRef.current?.zoom(inward) } catch { /* 画布未就绪时忽略 */ }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {/* 顶部工具条：标题左 / 操作右（对齐原型 xj-toolbar 两段式） */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 12, alignItems: 'center' }}>
        <Input
          value={nameDraft}
          onChange={(e) => setNameDraft(e.target.value)}
          placeholder="未命名流程"
          style={{ width: 220, fontWeight: 600 }}
          suffix={
            current ? (
              <Tag style={{ marginInlineEnd: 0 }} color={current.status === 'PUBLISHED' ? 'success' : 'default'}>
                v{current.version || 1} · {current.status || 'DRAFT'}
              </Tag>
            ) : (
              <Tag style={{ marginInlineEnd: 0 }}>新草稿</Tag>
            )
          }
        />
        <span style={{ flex: 1 }} />
        <Select
          placeholder="打开流程定义"
          style={{ width: 230 }}
          value={current ? current.id : undefined}
          onChange={openDef}
          options={defs.map((d) => ({ value: d.id, label: `#${d.id} ${d.name} · ${d.status || 'DRAFT'}` }))}
        />
        <Button icon={<PlusOutlined />} onClick={newDef}>新建</Button>
        <Button icon={<SaveOutlined />} loading={saving} onClick={saveDraft}>保存草稿</Button>
        <Button type="primary" icon={<SendOutlined />} onClick={() => setPubOpen(true)}>发布</Button>
      </div>
      <div style={{ display: 'flex', gap: 12, flex: 1, minHeight: 0 }}>
        {/* 左：节点面板 */}
        <div style={{ width: 230, flexShrink: 0, overflowY: 'auto' }}>
          <Collapse
            defaultActiveKey={['builtin', 'agent', 'conn']}
            size="small"
            items={PANEL.map((g) => {
              const connGroups = g.key === 'conn' ? groupByCategory(components) : []
              const items = g.items
              return {
              key: g.key,
              label: g.builtin ? (
                <Space size={6}>
                  {g.header}
                  <Tag color="orange" style={{ marginInlineEnd: 0, fontSize: 11, lineHeight: '18px' }}>内置 · 不可删除</Tag>
                </Space>
              ) : (
                g.header
              ),
              children: g.key === 'conn' ? (
                connGroups.length === 0 ? (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>暂无已注册组件</Typography.Text>
                ) : connGroups.map(({ cat, list }) => (
                  <div key={cat}>
                    <div style={{ fontSize: 11, color: 'rgba(0,0,0,0.45)', fontWeight: 600, margin: '8px 0 4px' }}>
                      {CATEGORY_LABEL[cat] || cat}
                    </div>
                    {list.map((c) => (
                      <div
                        key={c.nodeId}
                        className="xj-panel-item"
                        onClick={() => addNode(c.name, 'ATOMIC', c.nodeId)}
                      >
                        {catIcon(c.category)}
                        <span>{c.name}</span>
                      </div>
                    ))}
                  </div>
                ))
              ) : items.length === 0 ? (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>暂无已注册组件</Typography.Text>
              ) : items.map((it) => (
                <div
                  key={it.name}
                  className="xj-panel-item"
                  onClick={() =>
                    g.builtin
                      ? message.info('「' + it.name + '」为平台内置节点，画布固定存在，无需手动添加')
                      : addNode(it.name, g.key === 'agent' ? 'AGENT' : 'ATOMIC', (it as { refId?: string }).refId)
                  }
                >
                  {it.icon || <PlusCircleOutlined style={{ color: 'rgba(0,0,0,0.45)', fontSize: 13 }} />}
                  <span>{it.name}</span>
                </div>
              )),
              }
            })}
          />
        </div>
        {/* 右：画布工具条 + 画布 */}
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          <div style={{ display: 'flex', gap: 4, alignItems: 'center', marginBottom: 8 }}>
            <Tooltip title="放大"><Button size="small" icon={<ZoomInOutlined />} onClick={() => zoom(true)} /></Tooltip>
            <Tooltip title="缩小"><Button size="small" icon={<ZoomOutOutlined />} onClick={() => zoom(false)} /></Tooltip>
            <Tooltip title="适应画布">
              <Button size="small" icon={<CompressOutlined />} onClick={() => { try { lfRef.current?.fitView() } catch { /* 忽略 */ } }} />
            </Tooltip>
            <Tooltip title="重置缩放">
              <Button size="small" icon={<OneToOneOutlined />} onClick={() => { try { lfRef.current?.resetZoom() } catch { /* 忽略 */ } }} />
            </Tooltip>
            <span style={{ flex: 1 }} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {stat.nodes} 节点 · {stat.edges} 连线
            </Typography.Text>
          </div>
          <Canvas
            graph={graph}
            lfRef={lfRef}
            onNodeClick={(id) => setDrawer({ open: true, nodeId: id })}
            onNodeDelete={deleteNode}
            onGraphChange={refreshStat}
          />
          <div className="xj-canvas-hint">
            点击节点配置属性 · 拖拽节点调整位置 · 从节点锚点拖出连线 · 选中节点 / 连线后按 Delete 删除 · 双击节点可改名 · 拖拽空白平移画布
          </div>
        </div>
      </div>
      <PropDrawer
        open={drawer.open}
        nodeId={drawer.nodeId}
        detail={drawer.nodeId ? details[drawer.nodeId] : undefined}
        components={components}
        agents={agents}
        skills={skills}
        mcps={mcps}
        onSaveDetail={saveDetail}
        onDeleteNode={deleteNode}
        onClose={() => setDrawer({ open: false, nodeId: null })}
      />
      <Modal
        title={'发布 ' + (current ? '《' + current.name + '》' : '')}
        open={pubOpen}
        onCancel={() => setPubOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setPubOpen(false)}>关闭</Button>
            <Button type="primary" onClick={doPublish}>确认发布</Button>
          </Space>
        }
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="发布后生成新版本：进行中的执行按原版本继续，不受影响"
        />
        {pubResult ? (
          <div>
            <p style={{ fontSize: 13 }}>✅ 已发布 v{pubResult.version}，编译产物（LiteFlow EL）：</p>
            <pre style={{ background: '#fafafa', padding: 12, borderRadius: 6, fontSize: 12 }}>{pubResult.ruleContent}</pre>
          </div>
        ) : (
          <p style={{ fontSize: 13, color: '#999' }}>点击「确认发布」执行校验与编译（引用校验 / 图校验 / EL 生成）。</p>
        )}
      </Modal>
    </div>
  )
}

interface DefinitionDetail0 extends DefinitionSummary {
  canvas_json?: string | CanvasJson
  canvasJson?: string | CanvasJson
  current_version?: number
}

