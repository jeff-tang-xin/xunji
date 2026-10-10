import { useCallback, useEffect, useState } from 'react'
import { Table, Tag, Button, Modal, Form, Input, message, Popconfirm, Alert } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { apiJson } from '../api/client'

interface DataSource {
  id: number
  platform: string
  name: string
  endpoint: string
  extra_json?: string
  status?: string
}

/** 解析 extra_json 里的账号（后端脱敏列表带 extra_json，不含密码） */
function parseUser(extra?: string): string {
  if (!extra) return ''
  try {
    return (JSON.parse(extra) as { user?: string }).user || ''
  } catch {
    return ''
  }
}

/**
 * 数据源管理页（C5 数据源连接器配置，后端 /api/datasources）。
 * 形态：列表 + 新建（含测试连接）+ 删除。无发布生命周期（非资产版本化）。
 */
export default function DataSourceAdmin() {
  const [rows, setRows] = useState<DataSource[] | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [testing, setTesting] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(() => {
    apiJson<DataSource[]>('/api/datasources')
      .then(setRows)
      .catch((e: Error) => message.error(e.message))
  }, [])
  useEffect(() => {
    load()
  }, [load])

  const doTest = async () => {
    let v: { url?: string; user?: string; password?: string }
    try {
      v = await form.validateFields(['url', 'user', 'password'])
    } catch {
      return
    }
    setTesting(true)
    try {
      await apiJson('/api/datasources/test', 'POST', { url: v.url, user: v.user, password: v.password })
      message.success('连接成功')
    } catch (e) {
      message.error('连接失败：' + (e as Error).message)
    } finally {
      setTesting(false)
    }
  }

  const submit = async () => {
    let v: { name: string; url: string; user?: string; password?: string }
    try {
      v = await form.validateFields()
    } catch {
      return
    }
    try {
      await apiJson('/api/datasources', 'POST', { name: v.name, url: v.url, user: v.user, password: v.password })
      message.success('已创建数据源')
      setCreateOpen(false)
      form.resetFields()
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const del = (rec: DataSource) => {
    apiJson('/api/datasources/' + rec.id, 'DELETE')
      .then(() => {
        message.success('已删除')
        load()
      })
      .catch((e: Error) => message.error(e.message))
  }

  const columns: ColumnsType<DataSource> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '名称', dataIndex: 'name' },
    { title: '类型', dataIndex: 'platform', width: 90, render: (v: string) => <Tag>{v}</Tag> },
    { title: '连接地址', dataIndex: 'endpoint', ellipsis: true, className: 'xj-mono' },
    { title: '账号', render: (_, r) => parseUser(r.extra_json) || '-' },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v?: string) => <Tag color={v === 'ENABLED' ? 'green' : 'default'}>{v}</Tag>,
    },
    {
      title: '操作',
      width: 90,
      render: (_, r) => (
        <Popconfirm title="确定删除该数据源？引用它的流程将执行失败。" onConfirm={() => del(r)}>
          <a style={{ color: '#ff4d4f' }}>删除</a>
        </Popconfirm>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        <Button type="primary" onClick={() => setCreateOpen(true)}>新建数据源</Button>
        <span style={{ flex: 1 }} />
        <Button onClick={load}>刷新</Button>
      </div>
      <Alert
        style={{ marginBottom: 12 }}
        type="info"
        showIcon
        message="数据源供工作流节点查询外部业务库（如 ERP / WMS / 财务）。密码加密存储、不回显；创建时会先试连，连不通不会入库。"
      />
      <Table rowKey="id" size="middle" loading={!rows} dataSource={rows || []} columns={columns} />

      <Modal
        title="新建数据源"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        width={560}
        destroyOnClose
        onOk={submit}
        okText="创建"
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入数据源名称' }]}>
            <Input placeholder="如：生产订单库" />
          </Form.Item>
          <Form.Item name="url" label="JDBC 连接地址" rules={[{ required: true, message: '请输入 JDBC URL' }]}>
            <Input className="xj-mono" placeholder="jdbc:postgresql://host:5432/dbname" />
          </Form.Item>
          <Form.Item name="user" label="账号">
            <Input placeholder="数据库用户名" autoComplete="off" />
          </Form.Item>
          <Form.Item name="password" label="密码">
            <Input.Password placeholder="数据库密码（加密存储，不回显）" autoComplete="new-password" />
          </Form.Item>
        </Form>
        <Button onClick={doTest} loading={testing} block>
          测试连接
        </Button>
      </Modal>
    </div>
  )
}
