/**
 * 工作区 —— 对齐原型 00-workspace.html：左侧工作区卡片列表 + 右侧对话式主交互。
 * 消息形态七种（system/user/agent/stage/tool/artifact/decision），本轮真实事件：
 * user/agent/system + agent.delta 流式 + tool/tool.result 卡片（工具注册已通）
 * + decision 卡片（WORKFLOW 绑定 run SUSPENDED → 对话流内批注/通过/驳回 → /api/decisions）；
 * stage/artifact 卡片组件就绪，随决策门事件通道（decision.request）启用。
 * 交互增强：高度链修复（对话区独立滚动）、WORKFLOW 绑定拉最近 run 状态（SUSPENDED 感知）、
 * TextArea 输入（Enter 发送 / Shift+Enter 换行）、决策卡片跳审批中心。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert, Button, Card, Dropdown, Drawer, Empty, Form, Input, Modal, Segmented, Select,
  Space, Spin, Tag, Tooltip, Typography, message,
} from 'antd'
import {
  RobotOutlined, UserOutlined, PlusOutlined, SendOutlined,
  ShareAltOutlined, LinkOutlined, ApartmentOutlined,
} from '@ant-design/icons'
import { apiFetch, apiJson } from '../api/client'

/** 工作区行（后端 snake_case） */
interface WsRow {
  id: string
  name: string
  root_path: string
  binding_type: 'AGENT' | 'WORKFLOW'
  bound_asset_id: number
  bound_asset_version: number
  authorization_json?: string | null
  created_by?: string
  created_at?: string
}

/** 工作区最近 run 概要（WORKFLOW 绑定时经 GET /api/runs?definitionId= 查询） */
interface WsRunState {
  runId: string
  status: string
}

interface AssetOptionRow {
  id: number
  name?: string
  status?: string
  current_version?: number | null
  version?: number | null
}

/** 对话消息（渲染态）：type 对齐原型七形态 */
interface ChatMsg {
  id: number
  type: 'system' | 'user' | 'agent' | 'stage' | 'tool' | 'artifact' | 'decision'
  text?: string
  agent?: string
  streaming?: boolean
  /** tool 卡片：工具名（tool.call 事件） */
  tool?: string
  /** tool 卡片：调用标识（tool.result 回填匹配） */
  toolCallId?: string
  /** tool 卡片：执行状态 */
  toolState?: 'running' | 'success' | 'error'
  /** decision 卡片：挂起 run 与决策门节点（WORKFLOW 绑定 run SUSPENDED） */
  runId?: string
  nodeId?: string
  /** decision 卡片：决策状态 */
  decisionPhase?: 'pending' | 'approved' | 'rejected'
}

/** 解析 SSE 字节流：按空行分帧，提取 event:/data: 行 */
async function readSse(res: Response, onEvent: (event: string, data: string) => void): Promise<void> {
  const reader = res.body?.getReader()
  if (!reader) throw new Error('响应无流')
  const decoder = new TextDecoder()
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      let event = 'message'
      const dataLines: string[] = []
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (dataLines.length > 0) onEvent(event, dataLines.join('\n'))
    }
  }
}

/** 绑定目标显示名（产品语言：类型 + 名称 + 版本） */
const bindLabel = (w: WsRow) => {
  const type = w.binding_type === 'AGENT' ? 'Agent' : '工作流'
  return `${type} · v${w.bound_asset_version}`
}

/** run 状态 → 圆点颜色与文案（状态映射为产品语言） */
function runStatusView(s?: string): { color: string; text: string } {
  switch (s) {
    case 'RUNNING': return { color: '#52c41a', text: '运行中' }
    case 'SUSPENDED': return { color: '#fa8c16', text: '待审批' }
    case 'SUCCESS': return { color: '#95de64', text: '已完成' }
    case 'FAILED': return { color: '#ff4d4f', text: '失败' }
    default: return { color: '#bbb', text: '未运行' }
  }
}

/** 决策卡片（对齐原型 decision 形态：白底蓝边框气泡 + 批注 + 通过/驳回 + 完整审阅入口） */
function DecisionCard({
  m,
  onDecision,
}: {
  m: ChatMsg
  onDecision: (m: ChatMsg, decision: 'APPROVE' | 'REJECT', comment: string) => Promise<void>
}) {
  const navigate = useNavigate()
  const [comment, setComment] = useState('')
  const [busy, setBusy] = useState(false)
  const pending = m.decisionPhase === 'pending'
  const submit = async (decision: 'APPROVE' | 'REJECT') => {
    setBusy(true)
    try {
      await onDecision(m, decision, comment)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div style={{ display: 'flex', marginBottom: 14, paddingLeft: 36 }}>
      <div
        className="xj-bubble"
        style={{ background: '#fff', border: '1px solid #1677ff', borderBottomLeftRadius: 2 }}
      >
        ⚡ <b>等待审批 · 节点 {m.nodeId}</b> <Tag color="orange">决策门</Tag>
        <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', margin: '4px 0' }}>
          流程在节点 <code className="xj-mono">{m.nodeId}</code> 处暂停，审批后将从这里继续
        </div>
        {pending ? (
          <div>
            <Input.TextArea
              rows={2}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder="批注意见（驳回时必填，将写入决策审计）"
            />
            <Space style={{ marginTop: 8 }} wrap>
              <Button size="small" type="primary" loading={busy} onClick={() => void submit('APPROVE')}>
                通过，继续执行
              </Button>
              <Button size="small" danger disabled={!comment.trim()} loading={busy} onClick={() => void submit('REJECT')}>
                驳回（附批注）
              </Button>
              <Button size="small" type="link" style={{ padding: 0 }} onClick={() => navigate('/approval')}>
                完整审阅 →
              </Button>
            </Space>
          </div>
        ) : (
          <div style={{ marginTop: 4 }}>
            {m.decisionPhase === 'approved' && <Tag color="green">已通过 · 流程已续跑</Tag>}
            {m.decisionPhase === 'rejected' && <Tag color="red">已驳回 · 批注已入审计</Tag>}
          </div>
        )}
      </div>
    </div>
  )
}

/** 消息渲染（形态样式对齐原型 Msg 组件） */
function Msg({ m, onDecision }: { m: ChatMsg; onDecision: (m: ChatMsg, decision: 'APPROVE' | 'REJECT', comment: string) => Promise<void> }) {
  if (m.type === 'system') {
    return (
      <div style={{ textAlign: 'center', margin: '12px 0' }}>
        <span style={{
          display: 'inline-block', fontSize: 12, color: 'rgba(0,0,0,0.45)',
          background: '#f5f5f5', borderRadius: 10, padding: '2px 12px',
        }}>{m.text}</span>
      </div>
    )
  }
  if (m.type === 'user') {
    return (
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 14, gap: 8 }}>
        <div
          className="xj-bubble"
          style={{ background: '#1677ff', color: '#fff', borderBottomRightRadius: 2 }}
        >
          {m.text}
        </div>
        <div className="xj-avatar xj-avatar-user"><UserOutlined /></div>
      </div>
    )
  }
  if (m.type === 'tool') {
    // 对齐原型 tool 卡片（auto 形态绿底气泡）；缩进对齐 agent 气泡（头像 28 + gap 8）
    return (
      <div style={{ display: 'flex', marginBottom: 14, paddingLeft: 36 }}>
        <div
          className="xj-bubble"
          style={{ background: '#f6ffed', border: '1px solid #b7eb8f', borderBottomLeftRadius: 2 }}
        >
          🔧 <code className="xj-mono">{m.tool}</code>
          {m.toolState === 'running' && <Tag color="orange" style={{ marginLeft: 6 }}>调用中</Tag>}
          {m.toolState === 'success' && <Tag color="green" style={{ marginLeft: 6 }}>执行完成</Tag>}
          {m.toolState === 'error' && <Tag color="red" style={{ marginLeft: 6 }}>执行失败</Tag>}
          <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', marginTop: 2 }}>工具调用 · 授权策略随 HITL 实装启用</div>
        </div>
      </div>
    )
  }
  if (m.type === 'decision') {
    return <DecisionCard m={m} onDecision={onDecision} />
  }
  // agent / stage：头像 + 左侧浅灰气泡（白容器上的标准对比）
  return (
    <div style={{ display: 'flex', marginBottom: 14, gap: 8 }}>
      <div className="xj-avatar xj-avatar-agent"><RobotOutlined /></div>
      <div
        className="xj-bubble"
        style={{ background: '#f5f5f5', borderBottomLeftRadius: 2, whiteSpace: 'pre-wrap' }}
      >
        {m.agent && <Tag className="xj-mono" style={{ marginRight: 6 }}>{m.agent}</Tag>}
        <span>{m.text}</span>
        {m.streaming && <Spin size="small" style={{ marginLeft: 8 }} />}
      </div>
    </div>
  )
}

/** 对话主区（右侧）：状态栏 + 消息流 + 输入框 */
function ChatPanel({ ws, onStatusRefresh }: { ws: WsRow; onStatusRefresh: () => void }) {
  const [msgs, setMsgs] = useState<ChatMsg[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const idRef = useRef(0)

  useEffect(() => {
    setMsgs([])
    setInput('')
  }, [ws.id])

  useEffect(() => {
    if (boxRef.current) boxRef.current.scrollTop = boxRef.current.scrollHeight
  }, [msgs])

  const nextId = () => ++idRef.current

  /** WORKFLOW 绑定：输入内容异步触发流程，订阅 run 事件流实时渲染（暂停→审批卡片 / 完成→结果） */
  const runWorkflow = async (query: string) => {
    const runMsgId = nextId()
    setMsgs((x) => [
      ...x,
      { id: nextId(), type: 'user', text: query },
      { id: runMsgId, type: 'system', text: '流程已开始执行…', streaming: true },
    ])
    setBusy(true)
    try {
      const data = await apiJson<Record<string, unknown>>(`/api/workspaces/${ws.id}/runs`, 'POST', {
        input: { text: query },
      })
      const runId = String(data.runId || '')
      const status = String(data.status || '')
      if (status === 'RUNNING' && runId) {
        await watchRun(runId, runMsgId)
      } else {
        // 兼容同步返回（SUSPENDED/SUCCESS/FAILED）
        renderRunResult(runId, runMsgId, status, data)
      }
    } catch (e) {
      if (e instanceof Error) {
        setMsgs((x) => x.map((m) => (m.id === runMsgId ? { ...m, text: `执行失败：${e.message}`, streaming: false } : m)))
      }
    } finally {
      setBusy(false)
    }
  }

  /** 按 run 触发返回渲染结果消息（同步返回路径用；SSE 路径见 watchRun） */
  const renderRunResult = (runId: string, runMsgId: number, status: string, data: Record<string, unknown>) => {
    if (status === 'SUSPENDED') {
      const gateNodeId = String(data.gateNodeId ?? data.gate_node_id ?? '')
      setMsgs((x) => x.map((m) => (m.id === runMsgId ? { ...m, text: '流程已暂停，等待审批', streaming: false } : m)))
      setMsgs((x) => [...x, { id: nextId(), type: 'decision', runId, nodeId: gateNodeId, decisionPhase: 'pending' }])
    } else if (status === 'SUCCESS') {
      const out = data.output as { output?: unknown } | undefined
      const outText = out && typeof out.output === 'string' ? out.output : ''
      setMsgs((x) => x.map((m) => (
        m.id === runMsgId ? { ...m, text: outText ? `流程执行完成：${outText}` : '流程执行完成', streaming: false } : m
      )))
    } else {
      setMsgs((x) => x.map((m) => (m.id === runMsgId ? { ...m, text: `流程结束（${runStatusView(status).text}）`, streaming: false } : m)))
    }
    onStatusRefresh()
  }

  /**
   * 订阅 run 事件流（RunEventBus SSE，GET /api/runs/{id}/events）：
   * 先查一次详情（快流程 POST 返回时可能已终态），RUNNING 则等 run.finished 后拉详情渲染；
   * SUSPENDED 渲染决策卡片（gate_node_id 取自 run 详情）。
   */
  const watchRun = (runId: string, runMsgId: number) =>
    new Promise<void>((resolve) => {
      let settled = false
      let es: EventSource | null = null
      const settle = async () => {
        if (settled) return
        settled = true
        es?.close()
        try {
          const d = await apiJson<Record<string, unknown>>('/api/runs/' + runId)
          const status = String(d.status || '')
          if (status === 'SUSPENDED') {
            const gateNodeId = String(d.gate_node_id ?? '')
            setMsgs((x) => x.map((m) => (m.id === runMsgId ? { ...m, text: '流程已暂停，等待审批', streaming: false } : m)))
            setMsgs((x) => [...x, { id: nextId(), type: 'decision', runId, nodeId: gateNodeId, decisionPhase: 'pending' }])
          } else if (status === 'SUCCESS') {
            let outText = ''
            try {
              const out = typeof d.output_json === 'string' ? JSON.parse(d.output_json) : d.output_json
              outText = out && typeof out.output === 'string' ? out.output : ''
            } catch { /* 输出非 JSON 结构时退化为完成提示 */ }
            setMsgs((x) => x.map((m) => (
              m.id === runMsgId ? { ...m, text: outText ? `流程执行完成：${outText}` : '流程执行完成', streaming: false } : m
            )))
          } else {
            const err = d.error ? `：${String(d.error)}` : ''
            setMsgs((x) => x.map((m) => (m.id === runMsgId ? { ...m, text: `流程结束（${runStatusView(status).text}）${err}`, streaming: false } : m)))
          }
          onStatusRefresh()
        } catch (e) {
          setMsgs((x) => x.map((m) => (m.id === runMsgId ? { ...m, text: `结果获取失败：${e instanceof Error ? e.message : String(e)}`, streaming: false } : m)))
          onStatusRefresh()
        }
        resolve()
      }
      void apiJson<Record<string, unknown>>('/api/runs/' + runId).then((d) => {
        if (String(d.status || '') === 'RUNNING') {
          es = new EventSource('/api/runs/' + runId + '/events')
          es.addEventListener('run.finished', () => { void settle() })
          es.onerror = () => { window.setTimeout(() => { void settle() }, 300) }
        } else {
          void settle()
        }
      }).catch(() => { void settle() })
    })

  /** 决策提交（决策卡片 → /api/decisions）：APPROVE 续跑 / REJECT 保持挂起 */
  const decide = async (m: ChatMsg, decision: 'APPROVE' | 'REJECT', comment: string) => {
    try {
      const data = await apiJson<Record<string, unknown>>('/api/decisions', 'POST', {
        runId: m.runId,
        nodeId: m.nodeId,
        decision,
        comment,
      })
      setMsgs((x) => x.map((mm) => (
        mm.id === m.id ? { ...mm, decisionPhase: decision === 'APPROVE' ? 'approved' : 'rejected' } : mm
      )))
      const status = String(data.status || '')
      if (decision === 'APPROVE') {
        setMsgs((x) => [...x, {
          id: nextId(), type: 'system',
          text: status === 'SUCCESS' ? `续跑完成：${JSON.stringify(data.output ?? {})}` : `续跑结束：${status}`,
        }])
      } else {
        setMsgs((x) => [...x, { id: nextId(), type: 'system', text: '已驳回：流程保持暂停，你的批注已记录' }])
      }
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      onStatusRefresh()
    }
  }

  const send = async () => {
    const query = input.trim()
    if (!query || busy) return
    if (ws.binding_type === 'WORKFLOW') {
      setInput('')
      await runWorkflow(query)
      return
    }
    setInput('')
    const agentMsgId = nextId()
    setMsgs((x) => [
      ...x,
      { id: nextId(), type: 'user', text: query },
      { id: nextId(), type: 'system', text: '你的输入已作为流程的启动数据' },
      { id: agentMsgId, type: 'agent', agent: bindLabel(ws), text: '', streaming: true },
    ])
    setBusy(true)
    try {
      const r = await apiFetch(`/api/workspaces/${ws.id}/chat`, {
        method: 'POST',
        body: JSON.stringify({ query }),
      })
      if (!r.ok) {
        const data = await r.json().catch(() => ({}))
        throw new Error((data as { error?: string }).error || `请求失败 (${r.status})`)
      }
      await readSse(r, (event, data) => {
        let payload: Record<string, unknown> = {}
        try { payload = JSON.parse(data) } catch { /* 忽略非 JSON 帧 */ }
        if (event === 'agent.delta' && typeof payload.delta === 'string') {
          const delta = payload.delta
          setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, text: (m.text || '') + delta } : m)))
        } else if (event === 'agent.result' && typeof payload.text === 'string') {
          const text = payload.text
          setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, text: m.text || text, streaming: false } : m)))
        } else if (event === 'tool.call') {
          // 工具调用卡片（对齐原型 tool 形态）：插入 agent 消息之后
          setMsgs((x) => [
            ...x,
            {
              id: nextId(), type: 'tool',
              tool: String(payload.tool || ''), toolCallId: String(payload.toolCallId || ''),
              toolState: 'running',
            },
          ])
        } else if (event === 'tool.result') {
          const callId = String(payload.toolCallId || '')
          const ok = String(payload.state || '') === 'SUCCESS'
          setMsgs((x) => x.map((m) => (
            m.type === 'tool' && m.toolCallId === callId ? { ...m, toolState: ok ? 'success' : 'error' } : m
          )))
        } else if (event === 'error') {
          message.error(String(payload.message || '执行出错'))
        } else if (event === 'stage.finish') {
          setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, streaming: false } : m)))
        }
      })
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setBusy(false)
      setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, streaming: false } : m)))
    }
  }

  /** 输入框键盘：Enter 发送 / Shift+Enter 换行（IME 组合中不触发） */
  const onInputKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      void send()
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div ref={boxRef} className="xj-chat-box" style={{ minHeight: 0 }}>
        {msgs.length === 0 ? (
          <div style={{ minHeight: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', textAlign: 'center' }}>
            <div className="xj-empty-icon"><ApartmentOutlined /></div>
            <div style={{ fontSize: 15, fontWeight: 600, color: 'rgba(0,0,0,0.88)' }}>输入内容以启动流程</div>
            <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', marginTop: 8, lineHeight: 1.9, maxWidth: 420 }}>
              在下方输入内容即可启动流程，输入会作为流程的起点数据
            </div>
          </div>
        ) : (
          msgs.map((m) => <Msg key={m.id} m={m} onDecision={decide} />)
        )}
      </div>
      <div className="xj-input-bar" style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
        <Input.TextArea
          style={{ flex: 1 }}
          variant="borderless"
          autoSize={{ minRows: 1, maxRows: 4 }}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onInputKeyDown}
          placeholder={ws.binding_type === 'WORKFLOW'
            ? '输入内容即启动流程；流程暂停时可直接在此审批（Enter 发送 · Shift+Enter 换行）'
            : '输入内容启动或推进流程（Enter 发送 · Shift+Enter 换行）'}
        />
        <Button type="primary" icon={<SendOutlined />} loading={busy} disabled={!input.trim()} onClick={() => void send()}>
          发送
        </Button>
      </div>
    </div>
  )
}

/** 工作区信息 Drawer：展示工作目录与绑定目标，并提供编辑入口 */
function BindDrawer({ ws, open, onClose, onEdit }: {
  ws: WsRow; open: boolean; onClose: () => void; onEdit: () => void
}) {
  return (
    <Drawer title="工作区信息" width={520} open={open} onClose={onClose}>
      <Space direction="vertical" size={14} style={{ width: '100%' }}>
        <Card size="small" title="工作目录">
          <Space direction="vertical" style={{ width: '100%' }}>
            <div>
              <span style={{ fontSize: 12, color: '#888' }}>名称：</span>
              <Tag color="purple">{ws.name}</Tag>
            </div>
            <div>
              <span style={{ fontSize: 12, color: '#888' }}>路径：</span>
              <Tag className="xj-mono" color="blue">{ws.root_path}</Tag>
            </div>
          </Space>
        </Card>
        <Card size="small" title="绑定目标">
          <div>
            <span style={{ fontSize: 12, color: '#888' }}>当前：</span>
            <Tag color={ws.binding_type === 'AGENT' ? 'cyan' : 'geekblue'}>{bindLabel(ws)}</Tag>
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            流程执行使用绑定时固定的版本，更换绑定不影响已有执行记录。
          </Typography.Text>
        </Card>
        <Button type="primary" block onClick={onEdit}>编辑名称与绑定</Button>
      </Space>
    </Drawer>
  )
}

/** 嵌入分享 Modal（原型 ShareModal：链接/iframe/Widget 三段；嵌入端点待实装，展示占位代码） */
function ShareModal({ ws, open, onClose }: { ws: WsRow; open: boolean; onClose: () => void }) {
  const url = `${location.origin}/embed/ws/${ws.id}`
  const iframe = `<iframe src="${url}" width="480" height="720" frameborder="0" loading="lazy"></iframe>`
  return (
    <Modal title={`嵌入分享 · ${ws.name}`} open={open} onCancel={onClose} footer={null} width={640}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="工作区可通过只读链接或 iframe 嵌入；对话气泡挂件（Chat Widget）属于应用能力，请在「应用管理」中为已发布 Agent 的应用生成。"
      />
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>分享链接（只读对话视图）</Typography.Paragraph>
      <Typography.Paragraph copyable className="xj-mono" style={{ fontSize: 12 }}>{url}</Typography.Paragraph>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }}>iframe 嵌入代码</Typography.Paragraph>
      <Typography.Paragraph
        copyable={{ text: iframe }}
        className="xj-mono"
        style={{ fontSize: 12, background: '#fafafa', border: '1px solid #f0f0f0', borderRadius: 6, padding: 8, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}
      >{iframe}</Typography.Paragraph>
    </Modal>
  )
}

/** 新建 / 编辑工作区：名称 + 绑定目标二选一（工作目录由后端按名称生成，用户不填路径） */
function CreateModal({ open, editing, onClose, onSaved }: {
  open: boolean
  editing: WsRow | null
  onClose: () => void
  onSaved: () => void
}) {
  const [form] = Form.useForm()
  const [bindType, setBindType] = useState<'AGENT' | 'WORKFLOW'>('AGENT')
  const [agents, setAgents] = useState<AssetOptionRow[]>([])
  const [workflows, setWorkflows] = useState<AssetOptionRow[]>([])
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open) return
    setBindType(editing?.binding_type ?? 'AGENT')
    form.setFieldsValue(editing
      ? { name: editing.name, bound_asset_id: editing.bound_asset_id }
      : { name: undefined, bound_asset_id: undefined })
    void apiJson<AssetOptionRow[]>('/api/agents')
      .then((list) => setAgents((list || []).filter((a) => a.status === 'PUBLISHED')))
      .catch(() => setAgents([]))
    void apiJson<AssetOptionRow[]>('/api/definitions')
      .then((list) => setWorkflows((list || []).filter((a) => a.status === 'PUBLISHED')))
      .catch(() => setWorkflows([]))
  }, [open, form])

  const submit = async () => {
    const v = await form.validateFields()
    setBusy(true)
    try {
      const body = { name: v.name, binding_type: bindType, bound_asset_id: v.bound_asset_id }
      await apiJson(editing ? '/api/workspaces/' + editing.id : '/api/workspaces',
        editing ? 'PUT' : 'POST', body)
      message.success(editing ? '工作区已更新' : '工作区已创建')
      onSaved()
      onClose()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setBusy(false)
    }
  }

  const options = bindType === 'AGENT' ? agents : workflows
  return (
    <Modal
      title={editing ? '编辑工作区' : '新建工作区'}
      open={open}
      onCancel={onClose}
      onOk={() => void submit()}
      okText={editing ? '保存' : '创建'}
      confirmLoading={busy}
      destroyOnClose
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="name"
          label="工作区名称"
          rules={[{ required: true, message: '请输入工作区名称' }]}
          extra="工作目录按名称自动生成，无需填写路径"
        >
          <Input placeholder="如 订单系统" />
        </Form.Item>
        <Form.Item label="绑定目标" extra="仅可选择已发布的 Agent 或工作流">
          <Segmented
            value={bindType}
            onChange={(v) => {
              setBindType(v as 'AGENT' | 'WORKFLOW')
              // 切换类型后原选择必然失效，清空避免提交到错误资产
              form.setFieldValue('bound_asset_id', undefined)
            }}
            options={[
              { label: 'Agent', value: 'AGENT' },
              { label: '工作流', value: 'WORKFLOW' },
            ]}
          />
        </Form.Item>
        <Form.Item
          name="bound_asset_id"
          label={bindType === 'AGENT' ? '选择 Agent' : '选择工作流'}
          rules={[{ required: true, message: '请选择绑定目标' }]}
        >
          <Select
            placeholder="请选择"
            options={options.map((o) => ({
              value: o.id,
              label: `${o.name || '未命名'} · v${o.current_version ?? o.version ?? 1}`,
            }))}
          />
        </Form.Item>
      </Form>
    </Modal>
  )
}

/** 主页面：左侧工作区卡片列表 + 右侧对话（对齐原型 App 布局） */
export default function Workspace() {
  const navigate = useNavigate()
  const [rows, setRows] = useState<WsRow[]>([])
  const [loading, setLoading] = useState(true)
  const [selId, setSelId] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [editRow, setEditRow] = useState<WsRow | null>(null)
  const [bindOpen, setBindOpen] = useState(false)
  const [shareOpen, setShareOpen] = useState(false)
  /** 各工作区最近 run 状态（WORKFLOW 绑定才有 run 概念） */
  const [runStates, setRunStates] = useState<Record<string, WsRunState>>({})

  const loadRunStates = useCallback(async (list: WsRow[]) => {
    const wfRows = list.filter((r) => r.binding_type === 'WORKFLOW')
    if (wfRows.length === 0) return
    const next: Record<string, WsRunState> = {}
    await Promise.all(wfRows.map(async (r) => {
      try {
        const runs = await apiJson<Array<Record<string, unknown>>>(`/api/runs?definitionId=${r.bound_asset_id}&limit=1`)
        const latest = runs && runs.length > 0 ? runs[0] : null
        if (latest) {
          next[r.id] = { runId: String(latest.id || ''), status: String(latest.status || '') }
        }
      } catch { /* 查询失败按无 run 处理 */ }
    }))
    setRunStates((prev) => ({ ...prev, ...next }))
  }, [])

  const load = useCallback(async () => {
    try {
      const data = await apiJson<WsRow[]>('/api/workspaces')
      setRows(data)
      setSelId((cur) => (cur && data.some((r) => r.id === cur) ? cur : data[0]?.id ?? null))
      void loadRunStates(data)
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setLoading(false)
    }
  }, [loadRunStates])

  useEffect(() => {
    void load()
  }, [load])

  const ws = rows.find((r) => r.id === selId) || null
  const wsRun = ws ? runStates[ws.id] : undefined
  const suspended = wsRun?.status === 'SUSPENDED'

  const remove = async (id: string) => {
    try {
      await apiJson(`/api/workspaces/${id}`, 'DELETE')
      message.success('已删除')
      void load()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    }
  }

  return (
    <div style={{ display: 'flex', gap: 12, height: '100%', minHeight: 0 }}>
      {/* 左：工作区卡片列表 */}
      <div style={{ width: 250, flexShrink: 0, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <Button type="dashed" block icon={<PlusOutlined />} style={{ marginBottom: 10 }} onClick={() => setCreateOpen(true)}>
          新建工作区
        </Button>
        <div style={{ flex: 1, overflowY: 'auto', minHeight: 0 }}>
          {loading ? (
            <div style={{ textAlign: 'center', padding: 32 }}><Spin /></div>
          ) : rows.length === 0 ? (
            <Empty description="暂无工作区" imageStyle={{ height: 48 }} style={{ marginTop: 32 }} />
          ) : (
            rows.map((w) => {
              const rs = runStates[w.id]
              const dot = runStatusView(rs?.status)
              return (
                <div
                  key={w.id}
                  className={'xj-ws-item' + (selId === w.id ? ' xj-ws-active' : '')}
                  onClick={() => setSelId(w.id)}
                >
                  <div style={{ display: 'flex', alignItems: 'center' }}>
                    <span style={{ fontWeight: 600, fontSize: 13, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{w.name}</span>
                    <Dropdown
                      menu={{
                        items: [
                          { key: 'edit', label: '编辑工作区' },
                          { key: 'del', label: '删除工作区', danger: true },
                        ],
                        onClick: ({ key }) => {
                          if (key === 'edit') setEditRow(w)
                          if (key === 'del') {
                            Modal.confirm({
                              title: `删除工作区「${w.name}」？`,
                              content: '删除后不可恢复（工作区目录文件不受影响）。',
                              okText: '删除',
                              okButtonProps: { danger: true },
                              cancelText: '取消',
                              onOk: () => void remove(w.id),
                            })
                          }
                        },
                      }}
                      trigger={['click']}
                    >
                      <Button
                        type="text"
                        size="small"
                        style={{ color: '#999', width: 22, height: 22, minWidth: 0 }}
                        onClick={(e) => e.stopPropagation()}
                      >
                        ⋯
                      </Button>
                    </Dropdown>
                  </div>
                  <div style={{ fontSize: 12, color: '#888', marginTop: 2 }}>
                    <span className="xj-dot" style={{ background: dot.color }} />
                    {dot.text}{rs ? '' : ' · 未运行'}
                  </div>
                  <div style={{ fontSize: 11, color: '#aaa', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={w.root_path}>
                    {w.root_path}
                  </div>
                  <div style={{ marginTop: 4 }}>
                    <Tag className="xj-mono" style={{ fontSize: 10 }}>{bindLabel(w)}</Tag>
                  </div>
                </div>
              )
            })
          )}
        </div>
        {/* 精简说明：一行小字替代常驻大 Alert（信息收进 Tooltip 与空态引导） */}
        <Tooltip title="绑定 Agent 或工作流后，输入内容即可启动流程，执行过程实时显示在对话中。">
          <div style={{ fontSize: 11, color: 'rgba(0,0,0,0.45)', marginTop: 8, lineHeight: 1.6, cursor: 'help' }}>
            绑定 Agent 或工作流 · 输入内容即启动
          </div>
        </Tooltip>
      </div>
      {/* 右：对话主区 */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        {ws ? (
          <>
            <Space style={{ marginBottom: 10 }} wrap>
              <Typography.Title level={5} style={{ margin: 0 }}>{ws.name}</Typography.Title>
              <Tag>{bindLabel(ws)}</Tag>
              <Tag>
                <span className="xj-dot" style={{ background: runStatusView(wsRun?.status).color }} />
                {runStatusView(wsRun?.status).text}
              </Tag>
              <span style={{ flex: 1 }} />
              <Button icon={<ShareAltOutlined />} onClick={() => setShareOpen(true)}>嵌入分享</Button>
              <Button icon={<LinkOutlined />} onClick={() => setBindOpen(true)}>工作区信息</Button>
              {wsRun && <Button type="primary" onClick={() => navigate('/runs')}>执行详情</Button>}
            </Space>
            {suspended && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 10 }}
                message={`流程已暂停，等待审批——可直接在下方处理，或打开审批中心查看完整产物`}
                action={
                  <Button size="small" type="primary" onClick={() => navigate('/approval')}>
                    打开审批中心
                  </Button>
                }
              />
            )}
            <div style={{ flex: 1, minHeight: 0 }}>
              <ChatPanel ws={ws} onStatusRefresh={() => void loadRunStates(rows)} />
            </div>
          </>
        ) : (
          <Card style={{ flex: 1 }}>
            {loading ? (
              <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
            ) : (
              <Empty description="暂无工作区，点击左侧「+ 新建工作区」创建" />
            )}
          </Card>
        )}
      </div>
      <CreateModal
        open={createOpen || !!editRow}
        editing={editRow}
        onClose={() => { setCreateOpen(false); setEditRow(null) }}
        onSaved={() => void load()}
      />
      {ws && (
        <BindDrawer
          ws={ws}
          open={bindOpen}
          onClose={() => setBindOpen(false)}
          onEdit={() => { setBindOpen(false); setEditRow(ws) }}
        />
      )}
      {ws && <ShareModal ws={ws} open={shareOpen} onClose={() => setShareOpen(false)} />}
    </div>
  )
}

