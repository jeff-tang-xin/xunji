import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Col,
  Collapse,
  Drawer,
  Form,
  Input,
  Row,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  LockOutlined,
} from '@ant-design/icons'
import { apiJson } from '../../api/client'
import { AgentOption, WorkflowOption, DsOption, TemplateDetail } from './types'
import { Field } from './shared'

/** 模板 code 规则：小写字母/数字/下划线/连字符，1-32 位（与后端校验一致） */
const TEMPLATE_CODE_RE = /^[a-z0-9_-]{1,32}$/

export function TemplateEditor({ open, code, onClose, onSaved }: {
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




/** 阶段链可视化：横向节点链 + 状态着色 + 依赖箭头 */
