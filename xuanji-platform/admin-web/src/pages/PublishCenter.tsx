/**
 * 发布中心 —— 对齐原型 04-publish-center.html：五类资产统一生命周期视图。
 * 发布 = 资产版本固化（不可变已发布版本，对内+对外消费的唯一事实源）。
 * 数据源：/api/definitions（工作流）+ /api/{agents|skills|mcp|tools}；bundle 导入导出待实装（P5）。
 */
import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Drawer, Modal, Space, Table, Tabs, Tag, Timeline, Typography, message,
} from 'antd'
import type { TableProps } from 'antd'
import { apiJson } from '../api/client'

const STATUS_COLOR: Record<string, string> = {
  PUBLISHED: 'green', DRAFT: 'default', DEPRECATED: 'orange', OFFLINE: 'red',
}
const STATUS_TEXT: Record<string, string> = {
  PUBLISHED: '已发布', DRAFT: '草稿', DEPRECATED: '已弃用', OFFLINE: '已下线',
}

/** 资产行（五类通用字段；版本字段做 current_version/version 兼容） */
interface AssetRow {
  id: number
  name?: string
  status?: string
  current_version?: number | null
  version?: number | null
  updated_at?: string
}

type AssetKind = 'workflow' | 'agent' | 'skill' | 'mcp' | 'tool'

const KIND_META: Record<AssetKind, { label: string; path: string }> = {
  workflow: { label: '工作流', path: '/api/definitions' },
  agent: { label: 'Agent', path: '/api/agents' },
  skill: { label: '技能', path: '/api/skills' },
  mcp: { label: 'MCP', path: '/api/mcp' },
  tool: { label: '工具', path: '/api/tools' },
}

const verText = (r: AssetRow) => 'v' + (r.current_version ?? r.version ?? '-')

const timeText = (v?: string) => (v || '').replace('T', ' ').slice(0, 16)

/** 版本历史 Drawer（版本 Timeline；diff/回滚待实装） */
function HistoryDrawer({ row, kind, onClose }: { row: AssetRow | null; kind: AssetKind; onClose: () => void }) {
  if (!row) return null
  return (
    <Drawer title={`版本历史 · ${row.name || row.id}`} width={420} open onClose={onClose}>
      <Timeline
        items={[
          {
            color: row.status === 'PUBLISHED' ? 'green' : 'gray',
            children: (
              <div>
                <b className="xj-mono">{verText(row)}</b>
                {row.status === 'PUBLISHED' && <Tag color="green" style={{ marginLeft: 6 }}>当前</Tag>}
                <div style={{ fontSize: 12 }}>{KIND_META[kind].label} · {STATUS_TEXT[row.status || ''] || row.status || '—'}</div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  更新于 {timeText(row.updated_at) || '—'}
                </Typography.Text>
              </div>
            ),
          },
        ]}
      />
    </Drawer>
  )
}

/** 单类资产表：发布动作 + 版本历史 + 导出占位 */
function AssetTable({ kind, onChanged }: { kind: AssetKind; onChanged: () => void }) {
  const [rows, setRows] = useState<AssetRow[]>([])
  const [loading, setLoading] = useState(false)
  const [hist, setHist] = useState<AssetRow | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await apiJson<AssetRow[]>(KIND_META[kind].path))
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setLoading(false)
    }
  }, [kind])

  useEffect(() => {
    void load()
  }, [load])

  const publish = (r: AssetRow) => {
    const isNew = r.status === 'PUBLISHED'
    Modal.confirm({
      title: isNew ? `发布新版本 · ${r.name || r.id}` : `发布 ${r.name || r.id} ${verText(r)}`,
      content: isNew
        ? '当前版本已固定；再次发布将生成新版本，已有引用不受影响，升级引用需手动操作。'
        : '发布即版本固化：该版本此后不可变，可被工作流引用、工作区绑定、发布为 App。确定发布？',
      okText: isNew ? '发布新版本' : '发布',
      onOk: async () => {
        try {
          await apiJson(`${KIND_META[kind].path}/${r.id}/publish`, 'POST')
          message.success(isNew ? '新版本已发布，旧引用不受影响' : '已发布，可被引用/绑定')
          void load()
          onChanged()
        } catch (e) {
          if (e instanceof Error) message.error(e.message)
        }
      },
    })
  }

  const cols: TableProps<AssetRow>['columns'] = [
    { title: '名称', dataIndex: 'name', render: (v: string | undefined, r) => v || `#${r.id}` },
    { title: '当前版本', key: 'ver', className: 'xj-mono', width: 100, render: (_, r) => verText(r) },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (v: string | undefined) => (
        <Tag color={STATUS_COLOR[v || ''] || 'default'}>{STATUS_TEXT[v || ''] || v || '—'}</Tag>
      ),
    },
    { title: '更新时间', dataIndex: 'updated_at', width: 150, render: (v: string) => timeText(v) || '—' },
    {
      title: '操作', key: 'op', width: 150,
      render: (_, r) => (
        <Space>
          <a onClick={() => publish(r)}>{r.status === 'PUBLISHED' ? '发布新版本' : '发布'}</a>
          <a onClick={() => setHist(r)}>版本历史</a>
        </Space>
      ),
    },
  ]

  return (
    <>
      <Table<AssetRow> rowKey="id" size="middle" loading={loading} columns={cols} dataSource={rows} pagination={{ pageSize: 10 }} />
      <HistoryDrawer row={hist} kind={kind} onClose={() => setHist(null)} />
    </>
  )
}

export default function PublishCenter() {
  const [, setTick] = useState(0)
  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="发布即版本固化：已发布的版本不可修改，引用它的流程继续使用发布时的版本。"
      />
      <Tabs
        items={(Object.keys(KIND_META) as AssetKind[]).map((k) => ({
          key: k,
          label: KIND_META[k].label,
          children: <AssetTable kind={k} onChanged={() => setTick((t) => t + 1)} />,
        }))}
      />
    </div>
  )
}
