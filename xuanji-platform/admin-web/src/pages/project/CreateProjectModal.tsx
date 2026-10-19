import { useEffect, useState } from 'react'
import {
  Form,
  Input,
  Modal,
  Select,
  message,
} from 'antd'
import { apiJson } from '../../api/client'
import { WorkflowOption, ProjectTemplate, SHOW_TEMPLATE_ENTRY } from './types'
import { OpsConnLists, OpsServer, OpsDatabase, cleanServers, cleanDatabases } from './OpsConnLists'

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
  // 运维连接（对象列表：地址+凭证+说明）——凭证平台加密托管，AI 只见引用 ID
  const [servers, setServers] = useState<OpsServer[]>([])
  const [databases, setDatabases] = useState<OpsDatabase[]>([])

  // 打开时从后端动态加载模板与已发布工作流；失败回退默认，不阻断创建
  useEffect(() => {
    if (!open) return
    let cancelled = false
    setServers([])
    setDatabases([])
    apiJson<ProjectTemplate[]>('/api/projects/templates')
      .then((list) => { if (!cancelled && Array.isArray(list) && list.length > 0) setTemplates(list) })
      .catch(() => { /* 加载失败保留默认 */ })
    apiJson<WorkflowOption[]>('/api/definitions')
      .then((list) => { if (!cancelled) setWorkflows((list || []).filter((x) => x.status === 'PUBLISHED')) })
      .catch(() => { /* 加载失败下拉为空 */ })
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
      // 运维资源绑定（可跳过）：写入 OPERATE 阶段能力绑定，与模板预设 merge。
      // 服务器/数据库为对象列表（地址+凭证+说明），凭证后端加密存储
      const sv = cleanServers(servers)
      const dbs = cleanDatabases(databases)
      const deploySpec = (v.deploy_spec || '').toString().trim()
      if (sv.length > 0) body.servers = sv
      if (dbs.length > 0) body.databases = dbs
      if (deploySpec) body.deploy_spec = deploySpec
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
          label="运维连接（可选）"
          extra="服务器与数据库连接由平台托管凭证，AI 经工具操作（remote_shell / db_query / db_dml）；写入运维阶段能力绑定"
        >
          <OpsConnLists
            servers={servers}
            databases={databases}
            onServers={setServers}
            onDatabases={setDatabases}
          />
        </Form.Item>
        <Form.Item
          name="deploy_spec"
          label="部署规范（可选）"
          extra="部署目录约定、启动/回滚方式、端口与进程名等规范——AI 按规范部署，不预置目录"
        >
          <Input.TextArea rows={3} placeholder="如：统一部署到 /data/{项目名}/，start.sh 管理启停，端口 8xxx 顺延" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

