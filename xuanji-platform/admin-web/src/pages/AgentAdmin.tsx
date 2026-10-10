import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Button, Col, Divider, Drawer, Form, Input, InputNumber, Modal, Popconfirm,
  Row, Select, Space, Table, Tag, Tooltip, Typography, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { apiFetch, apiJson } from '../api/client'
import type { AssetRow } from '../types/api'

/** agent_config 行（snake_case 对齐后端列名） */
interface AgentRow extends AssetRow {
  model_provider_id?: number | null
  system_prompt?: string | null
  tools_json?: ToolRef[] | null
  skills_json?: string[] | null
  params_json?: Record<string, unknown> | null
}

/** 工具绑定项（tools_json 数组元素，引用钉住已发布版本） */
interface ToolRef {
  type?: string
  ref?: string
  [key: string]: unknown
}

/** 资产下拉行（模型供应商 / 技能 / 工具 / MCP 共用：只取展示与引用所需字段） */
interface OptionRow {
  id: number
  name?: string | null
  description?: string | null
  protocol?: string
  model?: string
  version?: number | null
  current_version?: number | null
  status?: string | null
}

/** AgentForm 表单值（结构化字段，提交前组装为 tools_json / params_json） */
interface AgentFormValues {
  name?: string
  description?: string | null
  model_provider_id?: number | null
  system_prompt?: string | null
  toolRefs?: string[]
  mcpRefs?: string[]
  skills_json?: string[]
  temperature?: number | null
  maxTokens?: number | null
  topP?: number | null
}

const STATUS_TEXT: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已下线' }
const STATUS_COLOR: Record<string, string> = { DRAFT: 'default', PUBLISHED: 'green', DISABLED: 'red' }
const statusTag = (v: unknown) => {
  const s = String(v ?? '')
  return <Tag color={STATUS_COLOR[s] || 'default'}>{STATUS_TEXT[s] || s}</Tag>
}
const verText = (v?: number | null) => 'v' + (v || 1)
/** params_json 取数值参数（缺失或非法值按「未填」处理，表单留空即可） */
const numOf = (params: Record<string, unknown> | null | undefined, key: string): number | undefined => {
  const v = params?.[key]
  return typeof v === 'number' ? v : undefined
}
/** 引用串按前缀分池解析（tool/mcp/skill 的 id 空间互相独立，混池会取错名） */
function refName(ref: string, pools: Record<string, OptionRow[]>): string {
  const m = /^(tool|mcp|skill):(\d+)@(\d+)$/.exec(ref)
  if (!m) return ref
  const kindLabel = m[1] === 'tool' ? '工具' : m[1] === 'mcp' ? 'MCP' : '技能'
  const hit = (pools[m[1]] || []).find((p) => p.id === Number(m[2]))
  return `${kindLabel} · ${hit?.name || '#' + m[2]} · v${m[3]}`
}

/* Agent = 模型 + 提示词 + 能力绑定 + 生成参数；发布后版本固化，可被画布与第三方引用 */
function AgentForm({ initialValues, models, skills, tools, mcps, published, onSave, onCancel }: {
  initialValues: AgentFormValues
  models: OptionRow[]
  skills: OptionRow[]
  tools: OptionRow[]
  mcps: OptionRow[]
  published: boolean
  onSave: (values: AgentFormValues) => void
  onCancel: () => void
}) {
  /** 下拉只列已发布资产：草稿资产无法被 Agent 在运行时解析 */
  const toOptions = (rows: OptionRow[], prefix: string) => rows
    .filter((r) => r.status === 'PUBLISHED')
    .map((r) => {
      const v = r.current_version ?? r.version ?? 1
      return {
        value: `${prefix}:${r.id}@${v}`,
        label: (r.name || ('#' + r.id)) + ' · v' + v,
      }
    })
  return (
    <Form layout="vertical" initialValues={initialValues} onFinish={onSave}>
      {published && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 14 }}
          message="该 Agent 已发布。此处修改只影响草稿内容，保存后需重新发布才会生成新版本。"
        />
      )}
      <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入 Agent 名称' }]}>
        <Input placeholder="如 需求评审助手" />
      </Form.Item>
      <Form.Item label="说明" name="description">
        <Input.TextArea rows={2} placeholder="这个 Agent 负责什么，便于他人理解与复用" />
      </Form.Item>
      <Form.Item
        label="模型"
        name="model_provider_id"
        rules={[{ required: true, message: '请选择模型' }]}
        extra="模型地址与密钥在「资产管理 · 模型供应商」中维护"
      >
        <Select
          placeholder="选择模型"
          options={models.map((m) => ({
            value: m.id,
            label: (m.name || ('#' + m.id)) + ' · ' + (m.model || m.protocol || '')
              + (m.status === 'PUBLISHED' ? '' : '（草稿）'),
          }))}
        />
      </Form.Item>
      <Form.Item label="系统提示词" name="system_prompt" rules={[{ required: true, message: '请输入系统提示词' }]}>
        <Input.TextArea rows={6} placeholder={'你是资深需求分析师，负责评审需求文档并给出改进建议……'} />
      </Form.Item>

      <Divider orientation="left" plain>能力绑定</Divider>
      <Form.Item label="工具" name="toolRefs" extra="Agent 可在推理中调用所选工具，仅可选择已发布工具">
        <Select mode="multiple" allowClear placeholder="选择工具（可多选）" options={toOptions(tools, 'tool')} />
      </Form.Item>
      <Form.Item label="MCP 服务" name="mcpRefs" extra="所选 MCP 服务已发现的工具会一并提供给 Agent">
        <Select mode="multiple" allowClear placeholder="选择 MCP 服务（可多选）" options={toOptions(mcps, 'mcp')} />
      </Form.Item>
      <Form.Item label="技能" name="skills_json" extra="技能的操作指引会在执行时并入系统提示词">
        <Select mode="multiple" allowClear placeholder="选择技能（可多选）" options={toOptions(skills, 'skill')} />
      </Form.Item>

      <Divider orientation="left" plain>生成参数</Divider>
      <Row gutter={12}>
        <Col span={8}>
          <Form.Item label="温度" name="temperature" tooltip="0 最严谨，2 最发散；评审类建议 0.2～0.5">
            <InputNumber min={0} max={2} step={0.1} style={{ width: '100%' }} placeholder="0.3" />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item label="最大输出" name="maxTokens" tooltip="单次回复的最大 token 数，留空使用模型默认">
            <InputNumber min={1} step={256} style={{ width: '100%' }} placeholder="默认" />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item label="Top P" name="topP" tooltip="采样多样性，0～1；与温度二选一调整即可">
            <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} placeholder="默认" />
          </Form.Item>
        </Col>
      </Row>

      <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button onClick={onCancel}>取消</Button>
        <Button type="primary" htmlType="submit">保存</Button>
      </Space>
    </Form>
  )
}

export default function AgentAdmin() {
  const [rows, setRows] = useState<AgentRow[] | null>(null)
  const [models, setModels] = useState<OptionRow[]>([])
  const [skills, setSkills] = useState<OptionRow[]>([])
  const [tools, setTools] = useState<OptionRow[]>([])
  const [mcps, setMcps] = useState<OptionRow[]>([])
  const [editing, setEditing] = useState<Partial<AgentRow> | null>(null)
  const [pubRow, setPubRow] = useState<AgentRow | null>(null)

  const load = useCallback(() => {
    apiJson<AgentRow[]>('/api/agents').then(setRows).catch((e: Error) => message.error(e.message))
    apiJson<OptionRow[]>('/api/model-providers').then(setModels).catch(() => undefined)
    apiJson<OptionRow[]>('/api/skills').then(setSkills).catch(() => undefined)
    apiJson<OptionRow[]>('/api/tools').then(setTools).catch(() => undefined)
    apiJson<OptionRow[]>('/api/mcp').then(setMcps).catch(() => undefined)
  }, [])
  useEffect(() => { load() }, [load])

  /** 引用名解析池（展开行展示用，避免把 tool:1@2 这类内部引用抛给用户） */
  const refPools: Record<string, OptionRow[]> = { tool: tools, mcp: mcps, skill: skills }

  const save = (values: AgentFormValues) => {
    const params: Record<string, unknown> = {}
    if (values.temperature != null) params.temperature = values.temperature
    if (values.maxTokens != null) params.maxTokens = values.maxTokens
    if (values.topP != null) params.topP = values.topP
    const body: Record<string, unknown> = {
      name: values.name,
      description: values.description,
      model_provider_id: values.model_provider_id,
      system_prompt: values.system_prompt,
      tools_json: [
        ...(values.toolRefs || []).map((ref) => ({ type: 'TOOL', ref })),
        ...(values.mcpRefs || []).map((ref) => ({ type: 'MCP', ref })),
      ],
      skills_json: values.skills_json || [],
      params_json: params,
    }
    const isNew = !editing?.id
    const url = isNew ? '/api/agents' : '/api/agents/' + editing?.id
    apiJson(url, isNew ? 'POST' : 'PUT', body)
      .then(() => {
        message.success(isNew ? '已创建（草稿）' : '已保存')
        setEditing(null)
        load()
      })
      .catch((e: Error) => message.error(e.message))
  }

  // 删除保留原始 Response：后端对非草稿删除返回 400，需特判文案「仅草稿可删除」
  const del = (rec: AgentRow) => {
    apiFetch('/api/agents/' + rec.id, { method: 'DELETE' })
      .then((r) => r.json().then((d: { status?: number; error?: string }) => ({ ok: r.ok, d })))
      .then(({ ok, d }) => {
        if (!ok) throw new Error(d.status === 400 ? '已发布过的 Agent 不可删除' : d.error || '删除失败')
        message.success('已删除')
        load()
      })
      .catch((e: Error) => message.error(e.message))
  }

  const publish = () => {
    if (!pubRow) return
    apiJson<{ id: number; version: number }>('/api/agents/' + pubRow.id + '/publish', 'POST')
      .then((d) => {
        message.success('已发布 ' + verText(d.version) + '（新版本已生成，引用不受影响）')
        setPubRow(null)
        load()
      })
      .catch((e: Error) => message.error(e.message))
  }

  const columns: TableColumnsType<AgentRow> = [
    { title: '名称', dataIndex: 'name' },
    { title: '说明', dataIndex: 'description', ellipsis: true },
    { title: '版本', dataIndex: 'version', render: (v) => verText(v) },
    { title: '状态', dataIndex: 'status', render: statusTag },
    {
      title: '操作',
      width: 210,
      render: (_, r) => {
        const draft = r.status === 'DRAFT'
        return (
          <Space>
            <a onClick={() => setEditing(r)}>编辑</a>
            <a onClick={() => setPubRow(r)}>
              {r.status === 'PUBLISHED' ? '发布新版本' : r.status === 'DISABLED' ? '重新发布' : '发布'}
            </a>
            {draft ? (
              <Popconfirm title="确定删除该 Agent？" onConfirm={() => del(r)}>
                <a style={{ color: '#ff4d4f' }}>删除</a>
              </Popconfirm>
            ) : (
              <Tooltip title="已发布过的 Agent 不可删除；如需停用请先在发布中心下线">
                <Typography.Text style={{ color: 'rgba(0,0,0,.25)' }}>删除</Typography.Text>
              </Tooltip>
            )}
          </Space>
        )
      },
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12 }}>
        <Button type="primary" onClick={() => setEditing({})}>新建 Agent</Button>
        <span style={{ flex: 1 }} />
        <Button onClick={load}>刷新</Button>
      </div>
      <Table rowKey="id" size="middle" loading={!rows} dataSource={rows || []}
        expandable={{
          expandedRowRender: (r) => (
            <Space direction="vertical" size={4}>
              <span>系统提示词：<Typography.Text type="secondary">{(r.system_prompt || '').slice(0, 80)}{(r.system_prompt || '').length > 80 ? '…' : ''}</Typography.Text></span>
              <span>工具：{(r.tools_json || []).length === 0 ? <Tag>未绑定</Tag> :
                (r.tools_json || []).map((t, i) => (
                  <Tag key={i} color={t.type === 'MCP' ? 'cyan' : 'geekblue'}>
                    {t.ref ? refName(t.ref, refPools) : '未知引用'}
                  </Tag>
                ))}</span>
              <span>技能：{(r.skills_json || []).length === 0 ? <Tag>未绑定</Tag> :
                (r.skills_json || []).map((s, i) => <Tag key={i} color="purple">{refName(s, refPools)}</Tag>)}</span>
            </Space>
          ),
        }}
        columns={columns} />
      <Drawer title={editing && editing.id ? '编辑 Agent · ' + editing.name : '新建 Agent'} width={560}
        open={!!editing} onClose={() => setEditing(null)} destroyOnClose>
        {editing && (
          <AgentForm
            models={models} skills={skills} tools={tools} mcps={mcps}
            published={editing.status === 'PUBLISHED'}
            onCancel={() => setEditing(null)} onSave={save}
            initialValues={editing.id ? {
              ...editing,
              toolRefs: (editing.tools_json || []).filter((t) => t.type === 'TOOL').map((t) => t.ref as string),
              mcpRefs: (editing.tools_json || []).filter((t) => t.type === 'MCP').map((t) => t.ref as string),
              skills_json: editing.skills_json || [],
              temperature: numOf(editing.params_json, 'temperature'),
              maxTokens: numOf(editing.params_json, 'maxTokens'),
              topP: numOf(editing.params_json, 'topP'),
            } : {}}
          />
        )}
      </Drawer>
      <Modal title={'发布 ' + (pubRow ? pubRow.name : '')} open={!!pubRow} onCancel={() => setPubRow(null)}
        onOk={publish} okText="确认发布">
        <Alert type="warning" showIcon
          message="发布后将生成新版本，已引用本 Agent 的流程仍使用旧版本，需手动升级。" />
      </Modal>
    </div>
  )
}
