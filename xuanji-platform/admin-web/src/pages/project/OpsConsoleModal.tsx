import { useEffect, useState } from 'react'
import { Button, Empty, Input, Modal, Radio, Space, Tag, Typography, message } from 'antd'
import { CaretRightOutlined, DatabaseOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { apiJson } from '../../api/client'

type OpsServer = { address: string; note?: string }
type OpsDatabase = { address: string; username?: string; note?: string }

type ConnOption = {
  ref: string
  kind: 'server' | 'database'
  label: string
}

type ExecRecord = {
  key: number
  title: string
  ok: boolean
  detail: string
}

/**
 * 运维操作弹窗——用户直接操作项目绑定的服务器/数据库（与 AI 工具共用同一连接底座）。
 *
 * 凭证托管：用户也只选连接引用（server-1/db-1），凭证在平台侧解密使用，界面不回显。
 * 服务器连接 → 执行 shell 命令；数据库连接 → 只读查询 / 单条变更。
 */
export function OpsConsoleModal({ open, projectId, onClose }: {
  open: boolean
  projectId: string
  onClose: () => void
}) {
  const [conns, setConns] = useState<ConnOption[]>([])
  const [selected, setSelected] = useState<string>('')
  const [command, setCommand] = useState('')
  const [busy, setBusy] = useState(false)
  const [records, setRecords] = useState<ExecRecord[]>([])
  const [seq, setSeq] = useState(0)

  useEffect(() => {
    if (!open) return
    setConns([])
    setSelected('')
    setCommand('')
    setRecords([])
    apiJson<{ capability?: Record<string, unknown> }>(`/api/projects/${projectId}/stage/OPERATE/capability`)
      .then((c) => {
        const cap = (c?.capability || {}) as { servers?: OpsServer[]; databases?: OpsDatabase[] }
        const opts: ConnOption[] = []
        ;(Array.isArray(cap.servers) ? cap.servers : []).forEach((s, i) => {
          opts.push({ ref: `server-${i + 1}`, kind: 'server', label: `server-${i + 1} ${s.address}${s.note ? `（${s.note}）` : ''}` })
        })
        ;(Array.isArray(cap.databases) ? cap.databases : []).forEach((d, i) => {
          opts.push({ ref: `db-${i + 1}`, kind: 'database', label: `db-${i + 1} ${d.address}${d.note ? `（${d.note}）` : ''}` })
        })
        setConns(opts)
        if (opts.length > 0) setSelected(opts[0].ref)
      })
      .catch((e: Error) => message.error(e.message))
  }, [open, projectId])

  const selectedKind = conns.find((c) => c.ref === selected)?.kind

  const push = (title: string, ok: boolean, detail: string) => {
    setSeq((n) => n + 1)
    setRecords((prev) => [{ key: seq + 1, title, ok, detail }, ...prev].slice(0, 20))
  }

  const runServer = async () => {
    if (!command.trim()) return
    setBusy(true)
    try {
      const r = await apiJson<{ exitCode: number; stdout: string; stderr: string }>(
        `/api/projects/${projectId}/ops/server/exec`, 'POST',
        { server: selected, command: command.trim() })
      const ok = r.exitCode === 0
      push(`$ ${command.trim()}`, ok, `exit ${r.exitCode}\n${r.stdout}${r.stderr ? `\n[stderr] ${r.stderr}` : ''}`)
      if (!ok) message.warning(`命令退出码 ${r.exitCode}`)
    } catch (e) {
      push(`$ ${command.trim()}`, false, e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const runDb = async (readOnly: boolean) => {
    if (!command.trim()) return
    setBusy(true)
    try {
      const path = readOnly ? 'ops/db/query' : 'ops/db/exec'
      const r = await apiJson<Record<string, unknown>>(
        `/api/projects/${projectId}/${path}`, 'POST',
        { db: selected, sql: command.trim() })
      if (readOnly) {
        const rows = (r.rows || []) as Record<string, unknown>[]
        const cols = (r.columns || []) as string[]
        push(`SELECT → ${r.count} 行`, true, cols.join(' | ') + '\n' + rows.slice(0, 20).map((row) => cols.map((c) => String(row[c] ?? '')).join(' | ')).join('\n'))
      } else {
        push(`EXEC → affected ${r.affected}`, true, `影响行数 ${r.affected}`)
      }
    } catch (e) {
      push(command.trim(), false, e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title="运维操作"
      open={open}
      onCancel={onClose}
      footer={null}
      width={780}
      destroyOnClose
    >
      {conns.length === 0 ? (
        <Empty description="项目未配置运维连接（在编辑项目里添加服务器/数据库连接）" />
      ) : (
        <>
          <Radio.Group
            value={selected}
            onChange={(e) => { setSelected(e.target.value); setCommand('') }}
            optionType="button"
            buttonStyle="solid"
            size="small"
            options={conns.map((c) => ({ value: c.ref, label: c.label }))}
          />
          <div style={{ marginTop: 10 }}>
            {selectedKind === 'server' ? (
              <Space.Compact style={{ width: '100%' }}>
                <Input
                  placeholder="shell 命令，如：systemctl status app"
                  value={command}
                  onChange={(e) => setCommand(e.target.value)}
                  onPressEnter={() => void runServer()}
                />
                <Button type="primary" icon={<CaretRightOutlined />} loading={busy} onClick={() => void runServer()}>
                  执行
                </Button>
              </Space.Compact>
            ) : (
              <>
                <Input.TextArea
                  rows={3}
                  placeholder={'SQL（查询用「查询」，建表/变更用「执行」；一次一条）'}
                  value={command}
                  onChange={(e) => setCommand(e.target.value)}
                />
                <Space style={{ marginTop: 8 }}>
                  <Button icon={<DatabaseOutlined />} loading={busy} onClick={() => void runDb(true)}>查询</Button>
                  <Button type="primary" danger icon={<ThunderboltOutlined />} loading={busy} onClick={() => void runDb(false)}>
                    执行（变更）
                  </Button>
                </Space>
              </>
            )}
          </div>
          <div style={{ marginTop: 14 }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>执行记录（最新在上，最多保留 20 条）</Typography.Text>
            {records.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无执行记录" style={{ marginTop: 8 }} />
            ) : (
              records.map((r) => (
                <div key={r.key} style={{ marginTop: 8, border: '1px solid #f0f0f0', borderRadius: 6, padding: 8 }}>
                  <Space>
                    <Tag color={r.ok ? 'green' : 'red'}>{r.ok ? '成功' : '失败'}</Tag>
                    <Typography.Text style={{ fontSize: 12, wordBreak: 'break-all' }}>{r.title}</Typography.Text>
                  </Space>
                  <pre style={{ margin: '6px 0 0', fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 200, overflow: 'auto' }}>
                    {r.detail}
                  </pre>
                </div>
              ))
            )}
          </div>
        </>
      )}
    </Modal>
  )
}
