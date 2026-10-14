import { useEffect, useState } from 'react'
import {
  Form,
  Input,
  Modal,
  Select,
  message,
} from 'antd'
import { apiJson } from '../../api/client'
import { WorkflowOption, ProjectTemplate, DsOption, SHOW_TEMPLATE_ENTRY } from './types'

/** 新建项目弹窗 */
/** 模板管理入口开关：模板编辑器就绪后置 true（列表页「模板管理」按钮 + 新建项目模板下拉） */

export function CreateProjectModal({ open, onClose, onCreated }: {
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

