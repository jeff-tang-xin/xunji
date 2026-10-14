import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Empty,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  ReloadOutlined,
} from '@ant-design/icons'
import { apiJson } from '../../api/client'
import { stageColor, stageLabel } from './types'
import { StageChatMsg } from './StageChatPanel'

export function StageSessionsPanel({ projectId }: { projectId: string }) {
  const [sessions, setSessions] = useState<
    { session_id: string; stage: string; iteration: string; last_at?: string }[]
  >([])
  const [loading, setLoading] = useState(false)
  const [openId, setOpenId] = useState<string | null>(null)
  const [history, setHistory] = useState<StageChatMsg[]>([])
  const [histLoading, setHistLoading] = useState(false)

  const loadSessions = useCallback(() => {
    setLoading(true)
    apiJson<{ session_id: string; stage: string; iteration: string; last_at?: string }[]>(
      `/api/projects/${projectId}/stage/chat/sessions`,
    ).then((r) => setSessions(r || []))
      .catch((e: Error) => message.error(e.message))
      .finally(() => setLoading(false))
  }, [projectId])

  useEffect(() => { loadSessions() }, [loadSessions])

  const openSession = (sid: string, stage: string, iteration: string) => {
    if (openId === sid) { setOpenId(null); return }
    setOpenId(sid)
    setHistLoading(true)
    apiJson<{ messages?: { role?: string; text?: string }[] }>(
      `/api/projects/${projectId}/stage/chat/history?stage=${stage}&iteration=${iteration}`,
    ).then((r) => {
      setHistory((r?.messages || [])
        .filter((m) => (m.text || '').trim())
        .map((m, i) => ({
          id: i + 1,
          type: (m.role === 'USER' ? 'user' : 'agent') as 'user' | 'agent',
          text: m.text || '',
        })))
    }).catch((e: Error) => message.error(e.message))
      .finally(() => setHistLoading(false))
  }

  return (
    <div>
      <Space style={{ marginBottom: 8, width: '100%', justifyContent: 'space-between' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          每个阶段就是一个会话，阶段推进自动开新会话
        </Typography.Text>
        <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={loadSessions}>刷新</Button>
      </Space>
      {!loading && sessions.length === 0 ? (
        <Empty description="还没有阶段对话" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        sessions.map((s) => (
          <Card
            key={s.session_id}
            size="small"
            style={{ marginBottom: 8 }}
            title={(
              <Space size={6}>
                <Tag color={stageColor(s.stage)}>{stageLabel(s.stage)}</Tag>
                <span>第 {s.iteration} 轮</span>
                {s.last_at && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {String(s.last_at).replace('T', ' ').slice(0, 16)}
                  </Typography.Text>
                )}
              </Space>
            )}
            extra={(
              <Button size="small" type="link" onClick={() => openSession(s.session_id, s.stage, s.iteration)}>
                {openId === s.session_id ? '收起' : '回放'}
              </Button>
            )}
          >
            {openId === s.session_id && (
              <div style={{ maxHeight: 320, overflowY: 'auto', background: '#fafafa', padding: 10, borderRadius: 6 }}>
                {histLoading ? (
                  <div style={{ textAlign: 'center', padding: 16 }}><Spin /></div>
                ) : history.length === 0 ? (
                  <Empty description="该会话暂无消息" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                ) : (
                  history.map((m) => (
                    <div key={m.id} style={{ marginBottom: 8, textAlign: m.type === 'user' ? 'right' : 'left' }}>
                      <div style={{
                        display: 'inline-block', maxWidth: '90%', padding: '5px 10px', borderRadius: 8,
                        whiteSpace: 'pre-wrap', wordBreak: 'break-word', textAlign: 'left', fontSize: 12,
                        background: m.type === 'user' ? '#1677ff' : '#fff',
                        color: m.type === 'user' ? '#fff' : undefined,
                        border: m.type === 'user' ? 'none' : '1px solid #f0f0f0',
                      }}>
                        {m.text}
                      </div>
                    </div>
                  ))
                )}
              </div>
            )}
          </Card>
        ))
      )}
    </div>
  )
}

/**
 * 阶段设置：绑定工作流 + 阶段智能体。
 * 绑定工作流后，批准规划不再重新编译画布，直接复用该已发布定义起跑——
 * 同一套调好的流程（含决策门）跨项目/跨轮次复用，定义列表也不再同名膨胀。
 * 阶段智能体在对话与执行时优先于规划草稿里的 agents。
 */
