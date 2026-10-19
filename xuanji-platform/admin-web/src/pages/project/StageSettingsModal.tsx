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
import { OpsConnLists, OpsServer, OpsDatabase, cleanServers, cleanDatabases } from './OpsConnLists'

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
  // 运维连接（对象列表：地址+凭证+说明）——回显密码已脱敏（***），提交 *** 表示沿用旧值
  const [servers, setServers] = useState<OpsServer[]>([])
  const [databases, setDatabases] = useState<OpsDatabase[]>([])
  const [deploySpec, setDeploySpec] = useState('')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!open) return
    setLoading(true)
    // 三个接口并行拉：定义列表、agent 列表、当前阶段绑定
    Promise.all([
      apiJson<WorkflowOption[]>('/api/definitions'),
      apiJson<AgentOption[]>('/api/agents'),
      apiJson<{ capability?: Record<string, unknown>; note?: string | null }>(
        `/api/projects/${projectId}/stage/${stage}/capability`),
    ]).then(([d, a, c]) => {
      setDefs((d || []).filter((x) => x.status === 'PUBLISHED'))
      setAgentOpts((a || []).filter((x) => x.status === 'PUBLISHED'))
      const cp = c?.capability || {}
      setCap(cp)
      setNote(c?.note || '')
      setWorkflow(cp.workflow ? String(cp.workflow) : undefined)
      setBoundAgents(Array.isArray(cp.agents) ? (cp.agents as string[]) : [])
      setServers(Array.isArray(cp.servers) ? (cp.servers as OpsServer[]) : [])
      setDatabases(Array.isArray(cp.databases) ? (cp.databases as OpsDatabase[]) : [])
      setDeploySpec(cp.deploy_spec ? String(cp.deploy_spec) : '')
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
        servers: cleanServers(servers),
        databases: cleanDatabases(databases),
        deploy_spec: deploySpec,
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
          label="运维连接"
          extra="服务器与数据库连接由平台托管凭证，AI 经工具操作（remote_shell / db_query / db_dml）；密码回显为 ***，不改即沿用"
        >
          <OpsConnLists
            servers={servers}
            databases={databases}
            onServers={setServers}
            onDatabases={setDatabases}
          />
        </Form.Item>
        <Form.Item
          label="部署规范"
          extra="部署目录约定、启动/回滚方式、端口与进程名等规范——AI 按规范部署，不预置目录"
        >
          <Input.TextArea rows={3} value={deploySpec} onChange={(e) => setDeploySpec(e.target.value)}
            placeholder="如：统一部署到 /data/{项目名}/，start.sh 管理启停，端口 8xxx 顺延" />
        </Form.Item>
        <Form.Item label="备注" extra="这批绑定给 AI 用来干什么的说明，会随上下文注入">
          <Input.TextArea rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
        </Form.Item>
      </Form>
    </Modal>
  )
}

