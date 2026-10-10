import { useCallback, useEffect, useState } from 'react'
import {
  Tabs, Table, Tag, Button, Space, Modal, Form, Input, Select, message, Popconfirm, Alert,
  Checkbox, Col, Row, Tooltip, Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiFetch, apiJson } from '../api/client'
import type { AssetRow } from '../types/api'

const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '已发布', DEPRECATED: '已弃用', OFFLINE: '已下线',
}
const statusTag = (v?: string) => (
  <Tag color={v === 'PUBLISHED' ? 'green' : v === 'DEPRECATED' || v === 'OFFLINE' ? 'red' : 'default'}>
    {STATUS_TEXT[v || ''] || v}
  </Tag>
)
const verText = (v?: number) => 'v' + (v || 1)

type EditorFormProps = {
  initialValues: Partial<AssetRow>
  onSave: (values: Record<string, unknown>) => void
  onCancel: () => void
}

interface ColDef {
  title: string
  dataIndex?: string
  className?: string
  ellipsis?: boolean
  render?: (v: unknown, r: AssetRow) => React.ReactNode
}

/* ---------- 通用：资产页签骨架（list/create/update/delete/publish 全 API 驱动） ---------- */
function AssetTab({ endpoint, columns, EditorForm, rowKey }: {
  endpoint: string
  columns: ColDef[]
  EditorForm: (props: EditorFormProps) => React.ReactElement
  rowKey?: string
}) {
  const [rows, setRows] = useState<AssetRow[] | null>(null)
  const [editing, setEditing] = useState<Partial<AssetRow> | null>(null)
  const [pubRow, setPubRow] = useState<AssetRow | null>(null)

  const load = useCallback(() => {
    apiJson<AssetRow[]>('/api/' + endpoint)
      .then(setRows)
      .catch((e: Error) => message.error(e.message))
  }, [endpoint])
  useEffect(() => {
    load()
  }, [load])

  const save = (values: Record<string, unknown>) => {
    const isNew = !editing?.id
    const url = isNew ? '/api/' + endpoint : '/api/' + endpoint + '/' + editing?.id
    apiJson<AssetRow>(url, isNew ? 'POST' : 'PUT', values)
      .then(() => {
        message.success(isNew ? '已创建（草稿）' : '已保存')
        setEditing(null)
        load()
      })
      .catch((e: Error) => message.error(e.message))
  }

  const del = (rec: AssetRow) => {
    apiFetch('/api/' + endpoint + '/' + rec.id, { method: 'DELETE' })
      .then(async (r) => ({ ok: r.ok, d: await r.json().catch(() => ({})) }))
      .then(({ ok, d }) => {
        if (!ok) {
          const dd = d as { error?: string; status?: number }
          throw new Error(dd.error || (dd.status === 400 ? '已发布过的资产不可删除' : '删除失败'))
        }
        message.success('已删除')
        load()
      })
      .catch((e: Error) => message.error(e.message))
  }

  const publish = () => {
    if (!pubRow) return
    apiJson<{ version: number }>('/api/' + endpoint + '/' + pubRow.id + '/publish', 'POST')
      .then((d) => {
        message.success('已发布 ' + verText(d.version) + '（新版本已生成，引用不受影响）')
        setPubRow(null)
        load()
      })
      .catch((e: Error) => message.error(e.message))
  }

  const toCol = (c: ColDef): ColumnsType<AssetRow>[number] => ({
    title: c.title,
    dataIndex: c.dataIndex,
    className: c.className,
    ellipsis: c.ellipsis,
    render: c.render ? (v, r) => c.render!(v, r) : undefined,
  })

  const ops: ColumnsType<AssetRow>[number] = {
    title: '操作',
    width: 190,
    render: (_, r) => (
      <Space>
        <a onClick={() => setEditing(r)}>编辑</a>
        <a onClick={() => setPubRow(r)}>
          {r.status === 'PUBLISHED' ? '发布新版本' : r.status === 'OFFLINE' || r.status === 'DEPRECATED' ? '重新发布' : '发布'}
        </a>
        {r.status === 'DRAFT' ? (
          <Popconfirm title="确定删除？" onConfirm={() => del(r)}>
            <a style={{ color: '#ff4d4f' }}>删除</a>
          </Popconfirm>
        ) : (
          <Tooltip title="已发布过的资产不可删除">
            <Typography.Text style={{ color: 'rgba(0,0,0,.25)' }}>删除</Typography.Text>
          </Tooltip>
        )}
      </Space>
    ),
  }

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        <Button type="primary" onClick={() => setEditing({})}>新建</Button>
        <span style={{ flex: 1 }} />
        <Button onClick={load}>刷新</Button>
      </div>
      <Table
        rowKey={rowKey || 'id'}
        size="middle"
        loading={!rows}
        dataSource={rows || []}
        columns={[...columns.map(toCol), ops]}
      />
      <Modal
        title={editing?.id ? '编辑' : '新建'}
        open={!!editing}
        onCancel={() => setEditing(null)}
        footer={null}
        width={640}
        destroyOnClose
      >
        {editing && <EditorForm initialValues={editing} onCancel={() => setEditing(null)} onSave={save} />}
      </Modal>
      <Modal
        title={'发布 ' + (pubRow ? pubRow.name || '#' + pubRow.id : '')}
        open={!!pubRow}
        onCancel={() => setPubRow(null)}
        onOk={publish}
        okText="确认发布"
      >
        <Alert
          type="warning"
          showIcon
          message="发布后生成新版本：已引用本资产的工作流继续使用旧版本，升级引用需手动操作。"
        />
      </Modal>
    </div>
  )
}

/* ---------- 工具页签（HTTP 接口调用：方法 + 地址 + 入参定义，提交组装为标准 JSON Schema） ---------- */
const PARAM_TYPES = ['string', 'number', 'integer', 'boolean', 'object', 'array']

interface ParamRow {
  name?: string
  type?: string
  desc?: string
  required?: boolean
}

/** 已存的 JSON Schema → 可编辑参数行（编辑时能回显，不必重填） */
const schemaToRows = (schema: unknown): ParamRow[] => {
  if (!schema || typeof schema !== 'object') return []
  const s = schema as {
    type?: string
    properties?: Record<string, { type?: string; description?: string }>
    required?: string[]
  } & Record<string, unknown>
  if (s.properties && typeof s.properties === 'object') {
    const req = Array.isArray(s.required) ? s.required : []
    return Object.entries(s.properties).map(([name, p]) => ({
      name,
      type: p?.type || 'string',
      desc: p?.description || '',
      required: req.includes(name),
    }))
  }
  // 兼容历史简化形态 {"project":"string"}（旧版表单教的写法）：顶层键当参数名，避免编辑时丢参数
  return Object.entries(s)
    .filter(([k]) => !['type', 'required', 'additionalProperties'].includes(k))
    .map(([k, v]) => ({
      name: k,
      type: typeof v === 'string' && PARAM_TYPES.includes(v) ? v : 'string',
      desc: '',
      required: false,
    }))
}

function ToolForm({ initialValues, onSave, onCancel }: EditorFormProps) {
  const [form] = Form.useForm()
  // source_ref 落库形态为「METHOD URL」，界面拆成方法 + 地址两格
  const raw = typeof initialValues.source_ref === 'string' ? initialValues.source_ref.trim() : ''
  const head = (raw.split(/\s+/)[0] || '').toUpperCase()
  const method = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(head) ? head : 'POST'
  const url = head === method ? raw.slice(head.length).trim() : raw
  return (
    <Form
      form={form}
      layout="vertical"
      initialValues={{
        name: initialValues.name,
        description: initialValues.description,
        method,
        url,
        params: schemaToRows((initialValues as Record<string, unknown>).input_schema),
      }}
      onFinish={(v) => {
        const rows = (v.params || []) as ParamRow[]
        const props: Record<string, unknown> = {}
        const required: string[] = []
        rows.forEach((p) => {
          if (!p?.name) return
          props[p.name] = p.desc
            ? { type: p.type || 'string', description: p.desc }
            : { type: p.type || 'string' }
          if (p.required) required.push(p.name)
        })
        onSave({
          name: v.name,
          description: v.description,
          source_type: 'OPENAPI',
          source_ref: `${v.method} ${v.url}`.trim(),
          input_schema: Object.keys(props).length
            ? { type: 'object', properties: props, required }
            : null,
        })
      }}
    >
      <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入工具名称' }]}>
        <Input placeholder="如 创建工单" />
      </Form.Item>
      <Form.Item label="说明" name="description" extra="Agent 依据说明决定何时调用该工具">
        <Input.TextArea rows={2} placeholder="在跟踪系统中创建一个工单" />
      </Form.Item>
      <Form.Item label="接口地址" required style={{ marginBottom: 0 }}>
        <Row gutter={8}>
          <Col span={7}>
            <Form.Item name="method" noStyle>
              <Select options={['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => ({ value: m, label: m }))} />
            </Form.Item>
          </Col>
          <Col span={17}>
            <Form.Item name="url" noStyle rules={[{ required: true, message: '请输入接口地址' }]}>
              <Input placeholder="https://example.com/api/issues" />
            </Form.Item>
          </Col>
        </Row>
      </Form.Item>
      <Form.Item
        label="入参定义"
        style={{ marginTop: 16 }}
        extra="Agent 按此定义生成调用参数；参数由上游节点传入时无需在此声明"
      >
        <Form.List name="params">
          {(fields, { add, remove }) => (
            <>
              {fields.length > 0 && (
                <Row gutter={6} style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>
                  <Col span={7}>参数名</Col>
                  <Col span={6}>类型</Col>
                  <Col span={8}>说明</Col>
                  <Col span={2}>必填</Col>
                  <Col span={1}> </Col>
                </Row>
              )}
              {fields.map((f) => (
                <Row key={f.key} gutter={6} align="middle" style={{ marginBottom: 6 }}>
                  <Col span={7}>
                    <Form.Item name={[f.name, 'name']} noStyle>
                      <Input placeholder="project" />
                    </Form.Item>
                  </Col>
                  <Col span={6}>
                    <Form.Item name={[f.name, 'type']} noStyle>
                      <Select options={PARAM_TYPES.map((t) => ({ value: t, label: t }))} />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name={[f.name, 'desc']} noStyle>
                      <Input placeholder="项目名称" />
                    </Form.Item>
                  </Col>
                  <Col span={2}>
                    <Form.Item name={[f.name, 'required']} noStyle valuePropName="checked">
                      <Checkbox />
                    </Form.Item>
                  </Col>
                  <Col span={1}>
                    <Button type="text" size="small" danger onClick={() => remove(f.name)}>✕</Button>
                  </Col>
                </Row>
              ))}
              <Button size="small" onClick={() => add({ type: 'string' })}>+ 添加参数</Button>
            </>
          )}
        </Form.List>
      </Form.Item>
      <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button onClick={onCancel}>取消</Button>
        <Button type="primary" htmlType="submit">保存</Button>
      </Space>
    </Form>
  )
}

const toolCols: ColDef[] = [
  { title: '名称', dataIndex: 'name' },
  { title: '说明', dataIndex: 'description', ellipsis: true },
  { title: '接口', dataIndex: 'source_ref', ellipsis: true, className: 'xj-mono' },
  { title: '版本', dataIndex: 'version', render: (v) => verText(v as number), className: 'xj-mono' },
  { title: '状态', dataIndex: 'status', render: (v) => statusTag(v as string) },
]

/* ---------- 技能页签 ---------- */
function SkillForm({ initialValues, onSave, onCancel }: EditorFormProps) {
  return (
    <Form layout="vertical" initialValues={initialValues} onFinish={(v) => onSave(v as Record<string, unknown>)}>
      <Form.Item label="名称" name="name" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item label="描述" name="description">
        <Input.TextArea rows={2} />
      </Form.Item>
      <Form.Item label="技能内容" name="content_md" extra="技能的操作步骤与规范，Agent 执行时作为指引提供给它">
        <Input.TextArea rows={10} placeholder={'# 技能 SOP\n1. 步骤一\n2. 步骤二'} />
      </Form.Item>
      <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button onClick={onCancel}>取消</Button>
        <Button type="primary" htmlType="submit">保存</Button>
      </Space>
    </Form>
  )
}

const skillCols: ColDef[] = [
  { title: '名称', dataIndex: 'name' },
  { title: '描述', dataIndex: 'description', ellipsis: true },
  { title: '版本', dataIndex: 'version', render: (v) => verText(v as number), className: 'xj-mono' },
  { title: '状态', dataIndex: 'status', render: (v) => statusTag(v as string) },
]

/* ---------- MCP 页签（含 discover 探测） ---------- */
function McpForm({ initialValues, onSave, onCancel }: EditorFormProps) {
  return (
    <Form layout="vertical" initialValues={initialValues} onFinish={(v) => onSave(v as Record<string, unknown>)}>
      <Form.Item label="名称" name="name" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item label="服务地址" name="endpoint" rules={[{ required: true, message: '请输入 MCP 服务地址' }]}
        extra="保存后可用「探测工具」读取该服务提供的工具清单">
        <Input placeholder="http://host:port/mcp" />
      </Form.Item>
      <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button onClick={onCancel}>取消</Button>
        <Button type="primary" htmlType="submit">保存</Button>
      </Space>
    </Form>
  )
}

function McpTab() {
  const [discovering, setDiscovering] = useState<number | null>(null)
  const discover = (rec: AssetRow) => {
    setDiscovering(rec.id)
    apiJson<{ tools?: unknown[] }>('/api/mcp/' + rec.id + '/discover', 'POST')
      .then((d) => message.success('发现 ' + (d.tools ? d.tools.length : 0) + ' 个工具'))
      .catch((e: Error) => message.error('探测失败：' + e.message))
      .finally(() => setDiscovering(null))
  }
  const discoverOp: ColDef = {
    title: '探测',
    render: (_, r) => (
      <a onClick={() => discover(r)}>{discovering === r.id ? '探测中...' : '发现工具'}</a>
    ),
  }
  return (
    <AssetTab
      endpoint="mcp"
      rowKey="id"
      EditorForm={McpForm}
      columns={[
        { title: '名称', dataIndex: 'name' },
        { title: 'Endpoint', dataIndex: 'endpoint', className: 'xj-mono', ellipsis: true },
        { title: '版本', dataIndex: 'version', render: (v) => verText(v as number), className: 'xj-mono' },
        { title: '状态', dataIndex: 'status', render: (v) => statusTag(v as string) },
        discoverOp,
      ]}
    />
  )
}

/* ---------- 模型供应商页签（凭证加密落库、明文不回显；连通性测试走真实外网） ---------- */
function ModelForm({ initialValues, onSave, onCancel }: EditorFormProps) {
  const hasCred = !!initialValues.has_credential
  return (
    <Form
      layout="vertical"
      initialValues={{ protocol: 'OPENAI_PROTOCOL', ...initialValues }}
      onFinish={(v) => onSave(v as Record<string, unknown>)}
    >
      <Form.Item label="名称" name="name" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item label="接入协议" name="protocol" rules={[{ required: true }]}>
        <Select options={['OPENAI_PROTOCOL', 'ANTHROPIC_PROTOCOL'].map((v) => ({ value: v, label: v }))} />
      </Form.Item>
      <Form.Item label="Base URL（官方 / 兼容端点 / 自建推理）" name="base_url" rules={[{ required: true }]}>
        <Input placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
      </Form.Item>
      <Form.Item label="模型" name="model" rules={[{ required: true }]}>
        <Input placeholder="qwen-plus" />
      </Form.Item>
      <Form.Item
        label="API Key（加密落库；留空表示不修改；明文不回显）"
        name="credential"
        extra={hasCred ? '已配置凭证' : '尚未配置凭证'}
      >
        <Input.Password
          placeholder={hasCred ? '••••••••（留空不修改）' : 'sk-...'}
          autoComplete="new-password"
        />
      </Form.Item>
      <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button onClick={onCancel}>取消</Button>
        <Button type="primary" htmlType="submit">保存</Button>
      </Space>
    </Form>
  )
}

function ModelTab() {
  const [testing, setTesting] = useState<number | null>(null)
  const test = (rec: AssetRow) => {
    setTesting(rec.id)
    apiJson<{ ok: boolean; httpStatus?: number; latencyMs?: number; detail?: string; error?: string }>(
      '/api/model-providers/' + rec.id + '/test',
      'POST',
    )
      .then((d) => {
        if (d.ok) message.success('连通成功 http=' + d.httpStatus + ' ' + (d.latencyMs || '') + 'ms')
        else message.error('连通失败：' + (d.detail || d.error || 'http=' + d.httpStatus))
      })
      .catch((e: Error) => message.error('测试失败：' + e.message))
      .finally(() => setTesting(null))
  }
  const testOp: ColDef = {
    title: '测试',
    render: (_, r) => <a onClick={() => test(r)}>{testing === r.id ? '测试中...' : '连通性测试'}</a>,
  }
  return (
    <AssetTab
      endpoint="model-providers"
      rowKey="id"
      EditorForm={ModelForm}
      columns={[
        { title: '名称', dataIndex: 'name' },
        { title: '协议', dataIndex: 'protocol', className: 'xj-mono' },
        { title: 'Base URL', dataIndex: 'base_url', className: 'xj-mono', ellipsis: true },
        { title: '模型', dataIndex: 'model', className: 'xj-mono' },
        {
          title: '凭证',
          dataIndex: 'has_credential',
          render: (v) => (v ? <Tag color="green">已配置</Tag> : <Tag>未配置</Tag>),
        },
        { title: '版本', dataIndex: 'version', render: (v) => verText(v as number), className: 'xj-mono' },
        { title: '状态', dataIndex: 'status', render: (v) => statusTag(v as string) },
        testOp,
      ]}
    />
  )
}

/* ---------- 主页面 ---------- */
export default function Assets() {
  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="数据权限已生效：平台管理员可见全部；普通成员仅见自己创建的资产。发布后生成固定版本，引用方锁定该版本。"
      />
      <Tabs
        defaultActiveKey="tools"
        items={[
          { key: 'tools', label: '工具（Tool）', children: <AssetTab endpoint="tools" EditorForm={ToolForm} columns={toolCols} /> },
          { key: 'skills', label: '技能（Skill）', children: <AssetTab endpoint="skills" EditorForm={SkillForm} columns={skillCols} /> },
          { key: 'mcp', label: 'MCP 服务', children: <McpTab /> },
          { key: 'models', label: '模型供应商', children: <ModelTab /> },
        ]}
      />
    </div>
  )
}
