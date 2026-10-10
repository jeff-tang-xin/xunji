import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Button, Card, Form, Input, Modal, Radio, Select, Space, Table, Tabs, Tag, Tree, TreeSelect, Typography, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import dayjs from 'dayjs'
import { apiJson, me, type MeInfo } from '../api/client'

// 权限管理 · RBAC 实装版（主文档 v0.9 Q13）：用户→角色→菜单 + 组织树 + 数据权限四档
// 数据源：/api/admin/*（仅平台管理员）；菜单可见性随登录实时生效（重新登录后刷新）

/** /api/admin/users 行（snake_case 对齐后端） */
interface AdminUser {
  id: number
  username: string
  display_name?: string | null
  org_name?: string | null
  is_platform_admin?: boolean
  must_change_password?: boolean
  status?: string
  last_login_at?: string | null
  roles?: string[]
  roleIds?: number[]
}

/** /api/admin/roles 行 */
interface AdminRole {
  id: number
  code: string
  name: string
  description?: string | null
  data_scope?: string
  is_built_in?: boolean
  menuCodes?: string[]
}

/** /api/admin/menus 行 */
interface AdminMenu {
  id: number
  code: string
  name: string
  path?: string | null
}

/** /api/admin/orgs 行 */
interface AdminOrg {
  id: number
  name: string
  parent_id?: number | null
  path?: string | null
}

/** 新建用户 / 重置密码后一次性展示的初始密码 */
interface CreatedUser {
  id?: number
  username?: string
  initialPassword?: string
}

interface UserFormValues {
  username?: string
  displayName?: string
  orgId?: number | null
  roleIds?: number[]
}

interface RoleAssignValues {
  roleIds?: number[]
}

interface RoleCreateValues {
  name?: string
  code?: string
  dataScope?: DataScope
  desc?: string
}

interface OrgTreeNode {
  value: number
  title: string
  children: OrgTreeNode[]
}

interface OrgTreeDataNode {
  key: number
  title: string
  children: OrgTreeDataNode[]
}

// ===== 数据权限四档（角色级配置，与菜单可见性正交）=====
type DataScope = 'ALL' | 'UNDER_ORG' | 'ORG' | 'ONLY_CREATE'

interface ScopeMeta {
  label: string
  color: string
  desc: string
}

const SCOPE_META: Record<string, ScopeMeta> = {
  ALL: { label: '全部数据', color: 'geekblue', desc: '可见平台内全部组织的数据' },
  UNDER_ORG: { label: '本组织及下级', color: 'cyan', desc: '可见所属组织及其全部下级组织的数据' },
  ORG: { label: '仅本组织', color: 'green', desc: '仅可见所属组织自身的数据，不含下级组织' },
  ONLY_CREATE: { label: '仅本人创建', color: 'orange', desc: '仅可见本人创建的数据' },
}
const SCOPE_ORDER: DataScope[] = ['ALL', 'UNDER_ORG', 'ORG', 'ONLY_CREATE']

function ScopeTag({ scope }: { scope?: string }) {
  const m: Partial<ScopeMeta> = (scope && SCOPE_META[scope]) || {}
  return <Tag color={m.color}>{m.label || scope}</Tag>
}

// ===== 工具 =====
function legacyCopy(text: string): boolean {
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

function copyText(text: string) {
  const done = () => message.success('已复制到剪贴板')
  const fail = () => message.warning('自动复制失败，请手动选择复制')
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done).catch(() => { legacyCopy(text) ? done() : fail() })
  } else {
    legacyCopy(text) ? done() : fail()
  }
}

function fmtTime(v?: string | null) {
  return v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—'
}

const MONO = { fontFamily: 'Consolas, Monaco, monospace', fontSize: 12 }

// ===== 页签①：用户管理 =====
function UserTab({ users, roles, orgs, reload }: {
  users: AdminUser[]
  roles: AdminRole[]
  orgs: AdminOrg[]
  reload: () => void
}) {
  const [open, setOpen] = useState(false)
  const [created, setCreated] = useState<CreatedUser | null>(null)
  const [roleTarget, setRoleTarget] = useState<AdminUser | null>(null) // {user, roleIds}
  const [form] = Form.useForm<UserFormValues>()
  const [roleForm] = Form.useForm<RoleAssignValues>()

  const roleOptions = roles.filter((r) => r.code !== 'platform_admin')
    .map((r) => ({ value: r.id, label: r.name }))
  const orgTree = (() => {
    const byParent: Record<number, AdminOrg[]> = {}
    orgs.forEach((o) => {
      const k = o.parent_id || 0
      const list = byParent[k] || []
      list.push(o)
      byParent[k] = list
    })
    const build = (pid: number): OrgTreeNode[] =>
      (byParent[pid] || []).map((o) => ({ value: o.id, title: o.name, children: build(o.id) }))
    return build(0)
  })()

  const submit = () => {
    form.validateFields().then((vals) => {
      apiJson<CreatedUser>('/api/admin/users', 'POST', {
        username: vals.username, displayName: vals.displayName, orgId: vals.orgId || null,
        roleIds: vals.roleIds || [],
      }).then((data) => {
        setOpen(false)
        form.resetFields()
        setCreated(data)
        reload()
      }).catch((e: Error) => message.error(e.message))
    }).catch(() => undefined)
  }

  const resetPassword = (u: AdminUser) => Modal.confirm({
    title: `重置「${u.username}」的密码？`,
    content: '将生成新的随机密码（明文一次性展示），该用户下次登录必须修改密码。',
    okText: '重置',
    cancelText: '取消',
    onOk: () => apiJson<CreatedUser>(`/api/admin/users/${u.id}/reset-password`, 'POST').then(setCreated),
  })

  const toggleStatus = (u: AdminUser) => {
    const next = u.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    apiJson(`/api/admin/users/${u.id}/status`, 'PUT', { status: next })
      .then(() => {
        message.success(next === 'ACTIVE' ? '已启用' : '已停用')
        reload()
      })
  }

  const openRoles = (u: AdminUser) => {
    setRoleTarget(u)
    roleForm.setFieldsValue({ roleIds: (u.roleIds || []).map(Number) })
  }
  const saveRoles = () => {
    roleForm.validateFields().then((vals) => {
      apiJson(`/api/admin/users/${roleTarget?.id}/roles`, 'PUT', { roleIds: vals.roleIds || [] })
        .then(() => {
          setRoleTarget(null)
          message.success('角色已更新（重新登录后菜单生效）')
          reload()
        })
    }).catch(() => undefined)
  }

  const columns: TableColumnsType<AdminUser> = [
    {
      title: '用户名', dataIndex: 'username',
      render: (v, r) => <Space><b>{v}</b>{r.is_platform_admin && <Tag color="gold">平台管理员</Tag>}</Space>,
    },
    { title: '姓名', dataIndex: 'display_name' },
    {
      title: '所属组织', dataIndex: 'org_name',
      render: (v, r) => (r.is_platform_admin
        ? <Typography.Text type="secondary">—（bypass）</Typography.Text>
        : (v || '—')),
    },
    {
      title: '角色', dataIndex: 'roles',
      render: (v) => (v && v.length
        ? v.map((name: string) => <Tag key={name} color="blue">{name}</Tag>)
        : <Typography.Text type="secondary">未分配</Typography.Text>),
    },
    {
      title: '首次改密', dataIndex: 'must_change_password',
      render: (v) => (v ? <Tag color="orange">待修改</Tag> : <Tag color="green">已修改</Tag>),
    },
    {
      title: '状态', dataIndex: 'status',
      render: (v) => <Tag color={v === 'ACTIVE' ? 'green' : 'default'}>{v === 'ACTIVE' ? '启用' : '停用'}</Tag>,
    },
    { title: '最近登录', dataIndex: 'last_login_at', render: (v) => <span style={MONO}>{fmtTime(v)}</span> },
    {
      title: '操作',
      render: (_, r) => (
        <Space>
          <a onClick={() => openRoles(r)}>分配角色</a>
          <a onClick={() => resetPassword(r)}>重置密码</a>
          {!r.is_platform_admin && <a onClick={() => toggleStatus(r)}>{r.status === 'ACTIVE' ? '停用' : '启用'}</a>}
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12 }}>
        <Button type="primary" onClick={() => setOpen(true)}>新建用户</Button>
      </div>
      <Table rowKey="id" size="middle" dataSource={users} columns={columns} />
      <Modal title="新建用户" open={open} onOk={submit} okText="创建" cancelText="取消"
        onCancel={() => { setOpen(false); form.resetFields() }}>
        <Form form={form} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="如 chenxiao" />
          </Form.Item>
          <Form.Item name="displayName" label="姓名"><Input placeholder="如 陈晓" /></Form.Item>
          <Form.Item name="orgId" label="所属组织">
            <TreeSelect treeData={orgTree} placeholder="选择组织（可空）" showSearch treeNodeFilterProp="title" allowClear />
          </Form.Item>
          <Form.Item name="roleIds" label="角色（可多选叠加）">
            <Select mode="multiple" options={roleOptions} placeholder="选择角色（平台管理员为系统内置，不可授予）" />
          </Form.Item>
        </Form>
      </Modal>
      <Modal title="初始密码（仅展示一次）" open={!!created} onCancel={() => setCreated(null)}
        footer={<Button type="primary" onClick={() => setCreated(null)}>我已保存，关闭</Button>}>
        {created && <div>
          <Alert type="warning" showIcon style={{ marginBottom: 16 }}
            message="密码仅展示一次，请立即复制并转交用户；关闭后不可再查看（平台只存 BCrypt 哈希，不存明文）。" />
          <p style={{ margin: '0 0 8px' }}>用户名：<b>{created.username}</b></p>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, background: '#fafafa', border: '1px solid #e8e8e8', borderRadius: 6, padding: '10px 14px' }}>
            <span style={{ ...MONO, fontSize: 16, fontWeight: 600, letterSpacing: 1 }}>{created.initialPassword}</span>
            <Button size="small" onClick={() => copyText(created.initialPassword || '')}>复制</Button>
          </div>
        </div>}
      </Modal>
      <Modal title={`分配角色：${roleTarget ? roleTarget.username : ''}`} open={!!roleTarget}
        onOk={saveRoles} okText="保存" cancelText="取消" onCancel={() => setRoleTarget(null)}>
        <Form form={roleForm} layout="vertical">
          <Form.Item name="roleIds" label="角色（可多选叠加）">
            <Select mode="multiple" options={roleOptions} placeholder="选择角色" />
          </Form.Item>
        </Form>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>菜单可见性由角色决定；变更后该用户重新登录生效。</Typography.Text>
      </Modal>
    </div>
  )
}

// ===== 页签②：角色管理 =====
function RoleTab({ roles, reload }: { roles: AdminRole[]; reload: () => void }) {
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm<RoleCreateValues>()

  const submit = () => {
    form.validateFields().then((vals) => {
      apiJson('/api/admin/roles', 'POST', {
        code: vals.code, name: vals.name, description: vals.desc || '',
        dataScope: vals.dataScope, menuIds: [],
      }).then(() => {
        setOpen(false)
        form.resetFields()
        message.success('角色已创建')
        reload()
      }).catch((e: Error) => message.error(e.message))
    }).catch(() => undefined)
  }

  const changeScope = (r: AdminRole, dataScope: DataScope) => {
    apiJson(`/api/admin/roles/${r.id}/data-scope`, 'PUT', { dataScope })
      .then(() => {
        message.success('数据范围已更新')
        reload()
      })
      .catch((e: Error) => message.error(e.message))
  }

  const columns: TableColumnsType<AdminRole> = [
    {
      title: '角色名', dataIndex: 'name',
      render: (v, r) => <Space>{v}{r.is_built_in && <Tag color="gold">内置</Tag>}</Space>,
    },
    { title: '编码', dataIndex: 'code', render: (v) => <span style={MONO}>{v}</span> },
    {
      title: '数据范围', dataIndex: 'data_scope',
      render: (v, r) => (r.is_built_in && r.code === 'platform_admin'
        ? <Space><ScopeTag scope={v} /><Tag color="gold">bypass</Tag></Space>
        : <Select<DataScope> value={v} size="small" style={{ width: 150 }}
            onChange={(val) => changeScope(r, val)}
            options={SCOPE_ORDER.map((s) => ({ value: s, label: SCOPE_META[s].label }))} />),
    },
    { title: '描述', dataIndex: 'description' },
  ]

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12 }}>
        <Button type="primary" onClick={() => setOpen(true)}>新建角色</Button>
      </div>
      <Table rowKey="id" size="middle" dataSource={roles} columns={columns} />
      <Modal title="新建角色" open={open} onOk={submit} okText="创建" cancelText="取消"
        onCancel={() => { setOpen(false); form.resetFields() }}>
        <Form form={form} layout="vertical" initialValues={{ dataScope: 'ONLY_CREATE' }}>
          <Form.Item name="name" label="角色名" rules={[{ required: true, message: '请输入角色名' }]}>
            <Input placeholder="如 交付项目经理" />
          </Form.Item>
          <Form.Item name="code" label="角色编码" rules={[{ required: true, message: '请输入编码' }]}>
            <Input placeholder="如 delivery_pm（唯一）" />
          </Form.Item>
          <Form.Item name="dataScope" label="数据范围（角色级配置，四档）" rules={[{ required: true, message: '请选择数据范围' }]}>
            <Radio.Group style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {SCOPE_ORDER.map((s) => (
                <Radio key={s} value={s}>
                  <Space size={8} wrap><ScopeTag scope={s} /><Typography.Text type="secondary">{SCOPE_META[s].desc}</Typography.Text></Space>
                </Radio>
              ))}
            </Radio.Group>
          </Form.Item>
          <Form.Item name="desc" label="描述（可选）"><Input.TextArea rows={2} placeholder="角色职责说明" /></Form.Item>
        </Form>
        <Alert type="info" showIcon
          message="创建后请在「菜单权限」页签勾选该角色可见的菜单。" />
      </Modal>
    </div>
  )
}

// ===== 页签③：菜单权限 =====
function MenuTab({ roles, menus, reload }: { roles: AdminRole[]; menus: AdminMenu[]; reload: () => void }) {
  const [roleId, setRoleId] = useState<number | null>(null)
  const [checked, setChecked] = useState<string[]>([])
  const activeId = roleId || (roles[0] && roles[0].id)

  useEffect(() => {
    if (!activeId) return
    const r = roles.find((x) => x.id === activeId)
    setChecked((r && r.menuCodes) || [])
  }, [activeId, roles])

  const treeData = menus.map((m) => ({ key: m.code, title: `${m.name}（${m.path || ''}）` }))
  const menuIdByCode: Record<string, number> = {}
  menus.forEach((m) => { menuIdByCode[m.code] = m.id })

  const save = () => {
    const menuIds = checked.map((c) => menuIdByCode[c]).filter((id): id is number => !!id)
    apiJson(`/api/admin/roles/${activeId}/menus`, 'PUT', { menuIds })
      .then(() => {
        message.success('菜单权限已保存（用户重新登录后生效）')
        reload()
      })
  }

  const activeRole = roles.find((r) => r.id === activeId)
  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
      <Card size="small" title="角色可见菜单" style={{ flex: 1 }}
        extra={<Select value={activeId} onChange={setRoleId} style={{ width: 180 }}
          options={roles.map((r) => ({ value: r.id, label: r.name }))} />}>
        {activeRole && activeRole.code === 'platform_admin' ? (
          <Alert type="warning" showIcon message="平台管理员为内置角色，固定可见全部菜单，不可修改。" />
        ) : (
          <div>
            <Tree checkable defaultExpandAll checkedKeys={checked}
              onCheck={(keys) => setChecked(Array.isArray(keys) ? keys as string[] : keys.checked as string[])}
              treeData={treeData} />
            <Button type="primary" style={{ marginTop: 12 }} onClick={save}>保存菜单权限</Button>
          </div>
        )}
      </Card>
    </div>
  )
}

// ===== 页签④：组织与数据范围 =====
function OrgTab({ orgs }: { orgs: AdminOrg[] }) {
  // 数据范围查询过滤随资产 CRUD 统一实装（Q13：过滤统一查询层 org_id + created_by）；
  // 此页签展示组织树与各角色档位语义。
  const byParent: Record<number, AdminOrg[]> = {}
  orgs.forEach((o) => {
    const k = o.parent_id || 0
    const list = byParent[k] || []
    list.push(o)
    byParent[k] = list
  })
  const build = (pid: number): OrgTreeDataNode[] =>
    (byParent[pid] || []).map((o) => ({ key: o.id, title: o.name, children: build(o.id) }))
  const orgTree = build(0)
  return (
    <div>
      <Card size="small" title="组织树">
        <Tree treeData={orgTree} defaultExpandAll selectable={false} />
      </Card>
    </div>
  )
}

// ===== 根组件 =====
export default function Permissions() {
  const [meInfo, setMeInfo] = useState<MeInfo | null>(null)
  const [users, setUsers] = useState<AdminUser[]>([])
  const [roles, setRoles] = useState<AdminRole[]>([])
  const [menus, setMenus] = useState<AdminMenu[]>([])
  const [orgs, setOrgs] = useState<AdminOrg[]>([])
  const [denied, setDenied] = useState(false)

  const reload = useCallback(() => {
    apiJson<AdminUser[]>('/api/admin/users').then(setUsers).catch(() => undefined)
    apiJson<AdminRole[]>('/api/admin/roles').then(setRoles).catch(() => undefined)
    apiJson<AdminMenu[]>('/api/admin/menus').then(setMenus).catch(() => undefined)
    apiJson<AdminOrg[]>('/api/admin/orgs').then(setOrgs).catch(() => undefined)
  }, [])

  useEffect(() => {
    me().then((u) => {
      setMeInfo(u)
      if (u.isPlatformAdmin) reload()
      else setDenied(true)
    })
  }, [reload])

  if (denied) {
    return <Alert type="warning" showIcon message="仅平台管理员可访问权限管理。" />
  }
  if (!meInfo) return <div style={{ padding: 60, color: '#999' }}>加载中...</div>

  return (
    <Tabs defaultActiveKey="user" items={[
      { key: 'user', label: '用户管理', children: <UserTab users={users} roles={roles} orgs={orgs} reload={reload} /> },
      { key: 'role', label: '角色管理', children: <RoleTab roles={roles} reload={reload} /> },
      { key: 'menu', label: '菜单权限', children: <MenuTab roles={roles} menus={menus} reload={reload} /> },
      { key: 'org', label: '组织与数据范围', children: <OrgTab orgs={orgs} /> },
    ]} />
  )
}
