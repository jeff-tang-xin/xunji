import { useEffect, useState } from 'react'
import {
  Form,
  Input,
  Modal,
  Select,
  message,
} from 'antd'
import { apiJson } from '../../api/client'
import { AgentOption, WorkflowOption, stageLabel } from './types'

import { DsOption } from './types'

export function StageSettingsModal({ open, projectId, stage, onClose, onSaved }: {
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

