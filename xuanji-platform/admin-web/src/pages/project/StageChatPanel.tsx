import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Checkbox,
  Empty,
  Input,
  Modal,
  Space,
  Tag,
  Typography,
  Upload,
  message,
} from 'antd'
import {
  StopOutlined,
  ThunderboltOutlined,
  PaperClipOutlined,
  SendOutlined,
  LockOutlined,
  FileTextOutlined,
} from '@ant-design/icons'
import { apiFetch, apiJson } from '../../api/client'
import { STEP_STATUS_COLOR, STEP_STATUS_LABEL, StageExecution, StageStepRow } from './types'
import { MarkdownView } from './shared'

/** 附件（对话消息可带图片/文本附件） */
interface ChatAttachment {
  name: string
  mediaType?: string
  dataBase64?: string
  content?: string
  dataUrl?: string
}

/** 工具授权请求里的单个工具调用（agent.confirm 事件 toolCalls 元素） */
interface ConfirmToolCall {
  toolCallId: string
  tool: string
  input?: Record<string, unknown>
}

/** 工具授权卡数据（sessionId 用于恢复执行；done/approved 标记用户已处理） */
interface ConfirmCardData {
  sessionId: string
  toolCalls: ConfirmToolCall[]
  done?: boolean
  approved?: boolean
}

/** 阶段对话消息（user 右侧蓝底，agent 左侧白底，error 红底，queued 灰底待发送，process 过程留档卡片，confirm 工具授权卡） */
export interface StageChatMsg {
  id: number
  type: 'user' | 'agent' | 'error' | 'queued' | 'system' | 'process' | 'confirm' | 'chatlog'
  text: string
  attachments?: ChatAttachment[]
  /** agent 回复流式生成中 */
  streaming?: boolean
  /** 实时阶段提示（🤔 思考中… / 🔧 xxx 执行中…），流式正文开始后清空 */
  phaseText?: string
  /** 思考文本流（thinking.delta 实时追加，灰色小字展示） */
  thinking?: string
  /** 流开始时间戳（秒），状态行显示已耗时 */
  startedAt?: number
  /** process 消息的标题（如「讨论 #3·发言」「求助问答 #5·architect」） */
  title?: string
  /** confirm 消息的授权数据 */
  confirmData?: ConfirmCardData
}

async function readSse(res: Response, onEvent: (event: string, data: string) => void) {
  const reader = res.body?.getReader()
  if (!reader) return
  const dec = new TextDecoder()
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      let ev = 'message'
      const dataLines: string[] = []
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) ev = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (dataLines.length > 0) onEvent(ev, dataLines.join('\n'))
    }
  }
}
export function StageChatPanel({ projectId, exec, onJumpExec }: { projectId: string; exec?: StageExecution | null; onJumpExec?: () => void }) {
  const [msgs, setMsgs] = useState<StageChatMsg[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  // 流式期间的秒级重渲染驱动：状态行「已 Ns」靠它每秒刷新
  const [, setTick] = useState(0)
  const idRef = useRef(0)
  const nextId = () => ++idRef.current
  const listRef = useRef<HTMLDivElement>(null)
  // 多轮交流：busy 时输入不锁——Enter 入队 / Ctrl+Enter 插队，当前轮结束自动发队首。
  // 队列用 ref 存（同步读写），queueLen 只做渲染镜像。
  const queueRef = useRef<{ id: number; text: string; attachments?: ChatAttachment[] }[]>([])
  const [queueLen, setQueueLen] = useState(0)
  const abortRef = useRef<AbortController | null>(null)
  const busyRef = useRef(false)
  // 授权卡「本会话记住」勾选状态（ref 即时读写，渲染时读当前值）
  const rememberRef = useRef(false)
  // 决策门禁：有未处理的 DECISION 步（未 DONE/SKIPPED）时锁定对话输入——
  // 决策必须在项目区域（右侧）处理，答复是唯一解锁通道，避免对话答复与决策卡两张皮
  const pendingDecisions = (exec?.steps || []).filter(
    (s) => s.stepType === 'DECISION' && s.status !== 'DONE' && s.status !== 'SKIPPED'
  )
  const inputLocked = pendingDecisions.length > 0
  // 对话附件（V29）：图片走多模态、文本文件走内容拼接；截图可直接 Ctrl+V 粘贴
  const [files, setFiles] = useState<ChatAttachment[]>([])

  const TEXT_EXT = /\.(md|txt|json|java|xml|yml|yaml|csv|log|sql|ts|tsx|js|jsx|py|sh|html|css|vue|properties|conf|ini)$/i

  const addFile = (f: File) => {
    if (files.length + queueRef.current.reduce((n, q) => n + (q.attachments?.length || 0), 0) >= 4) {
      message.warning('附件最多 4 个/轮')
      return
    }
    if (f.type.startsWith('image/')) {
      if (f.size > 5 * 1024 * 1024) { message.warning(`图片 ${f.name || '截图'} 超过 5MB`); return }
      const reader = new FileReader()
      reader.onload = () => {
        const dataUrl = String(reader.result || '')
        setFiles((x) => [...x, {
          name: f.name || '截图.png', mediaType: f.type,
          dataBase64: dataUrl.split(',')[1] || '', dataUrl,
        }])
      }
      reader.readAsDataURL(f)
      return
    }
    if (!TEXT_EXT.test(f.name)) {
      message.warning('仅支持图片与文本类文件（md/txt/代码/配置等）')
      return
    }
    if (f.size > 150 * 1024) { message.warning(`文本附件 ${f.name} 超过 150KB`); return }
    const reader = new FileReader()
    reader.onload = () => setFiles((x) => [...x, { name: f.name, content: String(reader.result || '') }])
    reader.readAsText(f)
  }

  /** 粘贴截图：clipboard 里的图片直接进附件区 */
  const onPaste = (e: React.ClipboardEvent) => {
    const items = e.clipboardData?.items
    if (!items) return
    for (const it of Array.from(items)) {
      if (it.kind === 'file' && it.type.startsWith('image/')) {
        const f = it.getAsFile()
        if (f) { e.preventDefault(); addFile(f) }
        return
      }
    }
  }

  // AI 过程可见化：步骤状态变化以系统消息进对话流（讨论完成/任务完成/失败/等待答复），
  // 进行中的步骤由下方「活跃步骤条」实时展示——后台跑几分钟时用户不再干等。
  // 过程内容可见化（讨论发言/求助问答）：跟踪 outputFull 长度增量，新发言/新问答以
  // process 卡片进对话流——用户在聊天框直接看到 agent 们在说什么、问了什么，不用切去执行 tab。
  const prevStepsRef = useRef<Map<number, { status: string; fullLen: number }>>(new Map())
  /** process 卡片展开状态（默认收起，只显示开头一段） */
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const toggleExpanded = (id: number) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  /** 发言/问答文本 → markdown：【说话人】加粗，段落保留 */
  const renderSpeech = (raw: string): string =>
    raw.replace(/^【([^】]+)】/gm, '**【$1】**').trim()

  /** 讨论纪要里的汇总段（===== 汇总 ===== 之后）；无分隔符返回全文 */
  const summaryPart = (full: string): string => {
    const idx = full.indexOf('===== 汇总 =====')
    return idx >= 0 ? full.slice(idx + '===== 汇总 ====='.length).trim() : full.trim()
  }

  useEffect(() => {
    const steps = exec?.steps || []
    const prev = prevStepsRef.current
    const next = new Map<number, { status: string; fullLen: number }>()
    const events: string[] = []
    const processMsgs: { title: string; text: string }[] = []
    for (const s of steps) {
      const full = s.outputFull || ''
      const before = prev.get(s.id)
      next.set(s.id, { status: s.status, fullLen: full.length })
      if (!before) {
        // 首次见到的步骤：内容由历史回放负责，但「进行中」要即时提示——
        // 讨论刚发起时发言还没落库（qwen 单次 2-3 分钟），不提示的话聊天框毫无动静
        if (s.status === 'EXECUTING' && s.stepType === 'DISCUSS') {
          events.push(`讨论 #${s.seq} ${s.name} 进行中${(s.participants || []).length ? `（${(s.participants || []).join('、')}）` : ''}，发言将实时显示在这里`)
        } else if (s.status === 'EXECUTING' && s.stepType === 'CONSULT') {
          events.push(`求助 #${s.seq} ${s.name} 进行中，答复后将显示在这里`)
        }
        continue
      }
      if (before.status !== s.status) {
        if (s.status === 'EXECUTING') {
          events.push(`⚙ #${s.seq} ${s.name} 开始执行${(s.participants || []).length ? `（${(s.participants || []).join('、')}）` : ''}`)
        } else if (s.status === 'DONE' && s.stepType !== 'DISCUSS' && s.stepType !== 'CONSULT') {
          events.push(`✅ #${s.seq} ${s.name} 完成${s.outputSummary ? `：${s.outputSummary.slice(0, 100)}` : ''}`)
        } else if (s.status === 'FAILED') {
          events.push(`❌ #${s.seq} ${s.name} 失败：${(s.error || '').slice(0, 100)}`)
        } else if (s.status === 'SKIPPED') {
          events.push(`⏭ #${s.seq} ${s.name} 已跳过：${(s.skipReason || '').slice(0, 80)}`)
        } else if (s.status === 'WAITING') {
          events.push(`⏸ #${s.seq} ${s.name} 等待你的答复：${(s.instruction || '').slice(0, 100)}`)
        }
      }
      // 讨论进行中：新落库的发言增量进对话流
      if (s.stepType === 'DISCUSS' && full.length > before.fullLen) {
        const delta = full.slice(before.fullLen, full.length)
        if (delta.trim()) processMsgs.push({ title: `讨论 #${s.seq} ${s.name}·新发言`, text: renderSpeech(delta) })
      }
      // 讨论完成：汇总进对话流（发言已按增量推过，只推汇总段）
      if (s.stepType === 'DISCUSS' && s.status === 'DONE' && before.status !== 'DONE') {
        const sum = summaryPart(full)
        if (sum) processMsgs.push({ title: `讨论汇总 #${s.seq} ${s.name}`, text: renderSpeech(sum) })
      }
      // 求助问答完成：问答全文进对话流
      if (s.stepType === 'CONSULT' && s.status === 'DONE' && before.status !== 'DONE') {
        if (full.trim()) processMsgs.push({ title: `求助问答 #${s.seq} ${s.name}`, text: renderSpeech(full) })
      }
    }
    prevStepsRef.current = next
    if (events.length > 0 || processMsgs.length > 0) {
      setMsgs((x) => [
        ...x,
        ...(events.length > 0 ? [{ id: nextId(), type: 'system' as const, text: events.join('\n') }] : []),
        ...processMsgs.map((p) => ({ id: nextId(), type: 'process' as const, title: p.title, text: p.text })),
      ])
    }
  }, [exec?.steps])
  const stageText = exec?.stage_label || '当前阶段'
  const chatStage = exec?.stage || ''
  const chatIteration = exec?.iteration || 1

  // 进入/切阶段时回放该阶段会话的历史：agent 侧记忆一直存在 agent_state 里，
  // 只是此前没有读取通道，刷新即空白。会话粒度=阶段，阶段推进后 exec.stage 变化自动换新会话。
  // 卸载时中止进行中的 SSE 流：返回列表页后连接继续读到流结束是泄漏
  useEffect(() => () => { abortRef.current?.abort() }, [])
  useEffect(() => {
    if (!chatStage) return
    // 切阶段：中止旧阶段还在跑的流（否则输入框卡「运行中」直到旧流结束，停止按钮 abort 的也是旧流），
    // 并丢弃未发送的队列（会话都换了，队列没有意义）
    abortRef.current?.abort()
    abortRef.current = null
    queueRef.current = []
    setQueueLen(0)
    setMsgs((x) => x.filter((m) => m.type !== 'queued'))
    let alive = true
    apiJson<{ messages?: { role?: string; text?: string }[] }>(
      `/api/projects/${projectId}/stage/chat/history?stage=${chatStage}&iteration=${chatIteration}`,
    ).then((r) => {
      if (!alive) return
      const hist = (r?.messages || [])
        .filter((m) => (m.text || '').trim())
        .map((m) => ({
          id: nextId(),
          type: (m.role === 'USER' ? 'user' : 'agent') as 'user' | 'agent',
          text: m.text || '',
        }))
      // 过程留档回放：讨论发言与求助问答落在 project_stage_step（不在 agent_state 会话），
      // 单独拉 stage-execution 补齐——刷新后聊天框仍能看到 agent 们讨论了什么、问了什么
      apiJson<{ steps?: StageStepRow[] }>(`/api/projects/${projectId}/stage-execution`)
        .then((ex) => {
          if (!alive) return
          const proc = (ex?.steps || [])
            .filter((s) => (s.stepType === 'DISCUSS' || s.stepType === 'CONSULT') && (s.outputFull || '').trim())
            .sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
            .flatMap((s) => {
              const label = s.stepType === 'DISCUSS'
                ? `讨论纪要 #${s.seq} ${s.name}${s.status === 'FAILED' ? '·中断' : ''}`
                : `求助问答 #${s.seq} ${s.name}`
              return [{ id: nextId(), type: 'process' as const, title: label, text: renderSpeech(s.outputFull || '') }]
            })
          const divider = proc.length > 0
            ? [{ id: nextId(), type: 'system' as const, text: '── 以下为讨论与求助留档 ──' }]
            : []
          // 对话过程留档（docs/{stage}/iter{n}/*-CHATLOG-*.md）：思考与工具轨迹的历史回放通道
          apiJson<{ files?: { path: string; type: string }[] }>(`/api/projects/${projectId}/files`)
            .then((fl) => {
              if (!alive) return
              const prefix = `docs/${chatStage}/iter${chatIteration}/`
              const logs = (fl?.files || [])
                .filter((f) => f.type === 'file' && f.path.startsWith(prefix) && f.path.includes('-CHATLOG-'))
                .map((f) => f.path)
                .sort()
              const logMsgs = logs.map((path) => ({ id: nextId(), type: 'chatlog' as const, text: path }))
              const logDivider = logMsgs.length > 0
                ? [{ id: nextId(), type: 'system' as const, text: '── 以下为对话过程留档（含思考与工具轨迹） ──' }]
                : []
              if (hist.length || divider.length || logMsgs.length) {
                setMsgs([...hist, ...divider, ...proc, ...logDivider, ...logMsgs])
              }
            })
            .catch(() => { if (hist.length || divider.length) setMsgs([...hist, ...divider, ...proc]) })
        })
        .catch(() => { if (hist.length) setMsgs(hist) })
    }).catch(() => { /* 历史取不到不打断对话主流程 */ })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, chatStage, chatIteration])

  // 新内容自动滚到底：流式回答时不滚的话用户要自己拖着看
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [msgs])

  /** 过程留档查看：拉文件内容弹 Modal（≤200KB 文本，轨迹文件远小于此） */
  const [chatLogView, setChatLogView] = useState<{ path: string; content: string } | null>(null)
  const openChatLog = async (relPath: string) => {
    try {
      const r = await apiJson<{ content?: string }>(
        `/api/projects/${projectId}/files/content?path=${encodeURIComponent(relPath)}`,
      )
      setChatLogView({ path: relPath, content: r?.content || '（空）' })
    } catch {
      message.error('读取过程留档失败')
    }
  }

  /** 工具授权决定：批准/拒绝（可勾选本会话记住）→ 恢复执行 → 渲染恢复期间事件 */
  const handleConfirm = async (msgId: number, approved: boolean, remember: boolean) => {
    const card = msgs.find((m) => m.id === msgId)
    if (!card?.confirmData || card.confirmData.done) return
    const { sessionId, toolCalls } = card.confirmData
    setMsgs((x) => x.map((m) => (m.id === msgId
      ? { ...m, confirmData: { ...m.confirmData!, done: true, approved } } : m)))
    try {
      const r = await apiFetch(`/api/projects/${projectId}/stage/chat/confirm`, {
        method: 'POST',
        body: JSON.stringify({
          sessionId,
          decisions: toolCalls.map((tc) => ({
            toolCallId: tc.toolCallId, tool: tc.tool, approved, rememberSession: remember,
          })),
        }),
      })
      if (!r.ok) {
        const data = await r.json().catch(() => ({})) as { message?: string; error?: string }
        throw new Error(data.message || data.error || `请求失败 (${r.status})`)
      }
      const data = await r.json() as { events?: Array<{ type: string; delta?: string; text?: string; meta?: Record<string, unknown> }> }
      // 恢复执行期间的事件：delta/result 拼一条 agent 消息，tool.call/result 记过程行
      const agentId = nextId()
      let buf = ''
      let sawText = false
      const procLines: string[] = []
      for (const evt of data.events || []) {
        if (evt.type === 'agent.delta' && typeof evt.delta === 'string') {
          buf += evt.delta
          sawText = true
        } else if (evt.type === 'agent.result' && typeof evt.text === 'string') {
          if (!sawText && evt.text) buf = evt.text
        } else if (evt.type === 'tool.call') {
          procLines.push(`🔧 ${String(evt.meta?.tool || '')} 执行中…`)
        } else if (evt.type === 'tool.result') {
          procLines.push(`🔧 ${String(evt.meta?.tool || '')} 完成`)
        } else if (evt.type === 'error') {
          procLines.push(`⚠️ ${evt.text || '执行出错'}`)
        }
      }
      setMsgs((x) => {
        const extra: StageChatMsg[] = []
        if (procLines.length) {
          extra.push({ id: nextId(), type: 'process', title: '🔧 工具执行', text: procLines.join('\n') })
        }
        if (buf.trim()) {
          extra.push({ id: agentId, type: 'agent', text: buf })
        }
        return [...x, ...extra]
      })
    } catch (e) {
      setMsgs((x) => [...x, {
        id: nextId(), type: 'error', text: e instanceof Error ? e.message : String(e),
      }])
    }
  }

  /** 发送一条（q 已在 msgs 里以 queued 形态占位）：SSE 流式回填，停止走 AbortController */
  const sendMsg = async (q: { id: number; text: string; attachments?: ChatAttachment[] }) => {
    const agentMsgId = nextId()
    const startedAt = Math.floor(Date.now() / 1000)
    setMsgs((x) => [
      ...x.map((m) => (m.id === q.id ? { ...m, type: 'user' as const } : m)),
      { id: agentMsgId, type: 'agent', text: '', streaming: true, startedAt, phaseText: '🤔 思考与规划中…' },
    ])
    busyRef.current = true
    setBusy(true)
    const tickTimer = window.setInterval(() => setTick((v) => v + 1), 1000)
    const ac = new AbortController()
    abortRef.current = ac
    // 工具执行过程实时卡片：PM 对话要跑 plan_steps/declare_requirements 等编排工具，
    // qwen 每轮生成 1-3 分钟，无过程显示时用户只看到「正在处理…」干等（实测反馈）
    const toolLines: string[] = []
    let procMsgId = 0
    const pushToolLine = (line: string) => {
      toolLines.push(line)
      if (!procMsgId) {
        procMsgId = nextId()
        setMsgs((x) => [...x, { id: procMsgId, type: 'process', title: '🔧 工具执行', text: toolLines.join('\n') }])
      } else {
        setMsgs((x) => x.map((m) => (m.id === procMsgId ? { ...m, text: toolLines.join('\n') } : m)))
      }
    }
    try {
      const r = await apiFetch(`/api/projects/${projectId}/stage/chat`, {
        method: 'POST',
        body: JSON.stringify({
          message: q.text,
          ...(q.attachments?.length ? {
            attachments: q.attachments.map((a) => ({
              name: a.name, media_type: a.mediaType, data_base64: a.dataBase64, content: a.content,
            })),
          } : {}),
        }),
        signal: ac.signal,
      })
      if (!r.ok) {
        const data = await r.json().catch(() => ({})) as { message?: string; error?: string }
        throw new Error(data.message || data.error || `请求失败 (${r.status})`)
      }
      let receivedEnd = false
      await readSse(r, (event, data) => {
        let payload: Record<string, unknown> = {}
        try { payload = JSON.parse(data) } catch { /* 忽略非 JSON 帧 */ }
        if (event === 'agent.start') {
          const who = String(payload.agent || 'agent')
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, text: m.text || `（${who} 正在处理…）` } : m)))
        } else if (event === 'llm.start') {
          // 每轮 LLM 调用开始（qwen 生成 tool_call 参数期间无文本 delta，靠这个让用户知道 AI 在思考）
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, phaseText: '🤔 思考与规划中…' } : m)))
        } else if (event === 'thinking.delta' && typeof payload.delta === 'string') {
          const th = payload.delta
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, thinking: (m.thinking || '') + th, phaseText: '🤔 思考中…' } : m)))
        } else if (event === 'agent.delta' && typeof payload.delta === 'string') {
          const delta = payload.delta
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, text: (m.text || '') + delta, phaseText: undefined } : m)))
        } else if (event === 'agent.result' && typeof payload.text === 'string') {
          // 只有还没流式出正文时才用整段结果兜底，否则会把已流出的内容重复一遍
          const full = payload.text
          setMsgs((x) => x.map((m) => (m.id === agentMsgId && (!m.text || m.text.startsWith('（'))
            ? { ...m, text: full } : m)))
        } else if (event === 'tool.call') {
          const tool = String(payload.tool || '')
          pushToolLine(`🔧 ${tool} 执行中…`)
          setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, phaseText: `🔧 ${tool} 执行中…` } : m)))
        } else if (event === 'tool.result') {
          const tool = String(payload.tool || '')
          const line = `✅ ${tool} ${payload.state === 'ERROR' ? '失败' : '完成'}`
          const idx = toolLines.lastIndexOf(`🔧 ${tool} 执行中…`)
          if (idx >= 0) toolLines[idx] = line
          else toolLines.push(line)
          if (procMsgId) {
            setMsgs((x) => x.map((m) => (m.id === procMsgId ? { ...m, text: toolLines.join('\n') } : m)))
          } else {
            procMsgId = nextId()
            setMsgs((x) => [...x, { id: procMsgId, type: 'process', title: '🔧 工具执行', text: toolLines.join('\n') }])
          }
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, phaseText: `✅ ${tool} ${payload.state === 'ERROR' ? '失败' : '完成'}，继续…` } : m)))
        } else if (event === 'agent.confirm' && Array.isArray(payload.toolCalls)) {
          // 工具授权请求：流到此结束，实例挂起等待用户决定——推授权卡
          const calls = (payload.toolCalls as Array<Record<string, unknown>>).map((tc) => ({
            toolCallId: String(tc.toolCallId || ''),
            tool: String(tc.tool || ''),
            input: (tc.input || {}) as Record<string, unknown>,
          }))
          setMsgs((x) => [...x, {
            id: nextId(), type: 'confirm', text: '',
            confirmData: { sessionId: String(payload.sessionId || ''), toolCalls: calls },
          }])
        } else if (event === 'agent.end' || event === 'stage.finish') {
          receivedEnd = true
          window.clearInterval(tickTimer)
          setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, streaming: false, phaseText: undefined } : m)))
        } else if (event === 'error') {
          setMsgs((x) => [...x, {
            id: nextId(), type: 'error', text: String(payload.message || '执行出错'),
          }])
        }
      })
      if (!receivedEnd) {
        // 流结束但没收到 end 帧：SSE 超时/网络中断——后台任务会继续跑完并留档，别让占位永远挂着
        window.clearInterval(tickTimer)
        setMsgs((x) => x.map((m) => (m.id === agentMsgId
          ? { ...m, streaming: false, phaseText: undefined,
              text: (m.text || '') + '\n\n（⚠ 连接中断——后台任务会继续执行并留档，稍后刷新页面查看结果）' } : m)))
      }
    } catch (e) {
      if (ac.signal.aborted) {
        // 用户主动停止：不是错误。后台 agent 会跑完并照常留档，只是前端不再接收
        setMsgs((x) => x.map((m) => (m.id === agentMsgId
          ? { ...m, streaming: false, text: (m.text || '') + '\n\n（已停止接收，后台任务会完成并留档）' } : m)))
      } else {
        setMsgs((x) => [...x, {
          id: nextId(), type: 'error', text: e instanceof Error ? e.message : String(e),
        }])
      }
    } finally {
      busyRef.current = false
      setBusy(false)
      abortRef.current = null
      setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, streaming: false } : m)))
      // 队首自动开跑：多轮交流的接力点
      const next = queueRef.current.shift()
      setQueueLen(queueRef.current.length)
      if (next) void sendMsg(next)
    }
  }

  /** 提交输入：空闲直接发；运行中 Enter 入队 / Ctrl+Enter 插队 */
  const submit = (jump: boolean) => {
    const query = input.trim()
    if (!query && files.length === 0) return
    const atts = files.length ? [...files] : undefined
    const q = { id: nextId(), text: query || '（见附件）', attachments: atts }
    setInput('')
    setFiles([])
    setMsgs((x) => [...x, { id: q.id, type: 'queued', text: q.text, attachments: atts }])
    if (!busyRef.current) {
      void sendMsg(q)
    } else if (jump) {
      queueRef.current.unshift(q)
      setQueueLen(queueRef.current.length)
    } else {
      queueRef.current.push(q)
      setQueueLen(queueRef.current.length)
    }
  }

  /** 撤回还没发送的排队消息 */
  const revoke = (id: number) => {
    queueRef.current = queueRef.current.filter((q) => q.id !== id)
    setQueueLen(queueRef.current.length)
    setMsgs((x) => x.filter((m) => m.id !== id))
  }

  /** 停止接收当前流（后台 agent 会跑完并留档） */
  const stop = () => abortRef.current?.abort()

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit(e.ctrlKey || e.metaKey)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>

      {/* 活跃步骤条：后台执行中的小流程实时可见（讨论已收集多少发言/任务跑多久），不再空等 */}
      {(() => {
        const active = (exec?.steps || []).filter((s) => s.status === 'EXECUTING' || s.status === 'WAITING')
        if (active.length === 0) return null
        return (
          <div style={{ padding: '6px 10px', background: '#e6f4ff', borderRadius: 6, border: '1px solid #91caff' }}>
            {active.map((s) => (
              <div key={s.id} style={{ fontSize: 12, lineHeight: '20px' }}>
                <Tag color={STEP_STATUS_COLOR[s.status] || 'processing'} style={{ marginInlineEnd: 6 }}>
                  {STEP_STATUS_LABEL[s.status] || s.status}
                </Tag>
                <Typography.Text style={{ fontSize: 12 }}>#{s.seq} {s.name}</Typography.Text>
                {(s.participants || []).length > 0 && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {' '}（{(s.participants || []).join('、')}）
                  </Typography.Text>
                )}
                {s.outputFull && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {' '}· 已收集 {s.outputFull.length} 字
                  </Typography.Text>
                )}
              </div>
            ))}
          </div>
        )
      })()}

      <div style={{ position: 'relative' }}>
        {msgs.length > 0 && (
          <Button
            size="small" type="text"
            style={{ position: 'absolute', top: 4, right: 8, zIndex: 1, fontSize: 12, color: 'rgba(0,0,0,0.45)' }}
            onClick={() => setMsgs([])}
          >
            清空显示
          </Button>
        )}
        <div
          ref={listRef}
          style={{
            maxHeight: 'calc(100vh - 340px)', minHeight: 320, overflowY: 'auto', padding: '10px 12px',
            background: '#fafafa', borderRadius: 6, border: '1px solid #f0f0f0',
          }}
        >
        {msgs.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={`向${stageText}提问，回复流式展示；最终产出会自动登记为交付物留档`}
            style={{ padding: 24 }}
          />
        ) : (
          msgs.map((m) => (
            <div key={m.id} style={{
              marginBottom: 10,
              textAlign: m.type === 'user' ? 'right' : m.type === 'system' ? 'center' : 'left',
            }}>
              {m.type === 'confirm' && m.confirmData ? (
                <div style={{
                  maxWidth: '94%', padding: '10px 14px', borderRadius: 8, textAlign: 'left',
                  background: '#fffbe6', border: '1px solid #ffe58f',
                }}>
                  <div style={{ fontWeight: 600, marginBottom: 6 }}>
                    <LockOutlined style={{ marginInlineEnd: 6 }} />
                    AI 请求使用工具：{m.confirmData.toolCalls.map((tc) => tc.tool).join('、')}
                  </div>
                  {m.confirmData.toolCalls.map((tc) => (
                    <div key={tc.toolCallId} style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>
                      <code style={{ wordBreak: 'break-all' }}>{JSON.stringify(tc.input || {}).slice(0, 200)}</code>
                    </div>
                  ))}
                  {m.confirmData.done ? (
                    <Tag color={m.confirmData.approved ? 'green' : 'red'}>
                      {m.confirmData.approved ? '已批准' : '已拒绝'}
                    </Tag>
                  ) : (
                    <>
                      <Space size={8} style={{ marginTop: 4 }}>
                        <Button type="primary" size="small" onClick={() => void handleConfirm(m.id, true, rememberRef.current)}>
                          批准执行
                        </Button>
                        <Button size="small" danger onClick={() => void handleConfirm(m.id, false, false)}>
                          拒绝
                        </Button>
                      </Space>
                      <Checkbox
                        style={{ marginLeft: 10, fontSize: 12 }}
                        checked={rememberRef.current}
                        onChange={(e) => { rememberRef.current = e.target.checked }}
                      >
                        本会话记住（该项目会话内不再询问）
                      </Checkbox>
                    </>
                  )}
                </div>
              ) : m.type === 'chatlog' ? (
                <div
                  style={{
                    maxWidth: '94%', padding: '6px 12px', borderRadius: 8, textAlign: 'left',
                    background: '#e6f4ff', border: '1px solid #91caff', cursor: 'pointer',
                    fontSize: 13, color: '#0958d9',
                  }}
                  onClick={() => openChatLog(m.text)}
                >
                  <FileTextOutlined style={{ marginInlineEnd: 6 }} />
                  对话过程留档 · {m.text.split('/').pop()?.replace(/^\d+-CHATLOG-/, '').replace(/\.md$/, '')}（点击查看思考与工具轨迹）
                </div>
              ) : m.type === 'process' ? (
                <div style={{
                  maxWidth: '94%', padding: '8px 12px', borderRadius: 8, textAlign: 'left',
                  background: '#f9f0ff', border: '1px solid #d3adf7',
                }}>
                  <div
                    style={{ cursor: 'pointer', fontSize: 12, fontWeight: 600, color: '#531dab' }}
                    onClick={() => toggleExpanded(m.id)}
                  >
                    {expanded.has(m.id) ? '▾ ' : '▸ '}{m.title}
                  </div>
                  <div style={{
                    marginTop: 4, fontSize: 13,
                    maxHeight: expanded.has(m.id) ? 'none' : 120,
                    overflow: 'hidden',
                    maskImage: expanded.has(m.id) ? 'none' : 'linear-gradient(to bottom, #000 70%, transparent)',
                    WebkitMaskImage: expanded.has(m.id) ? 'none' : 'linear-gradient(to bottom, #000 70%, transparent)',
                  }}>
                    <MarkdownView scroll={false} text={m.text} />
                  </div>
                  {!expanded.has(m.id) && (
                    <Button type="link" size="small" style={{ padding: 0, fontSize: 12 }} onClick={() => toggleExpanded(m.id)}>
                      展开全文
                    </Button>
                  )}
                </div>
              ) : (
              <div
                style={{
                  display: 'inline-block',
                  maxWidth: m.type === 'user' ? '80%' : '94%',
                  padding: '6px 12px',
                  borderRadius: 8,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  textAlign: 'left',
                  background: m.type === 'user' ? '#1677ff' : m.type === 'error' ? '#fff2f0' : m.type === 'queued' ? '#f6f6f6' : m.type === 'system' ? 'transparent' : '#fff',
                  color: m.type === 'user' ? '#fff' : m.type === 'system' ? '#8c8c8c' : undefined,
                  border: m.type === 'user' ? 'none' : `1px solid ${m.type === 'error' ? '#ffccc7' : m.type === 'queued' ? '#e8e8e8' : '#f0f0f0'}`,
                  opacity: m.type === 'queued' ? 0.75 : 1,
                }}
              >
                {m.type === 'queued' && (
                  <Space size={4} style={{ marginBottom: 2 }}>
                    <Tag color="default" style={{ marginRight: 0, fontSize: 12, lineHeight: '16px' }}>待发送</Tag>
                    <Button
                      type="text" size="small" style={{ height: 16, padding: '0 2px', fontSize: 12 }}
                      onClick={() => revoke(m.id)}
                    >
                      撤回
                    </Button>
                  </Space>
                )}
                {m.attachments?.map((a, i) => (a.dataUrl
                  ? <img key={i} src={a.dataUrl} alt={a.name} style={{ maxWidth: 260, maxHeight: 180, borderRadius: 6, display: 'block', marginBottom: 4 }} />
                  : <div key={i} style={{ marginBottom: 4 }}><PaperClipOutlined /> {a.name}</div>))}
                {m.thinking && (
                  <div style={{
                    marginBottom: m.text ? 6 : 0, padding: '4px 8px', fontSize: 12, color: '#8c8c8c',
                    background: '#fafafa', borderLeft: '2px solid #d9d9d9', borderRadius: 4,
                    maxHeight: 140, overflowY: 'auto', whiteSpace: 'pre-wrap',
                  }}>{m.thinking}</div>
                )}
                {m.type === 'user' || m.type === 'queued'
                  ? m.text
                  : <MarkdownView scroll={false} text={m.text} />}
                {m.streaming && m.phaseText && (
                  <div style={{ marginTop: 4, fontSize: 12, color: '#531dab' }}>
                    {m.phaseText}
                    {m.startedAt && (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {' '}· 已 {Math.floor(Date.now() / 1000) - m.startedAt}s
                      </Typography.Text>
                    )}
                  </div>
                )}
                {m.streaming && <Typography.Text type="secondary"> ▍</Typography.Text>}
              </div>
              )}
            </div>
          ))
        )}
        </div>
      </div>

      <div>
        {files.length > 0 && (
          <Space wrap size={6} style={{ marginBottom: 6 }}>
            {files.map((f, i) => (
              <Tag
                key={i} closable onClose={() => setFiles((x) => x.filter((_, j) => j !== i))}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
              >
                {f.dataUrl
                  ? <img src={f.dataUrl} alt={f.name} style={{ width: 22, height: 22, objectFit: 'cover', borderRadius: 3 }} />
                  : <PaperClipOutlined />}
                {f.name}
              </Tag>
            ))}
          </Space>
        )}
        <Input.TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
          disabled={inputLocked}
          placeholder={inputLocked
            ? '有未处理的决策——请先在右侧「阶段执行」的决策卡上答复，答复后这里自动解锁'
            : `说明你要在${stageText}做什么（Enter 发送，Shift+Enter 换行，可直接粘贴截图）`}
          autoSize={{ minRows: 2, maxRows: 5 }}
        />
        {inputLocked && (
          <Alert
            type="warning"
            showIcon
            style={{ marginTop: 6 }}
            message={`待处理决策 ${pendingDecisions.length} 项：${pendingDecisions.map((d) => `#${d.seq} ${d.name}`).join('、')}`}
            description="决策需要在右侧「阶段执行」面板处理（点决策行的「处理」按钮答复）。答复后对话自动解锁。"
            action={onJumpExec && (
              <Button size="small" type="primary" onClick={onJumpExec}>去处理</Button>
            )}
          />
        )}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6 }}>
          <Typography.Text type="secondary" style={{ fontSize: 12, flex: 1 }}>
            {busy
              ? `运行中 — Enter 入队，Ctrl+Enter 插队${queueLen ? `（队列 ${queueLen} 条）` : ''}`
              : '就绪 — Enter 发送，Shift+Enter 换行'}
          </Typography.Text>
          {busy && (
            <Button danger icon={<StopOutlined />} onClick={stop}>停止</Button>
          )}
          {busy && (
            <Button
              color="orange" variant="solid" icon={<ThunderboltOutlined />}
              disabled={!input.trim()} onClick={() => submit(true)}
            >
              插队
            </Button>
          )}
          <Upload
            accept="image/*,.md,.txt,.json,.java,.xml,.yml,.yaml,.csv,.log,.sql,.ts,.tsx,.js,.jsx,.py,.sh,.html,.css,.vue,.properties,.conf,.ini"
            showUploadList={false}
            beforeUpload={(f) => { addFile(f); return Upload.LIST_IGNORE }}
          >
            <Button icon={<PaperClipOutlined />}>附件</Button>
          </Upload>
          <Button
            type="primary" icon={<SendOutlined />}
            disabled={inputLocked || (busy && !input.trim() && files.length === 0)} onClick={() => submit(false)}
          >
            {busy ? `入队${queueLen ? ` (${queueLen})` : ''}` : '发送'}
          </Button>
        </div>
      </div>
      <Modal
        open={!!chatLogView}
        title={`对话过程留档 · ${chatLogView?.path.split('/').pop() || ''}`}
        onCancel={() => setChatLogView(null)}
        footer={null}
        width={860}
        styles={{ body: { maxHeight: '70vh', overflowY: 'auto' } }}
      >
        {chatLogView && <MarkdownView scroll={false} text={chatLogView.content} />}
      </Modal>
    </div>
  )
}

/**
 * 右侧栏「会话」tab：项目内全部阶段会话（会话粒度=阶段，阶段推进自动开新会话），
 * 点击展开回放该阶段的对话历史（GET /stage/chat/history）。
 */
