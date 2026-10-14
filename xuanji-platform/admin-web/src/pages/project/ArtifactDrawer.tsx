import { useState } from 'react'
import {
  Button,
  Drawer,
  Form,
  Input,
  Select,
  message,
} from 'antd'
import { apiJson } from '../../api/client'
import { WorkNode, NODE_TYPE_LABEL } from './types'

export function ArtifactDrawer({ open, projectId, nodes, onClose, onSaved }: {
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
