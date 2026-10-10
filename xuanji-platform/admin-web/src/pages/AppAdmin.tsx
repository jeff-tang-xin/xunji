/**
 * 应用管理 —— 等价迁移自 xuanji-server/src/main/resources/static/06-app-admin.html
 * App = 已发布资产的「对外消费合同」：绑定钉住版本 + AppKey/scope + 统一 API / 嵌入。
 * 有意差异：原文件 definitions 拉取误将 Response 直塞 state、操作类请求失败仍提示成功，
 * 此处统一改用 apiJson（非 2xx 抛错）修复。
 */
import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Button, Drawer, Form, Input, Modal, Popconfirm, Segmented, Select,
  Space, Table, Tag, Typography, message,
} from 'antd'
import { apiJson } from '../api/client'
import type { AppRow } from '../types/api'

const STATUS_COLOR: Record<string, string> = { DRAFT: 'default', PUBLISHED: 'green', DISABLED: 'red' }
const CRED_COLOR: Record<string, string> = { ACTIVE: 'green', DISABLED: 'default' }

/** 凭据列表行（后端返回 snake_case） */
interface CredRow {
  id: number
  key_prefix: string
  scopes: string
  status: string
  created_at?: string
}

/** 签发/轮换 AppKey 响应（明文 key 仅此一次） */
interface KeyIssueResponse {
  keyId?: number
  keyPrefix?: string
  rotatedFrom?: number
  key: string
}

/** KeyModal 展示用：签发响应 + 所属 App */
interface IssuedKey extends KeyIssueResponse {
  appId: string
}

/** 绑定资产下拉行（definitions / agents 列表） */
interface AssetOptionRow {
  id: number
  name?: string
  status?: string
  current_version?: number | null
  version?: number | null
}

function KeyModal({ cred, onClose }: { cred: IssuedKey | null; onClose: () => void }) {
  if (!cred) return null
  return (
    <Modal title="AppKey 已创建（明文仅此一次）" open onCancel={onClose}
      footer={<Button type="primary" onClick={onClose}>我已保存</Button>}>
      <Alert type="warning" showIcon style={{ marginBottom: 12 }}
        message="平台只存 SHA-256 哈希，关闭后无法再次查看明文，只能轮换重建" />
      <Input.Search readOnly value={cred.key} enterButton="复制"
        onSearch={() => { navigator.clipboard && navigator.clipboard.writeText(cred.key); message.success('已复制') }}
        className="xj-mono" />
      <Typography.Paragraph type="secondary" style={{ marginTop: 12, fontSize: 12 }}>
        调用方式：Authorization: Bearer &lt;key&gt; ｜ 接口：POST /api/v1/apps/{cred.appId}/runs ｜ 请通过 HTTPS 调用
      </Typography.Paragraph>
    </Modal>
  )
}

function EmbedModal({ app, onClose }: { app: AppRow | null; onClose: () => void }) {
  if (!app) return null
  const isAgent = app.bound_asset_type === 'AGENT'
  const [creds, setCreds] = useState<CredRow[]>([])
  const [keyId, setKeyId] = useState<number | undefined>(undefined)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let alive = true
    setLoading(true)
    apiJson(`/api/apps/${app.id}/credentials`)
      .then((list) => {
        const rows = list as CredRow[]
        if (!alive) return
        const chatKeys = rows.filter(
          (c) => c.status === 'ACTIVE' && (c.scopes || '').includes('"chat"'),
        )
        setCreds(chatKeys)
        if (chatKeys.length) setKeyId(chatKeys[0].id)
      })
      .catch((e: Error) => message.error('凭据加载失败：' + e.message))
      .finally(() => alive && setLoading(false))
    return () => { alive = false }
  }, [app.id])

  // 选中的凭据（明文 key 平台不存，嵌入代码需用户填入自己保存的 AppKey）
  const selCred = creds.find((c) => c.id === keyId)
  const snippet = selCred
    ? '<script src="' + location.origin + '/widget/chat.js"\n'
      + '  data-app-id="' + app.id + '"\n'
      + '  data-app-key="在此填入已保存的 AppKey（' + selCred.key_prefix + '…）"\n'
      + '  data-title="智能助手" defer><\/script>'
    : ''

  return (
    <Modal title={'页面嵌入 · ' + app.name} open onCancel={onClose} width={620}
      footer={<Button type="primary" onClick={onClose}>完成</Button>}>
      {!isAgent ? (
        <Alert type="warning" showIcon
          message="Chat Widget 仅支持绑定 Agent 的应用；当前应用绑定的是工作流，请通过 runs API 触发" />
      ) : (
        <>
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message="在任意网页挂一个右下角对话气泡，访客即可与该 Agent 对话。AppKey 决定调用配额与鉴权，请通过 HTTPS 使用。" />
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>
            选择 AppKey（须含 chat 权限）
          </Typography.Paragraph>
          <Select
            style={{ width: '100%', marginBottom: 12 }}
            loading={loading}
            value={keyId}
            onChange={setKeyId}
            placeholder="没有可用的 chat AppKey，请先在凭据中签发"
            options={creds.map((c) => ({
              value: c.id,
              label: `Key #${c.id}（${c.key_prefix}…）`,
            }))}
          />
          {selCred && (
            <>
              <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>
                嵌入代码（把 AppKey 占位符替换为签发时保存的明文 key）
              </Typography.Paragraph>
              <pre className="xj-mono" style={{
                background: '#fafafa', padding: 12, borderRadius: 6,
                whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12,
              }}>{snippet}</pre>
              <Button onClick={() => { navigator.clipboard && navigator.clipboard.writeText(snippet); message.success('已复制') }}>
                复制代码
              </Button>
            </>
          )}
          {!loading && creds.length === 0 && (
            <Alert type="warning" showIcon
              message="该应用还没有含 chat 权限的启用 AppKey，请先在下方凭据列表签发（scopes 勾选 chat）" />
          )}
        </>
      )}
    </Modal>
  )
}

function AppFormDrawer({ target, onClose, onSaved }: {
  target: Partial<AppRow>
  onClose: () => void
  onSaved: () => void
}) {
  const isEdit = !!target.id
  const [form] = Form.useForm()
  const [assetType, setAssetType] = useState(target.bound_asset_type || 'WORKFLOW')
  const [defs, setDefs] = useState<AssetOptionRow[]>([])
  const [agents, setAgents] = useState<AssetOptionRow[]>([])
  useEffect(() => {
    // 辅助下拉数据：加载失败不阻塞抽屉（保持原行为）
    apiJson<AssetOptionRow[]>('/api/definitions').then(setDefs).catch(() => undefined)
    apiJson<AssetOptionRow[]>('/api/agents').then(setAgents).catch(() => undefined)
  }, [])
  const published = (list: AssetOptionRow[]) => list.filter(a => a.status === 'PUBLISHED')
    .map(a => ({ value: a.id, label: '#' + a.id + ' ' + a.name + ' (v' + (a.current_version || a.version) + ')' }))
  const options = assetType === 'AGENT' ? published(agents) : published(defs)
  const save = async () => {
    const v = await form.validateFields()
    try {
      if (isEdit) {
        await apiJson('/api/apps/' + target.id, 'PUT', { name: v.name, description: v.description || '' })
        message.success('已保存')
      } else {
        await apiJson('/api/apps', 'POST', {
          name: v.name, description: v.description || '',
          bound_asset_type: assetType, bound_asset_id: v.assetId,
        })
        message.success('App 已创建（DRAFT）：发布后可签发 AppKey 对外服务')
      }
      onSaved(); onClose()
    } catch (e) { message.error('保存失败：' + (e as Error).message) }
  }
  return (
    <Drawer title={isEdit ? '编辑 App · ' + target.name : '新建 App'} width={480} open onClose={onClose}
      extra={<Space><Button onClick={onClose}>取消</Button><Button type="primary" onClick={save}>保存</Button></Space>}>
      <Form layout="vertical" form={form} initialValues={{ name: target.name, description: target.description }}>
        <Form.Item label="App 名称" name="name" rules={[{ required: true, message: '请输入名称' }]}><Input placeholder="面向第三方的应用名称" /></Form.Item>
        <Form.Item label="描述" name="description"><Input.TextArea rows={2} placeholder="用途说明（可选）" /></Form.Item>
        {!isEdit && <>
          <Form.Item label="绑定资产（Agent 或工作流，二选一）">
            <Segmented value={assetType} onChange={v => { setAssetType(String(v)); form.setFieldValue('assetId', undefined) }}
              options={[{ value: 'WORKFLOW', label: '📋 工作流' }, { value: 'AGENT', label: '🤖 Agent' }]} />
          </Form.Item>
          <Form.Item label={assetType === 'AGENT' ? '已发布 Agent 资产' : '已发布工作流资产'} name="assetId"
            rules={[{ required: true, message: '请选择已发布资产' }]}>
            <Select style={{ width: '100%' }} placeholder="仅列出已发布的资产" options={options} />
          </Form.Item>
          <Alert type="info" showIcon style={{ marginBottom: 8 }}
            message={assetType === 'AGENT' ? '对话型应用：通过对话接口实时交互' : '流程型应用：外部系统通过执行接口触发，按固定版本运行'} />
        </>}
          {isEdit && <Alert type="warning" showIcon message="绑定资产与版本创建后不可修改" />}
      </Form>
    </Drawer>
  )
}

function CredDrawer({ app, onClose, onChanged }: {
  app: AppRow | null
  onClose: () => void
  onChanged: () => void
}) {
  const [creds, setCreds] = useState<CredRow[]>([])
  const [newCred, setNewCred] = useState<IssuedKey | null>(null)
  const load = useCallback(() => {
    if (!app) return
    apiJson<CredRow[]>('/api/apps/' + app.id + '/credentials').then(setCreds).catch(() => undefined)
  }, [app])
  useEffect(() => { if (app) load() }, [app, load])
  if (!app) return null
  const create = async () => {
    try {
      const c = await apiJson<KeyIssueResponse>('/api/apps/' + app.id + '/credentials', 'POST', { scopes: ['runs'] })
      setNewCred({ ...c, appId: app.id }); load(); onChanged()
    } catch (e) { message.error('签发失败：' + (e as Error).message) }
  }
  const rotate = async (keyId: number) => {
    try {
      const c = await apiJson<KeyIssueResponse>('/api/apps/' + app.id + '/credentials/' + keyId + '/rotate', 'POST')
      setNewCred({ ...c, appId: app.id }); load(); onChanged()
    } catch (e) { message.error('轮换失败：' + (e as Error).message) }
  }
  const disable = async (keyId: number) => {
    try {
      await apiJson('/api/apps/' + app.id + '/credentials/' + keyId, 'DELETE')
      message.success('已禁用'); load()
    } catch (e) { message.error('操作失败：' + (e as Error).message) }
  }
  return (
    <Drawer title={'AppKey 管理 · ' + app.name} width={640} open onClose={onClose}>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="明文仅签发/轮换时展示一次，库中只存哈希；轮换 = 签发新 key + 旧 key 立即失效" />
      <div style={{ marginBottom: 12 }}><Button type="primary" onClick={create}>签发 AppKey</Button></div>
      <Table rowKey="id" size="small" dataSource={creds} pagination={false}
        columns={[
          { title: 'Key ID', dataIndex: 'id', width: 70 },
          { title: '前缀', dataIndex: 'key_prefix', className: 'xj-mono' },
          { title: 'scope', dataIndex: 'scopes', className: 'xj-mono', render: v => <span>{(v || '').replace(/"/g, '')}</span> },
          { title: '状态', dataIndex: 'status', render: v => <Tag color={CRED_COLOR[v]}>{v}</Tag> },
          { title: '操作', render: (_, r) => <Space>
              {r.status === 'ACTIVE' && <>
                <Popconfirm title="轮换后旧 key 立即失效，确认？" onConfirm={() => rotate(r.id)}><a>轮换</a></Popconfirm>
                <Popconfirm title="禁用后该 key 无法调用，确认？" onConfirm={() => disable(r.id)}><a>禁用</a></Popconfirm>
              </>}
            </Space> },
        ]} />
      <KeyModal cred={newCred} onClose={() => setNewCred(null)} />
    </Drawer>
  )
}

export default function AppAdmin() {
  const [apps, setApps] = useState<AppRow[]>([])
  const [formApp, setFormApp] = useState<Partial<AppRow> | null>(null)
  const [credApp, setCredApp] = useState<AppRow | null>(null)
  const [embedApp, setEmbedApp] = useState<AppRow | null>(null)
  const load = useCallback(() => {
    apiJson<AppRow[]>('/api/apps').then(setApps).catch((e: Error) => message.error('加载失败：' + e.message))
  }, [])
  useEffect(() => { load() }, [load])
  const act = (fn: Promise<unknown>, ok?: string) =>
    fn.then(() => { if (ok) message.success(ok); load() }).catch((e: Error) => message.error('操作失败：' + e.message))
  return (
    <div>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="应用是已发布资产的对外服务入口：绑定固定版本，通过 AppKey 控制谁能调用。内部用户直接使用工作区，外部系统通过应用接入。"
        description="开放接口：POST /api/v1/apps/{appId}/runs（触发执行）· GET .../runs/{runId}（查询结果）· 对话接口随对话能力开放。" />
      <div style={{ marginBottom: 12 }}><Button type="primary" onClick={() => setFormApp({})}>新建 App</Button></div>
      <Table rowKey="id" size="middle" dataSource={apps}
        columns={[
          { title: 'App ID', dataIndex: 'id', className: 'xj-mono', width: 230 },
          { title: '名称', dataIndex: 'name' },
          { title: '绑定资产', render: (_, r) => <span className="xj-mono">{r.bound_asset_type === 'AGENT' ? 'Agent' : '工作流'} #{r.bound_asset_id} · v{r.bound_asset_version}</span> },
          { title: '状态', dataIndex: 'status', render: v => <Tag color={STATUS_COLOR[v]}>{({ DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用' } as Record<string, string>)[v] || v}</Tag> },
          { title: '创建人', dataIndex: 'created_by', width: 90 },
          { title: '操作', render: (_, r) => <Space>
              {r.status === 'DRAFT' && <a onClick={() => act(apiJson('/api/apps/' + r.id + '/publish', 'POST'), '已发布')}>发布</a>}
              {r.status === 'PUBLISHED' && <a onClick={() => setCredApp(r)}>AppKey</a>}
              {r.status === 'PUBLISHED' && <a onClick={() => setEmbedApp(r)}>嵌入</a>}
              {r.status === 'PUBLISHED' && <Popconfirm title="禁用后全部 AppKey 失效，确认？" onConfirm={() => act(apiJson('/api/apps/' + r.id + '/disable', 'POST'), '已禁用')}><a>禁用</a></Popconfirm>}
              {r.status !== 'PUBLISHED' && <Popconfirm title="确认删除该 App？" onConfirm={() => act(apiJson('/api/apps/' + r.id, 'DELETE'), '已删除')}><a>删除</a></Popconfirm>}
              <a onClick={() => setFormApp(r)}>编辑</a>
            </Space> },
        ]} />
      {formApp && <AppFormDrawer target={formApp} onClose={() => setFormApp(null)} onSaved={load} />}
      <CredDrawer app={credApp} onClose={() => setCredApp(null)} onChanged={load} />
      <EmbedModal app={embedApp} onClose={() => setEmbedApp(null)} />
    </div>
  )
}
