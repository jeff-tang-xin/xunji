import { useCallback, useEffect, useState } from 'react'
import { Table, Tag, Button, Modal, Form, Input, message, Popconfirm, Alert, Select } from 'antd'
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

/** 数据库类型 → JDBC URL 前缀（与后端 DB_URL_PREFIX 对齐） */
const DB_TYPES = [
  { value: 'postgresql', label: 'PostgreSQL', prefix: 'jdbc:postgresql://', host: 'host:5432/dbname' },
  { value: 'mysql', label: 'MySQL', prefix: 'jdbc:mysql://', host: 'host:3306/dbname' },
] as const

/** 列表「类型」列：优先 extra_json.db_type，退回从 URL 前缀推断（旧数据无 db_type） */
function dbTypeOf(rec: DataSource): string {
  try {
    const t = (JSON.parse(rec.extra_json || '{}') as { db_type?: string }).db_type
    if (t) return t
  } catch { /* fallthrough */ }
  if (rec.endpoint?.startsWith('jdbc:mysql://')) return 'mysql'
  return 'postgresql'
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
    let v: { db_type?: string; url?: string; user?: string; password?: string }
    try {
      v = await form.validateFields(['db_type', 'url', 'user', 'password'])
    } catch {
      return
    }
    setTesting(true)
    try {
      await apiJson('/api/datasources/test', 'POST', { db_type: v.db_type, url: v.url, user: v.user, password: v.password })
      message.success('连接成功')
    } catch (e) {
      message.error('连接失败：' + (e as Error).message)
    } finally {
      setTesting(false)
    }
  }

  const submit = async () => {
    let v: { name: string; db_type?: string; url: string; user?: string; password?: string }
    try {
      v = await form.validateFields()
    } catch {
      return
    }
    try {
      await apiJson('/api/datasources', 'POST', { name: v.name, db_type: v.db_type, url: v.url, user: v.user, password: v.password })
      message.success('已创建数据源')
      setCreateOpen(false)
      form.resetFields()
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  /** 切换数据库类型：URL 为空或属于另一类型前缀时，自动换成新类型的模板（用户手填部分丢失可接受——前缀错了连不上） */
  const onDbTypeChange = (t: string) => {
    const def = DB_TYPES.find((d) => d.value === t)
    if (!def) return
    const url: string = form.getFieldValue('url') || ''
    const foreign = DB_TYPES.some((d) => d.value !== t && url.startsWith(d.prefix))
    if (!url || foreign) {
      form.setFieldValue('url', def.prefix + def.host)
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
    {
      title: '类型',
      width: 110,
      render: (_, r) => {
        const t = dbTypeOf(r)
        return <Tag color={t === 'mysql' ? 'blue' : 'geekblue'}>{DB_TYPES.find((d) => d.value === t)?.label || t}</Tag>
      },
    },
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
        <Form form={form} layout="vertical" preserve={false} initialValues={{ db_type: 'postgresql' }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入数据源名称' }]}>
            <Input placeholder="如：生产订单库" />
          </Form.Item>
          <Form.Item name="db_type" label="数据库类型" rules={[{ required: true, message: '请选择数据库类型' }]}>
            <Select
              options={DB_TYPES.map((d) => ({ value: d.value, label: d.label }))}
              onChange={onDbTypeChange}
            />
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
