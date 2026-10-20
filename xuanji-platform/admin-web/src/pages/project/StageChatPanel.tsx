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

/** 阶段对话消息（user 右侧蓝底，agent 左侧白底带归属标注，error 红底，queued 灰底待发送，process 过程卡片，confirm 工具授权卡） */
export interface StageChatMsg {
  id: number
  type: 'user' | 'agent' | 'error' | 'queued' | 'system' | 'confirm' | 'chatlog'
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
  /** 系统唤醒轮的 agent 回复（后端自动续跑，非用户输入触发）——渲染带「系统唤醒」标识 */
  systemWake?: boolean
  /** 归属智能体（统一时间线）：主控='主控'，执行者=agent 名——渲染成消息头彩色标注 */
  sender?: string
  /** 在做什么（任务名/议题名/求助对象）：如「架构设计与技术选型」「@architect 求助」 */
  action?: string
}

/** 归属标注配色：按名字稳定取色（同一智能体恒定同色，多智能体时间线一眼分清谁在说话） */
const SENDER_COLORS = ['#1677ff', '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16', '#52c41a', '#2f54eb', '#faad14']
const senderColor = (name: string) => {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return SENDER_COLORS[h % SENDER_COLORS.length]
}

/** 步骤 outputFull → 统一时间线消息序列（谁在做什么、说了什么）。
 * 讨论：按【发言者】分段，汇总段归主控（主席收口）；求助：问/答各归其主；
 * 任务：产出/轨迹整体归执行 agent。▶ 进行中提示行丢弃（状态条已表达）。 */
function stepToMsgs(s: { stepType: string; name: string; agent?: string | null; outputFull?: string | null; status: string }):
  { sender: string; action?: string; text: string }[] {
  const full = s.outputFull || ''
  if (!full.trim()) return []
  if (s.stepType === 'TASK') {
    return [{ sender: s.agent || '执行智能体', action: s.name, text: full.trim() }]
  }
  if (s.stepType === 'CONSULT') {
    const out: { sender: string; action?: string; text: string }[] = []
    const qm = full.match(/【问】\(([^)]*)\)\n?([\s\S]*?)(?=\n\n【答】|$)/)
    const am = full.match(/【答】\(([^)]*)\)\n?([\s\S]*)$/)
    if (qm) out.push({ sender: qm[1] || '求助方', action: `@${(am?.[1] || '对方').trim()} 求助`, text: qm[2].trim() })
    if (am) out.push({ sender: am[1] || '支援方', action: qm ? `回答 ${qm[1] || '求助方'}` : undefined, text: am[2].trim() })
    return out.length ? out : [{ sender: s.agent || '求助', action: s.name, text: full.trim() }]
  }
  // DISCUSS：发言分段；「===== 汇总 =====」之后是主控（主席）收口
  const sumIdx = full.indexOf('===== 汇总 =====')
  const body = sumIdx >= 0 ? full.slice(0, sumIdx) : full
  const sum = sumIdx >= 0 ? full.slice(sumIdx + '===== 汇总 ====='.length).trim() : ''
  const out: { sender: string; action?: string; text: string }[] = []
  for (const seg of body.split(/(?=【[^】]+】)/)) {
    const t = seg.replace(/▶\s*\S+\s*发言中…/g, '').trim()
    if (!t) continue
    const m = t.match(/^【([^】]+)】\s*([\s\S]*)$/)
    if (m) {
      const sender = m[1].trim()
      const text = m[2].trim()
      if (text) out.push({ sender, action: s.name, text })
    } else if (t) {
      out.push({ sender: '讨论', action: s.name, text: t })
    }
  }
  if (sum) out.push({ sender: '主控', action: `讨论收口 · ${s.name}`, text: sum })
  return out
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
export function StageChatPanel({ projectId, exec, onJumpExec }: {
  projectId: string
  exec?: StageExecution | null
  onJumpExec?: () => void
}) {
  const [msgs, setMsgs] = useState<StageChatMsg[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  /** 终止执行中的步骤（活跃步骤条就地终止）：后端 cancel 当前 LLM 调用，SKIPPED 留痕，主控进入下一步 */
  const [terminating, setTerminating] = useState<number | null>(null)
  const terminateStep = (seq: number, stage?: string, iteration?: number) => {
    setTerminating(seq)
    // stage/iteration 必带（seq=18 404 实锤）：阶段推进后按当前阶段查会误报步骤不存在
    const q = stage && iteration ? `?stage=${stage}&iteration=${iteration}` : ''
    apiJson<{ message?: string }>(`/api/projects/${projectId}/stage/step/${seq}/terminate${q}`, 'POST')
      .then((r) => message.success(r.message || '已终止'))
      .catch((e: Error) => message.error(e.message))
      .finally(() => setTerminating(null))
  }
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

  // 打字机效果：流式 delta 先进缓冲，定时器逐字上屏——LLM 生成速度不均（有时一大段一起到），
  // 直接 append 会一坨一坨地蹦；打字机让正文平滑输出。自适应步长：落后越多打字越快，保证追平不积压。
  const typeQueueRef = useRef<Map<number, { full: string; shown: number; done: boolean }>>(new Map())
  const typeTimerRef = useRef<number | null>(null)
  const ensureTypeTimer = () => {
    if (typeTimerRef.current != null) return
    typeTimerRef.current = window.setInterval(() => {
      const q = typeQueueRef.current
      if (q.size === 0) {
        window.clearInterval(typeTimerRef.current!)
        typeTimerRef.current = null
        return
      }
      for (const [id, e] of q) {
        e.shown = Math.min(e.full.length, e.shown + Math.max(2, Math.ceil((e.full.length - e.shown) / 12)))
        setMsgs((x) => x.map((m) => (m.id === id ? { ...m, text: e.full.slice(0, e.shown) } : m)))
        if (e.done && e.shown >= e.full.length) q.delete(id)
      }
    }, 30)
  }
  /** delta 进缓冲（消息首次出现即建缓冲条目） */
  const queueDelta = (id: number, delta: string) => {
    const e = typeQueueRef.current.get(id)
    if (e) e.full += delta
    else typeQueueRef.current.set(id, { full: delta, shown: 0, done: false })
    ensureTypeTimer()
  }
  /** 整段兜底（agent.result）：替换缓冲全文并标记完成——result 是权威全文，shown 保留进度不重打 */
  const queueFull = (id: number, full: string) => {
    const e = typeQueueRef.current.get(id)
    if (e) { e.full = full; e.done = true }
    else typeQueueRef.current.set(id, { full, shown: 0, done: true })
    ensureTypeTimer()
  }
  /** 流结束：标记完成，定时器把剩余缓冲排空后自动清理 */
  const finishQueue = (id: number) => {
    const e = typeQueueRef.current.get(id)
    if (e) e.done = true
    ensureTypeTimer()
  }

  useEffect(() => {
    const steps = exec?.steps || []
    const prev = prevStepsRef.current
    const next = new Map<number, { status: string; fullLen: number }>()
    const events: string[] = []
    const timeline: { sender: string; action?: string; text: string }[] = []
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
        } else if (s.status === 'EXECUTING' && s.stepType === 'TASK') {
          events.push(`任务 #${s.seq} ${s.name} 进行中（${s.agent || '执行智能体'}），执行过程将实时显示在这里`)
        }
        continue
      }
      if (before.status !== s.status) {
        if (s.status === 'EXECUTING') {
          events.push(`⚙ #${s.seq} ${s.name} 开始执行${(s.participants || []).length ? `（${(s.participants || []).join('、')}）` : ''}`)
        } else if (s.status === 'DONE' && s.stepType !== 'DISCUSS' && s.stepType !== 'CONSULT' && s.stepType !== 'TASK') {
          events.push(`✅ #${s.seq} ${s.name} 完成${s.outputSummary ? `：${s.outputSummary.slice(0, 100)}` : ''}`)
        } else if (s.status === 'FAILED') {
          events.push(`❌ #${s.seq} ${s.name} 失败：${(s.error || '').slice(0, 100)}`)
        } else if (s.status === 'SKIPPED') {
          events.push(`⏭ #${s.seq} ${s.name} 已跳过：${(s.skipReason || '').slice(0, 80)}`)
        } else if (s.status === 'WAITING') {
          events.push(`⏸ #${s.seq} ${s.name} 等待你的答复：${(s.instruction || '').slice(0, 100)}`)
        }
      }
      // 统一时间线：讨论发言/求助问答/任务轨迹按归属拆条进对话流（谁在做什么、说了什么）
      if (s.stepType === 'DISCUSS' && full.length > before.fullLen) {
        const delta = full.slice(before.fullLen, full.length)
        for (const m of stepToMsgs({ stepType: 'DISCUSS', name: s.name, outputFull: delta, status: s.status })) {
          timeline.push(m)
        }
      }
      // 求助问答完成：问答全文拆条（问归求助方、答归支援方）
      if (s.stepType === 'CONSULT' && s.status === 'DONE' && before.status !== 'DONE') {
        for (const m of stepToMsgs(s)) timeline.push(m)
      }
      // 任务执行中的轨迹实时性由总线 SSE 负责（step.process 流式消息）；
      // 轮询只兜底终态：任务完成推产出全文（output_full 已被纯产出覆盖，整段推）
      if (s.stepType === 'TASK' && s.status === 'DONE' && before.status !== 'DONE') {
        for (const m of stepToMsgs(s)) timeline.push(m)
      }
      // 讨论/求助/任务完成的前端自动续跑已下线：后端统一唤醒（StageOrchestrator.wakeIfSettled，
      // 批次收敛 + 系统身份无头轮）——后端是运行唯一真相，前端续跑依赖页面开着且与后端双触发。
      // 前端经会话事件总线（下方 effect）实时回显后端唤醒轮。
    }
    prevStepsRef.current = next
    if (events.length > 0 || timeline.length > 0) {
      setMsgs((x) => [
        ...x,
        ...(events.length > 0 ? [{ id: nextId(), type: 'system' as const, text: events.join('\n') }] : []),
        ...timeline.map((m) => ({ id: nextId(), type: 'agent' as const, sender: m.sender, action: m.action, text: m.text })),
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
        .map((m) => {
          const raw = m.text || ''
          // [系统唤醒] 前缀 = 后端自动续跑轮的触发消息（agent_state 里 role=USER）——
          // 回放渲染成系统提示条，与用户手打输入区分
          if (m.role === 'USER' && raw.startsWith('[系统唤醒]')) {
            return { id: nextId(), type: 'system' as const, text: `⚙ 系统唤醒：${raw.replace(/^\[系统唤醒\]\s*/, '').split('\n')[0]}` }
          }
          return { id: nextId(), type: (m.role === 'USER' ? 'user' : 'agent') as 'user' | 'agent', text: raw }
        })
      // 过程回放：讨论发言/求助问答/任务产出都落在 project_stage_step（不在 agent_state 会话），
      // 单独拉 stage-execution 补齐——按归属拆条融入历史流（统一时间线，可视即留档）
      apiJson<{ steps?: StageStepRow[] }>(`/api/projects/${projectId}/stage-execution`)
        .then((ex) => {
          if (!alive) return
          const proc = (ex?.steps || [])
            .filter((s) => (s.stepType === 'DISCUSS' || s.stepType === 'CONSULT' || s.stepType === 'TASK') && (s.outputFull || '').trim())
            .sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
            .flatMap((s) => stepToMsgs(s).map((m) => ({ id: nextId(), type: 'agent' as const, sender: m.sender, action: m.action, text: m.text })))
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
              // 过程卡片（讨论/求助/任务）与 CHATLOG 条目直接融入历史流——
              // 可视即留档，不再用「以下为留档」分隔条制造两个世界
              if (hist.length || proc.length || logMsgs.length) {
                setMsgs([...hist, ...proc, ...logMsgs])
              }
            })
            .catch(() => { if (hist.length || proc.length) setMsgs([...hist, ...proc]) })
        })
        .catch(() => { if (hist.length) setMsgs(hist) })
    }).catch(() => { /* 历史取不到不打断对话主流程 */ })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, chatStage, chatIteration])

  // 后端系统唤醒轮的实时回显：常驻订阅会话事件总线（fetch 流，token 走 Authorization header，
  // EventSource 带不了）。后端是运行唯一真相——任务/讨论落板、决策答复后主控自动续跑（无头轮），
  // 事件经总线推给在线前端；离线时落 agent_state，回放兜底。断线 3s 重连。
  useEffect(() => {
    if (!chatStage) return
    let alive = true
    let timer: number | null = null
    const busAgent = { id: 0 }
    const busProc = { id: 0 }
    const busToolLines: string[] = []
    // 任务执行流式消息（step.process）：按 seq 聚合一条带归属的消息，delta 增量 append——
    // developer 执行任务的思考/工具轨迹实时进对话流（统一时间线：谁在做什么）
    const busStepMsgs = new Map<number, { id: number }>()
    const wait = (ms: number) => new Promise<void>((res) => { timer = window.setTimeout(res, ms) })
    const connect = async () => {
      while (alive) {
        try {
          const r = await apiFetch(`/api/projects/${projectId}/stage/chat/events?stage=${chatStage}&iteration=${chatIteration}`)
          if (!r.ok || !r.body) { await wait(3000); continue }
          await readSse(r, (event, data) => {
            if (!alive) return
            let payload: Record<string, unknown> = {}
            try { payload = JSON.parse(data) } catch { /* 忽略非 JSON 帧 */ }
            if (event === 'system.wake') {
              // 系统唤醒提示条：告知用户这一轮是系统自动触发（区别于用户输入）
              busAgent.id = 0
              busProc.id = 0
              busToolLines.length = 0
              setMsgs((x) => [...x, { id: nextId(), type: 'system', text: `⚙ 系统唤醒：${String(payload.reason || '后台任务已落板，主控继续推进')}` }])
            } else if (event === 'step.process') {
              const seq = Number(payload.seq || 0)
              const delta = typeof payload.delta === 'string' ? payload.delta : ''
              if (!seq || !delta.trim()) return
              const name = String(payload.name || `#${seq}`)
              const agent = String(payload.agent || '执行智能体')
              let entry = busStepMsgs.get(seq)
              if (!entry) {
                entry = { id: nextId() }
                busStepMsgs.set(seq, entry)
                setMsgs((x) => [...x, { id: entry!.id, type: 'agent', sender: agent, action: name, text: delta }])
              } else {
                setMsgs((x) => x.map((m) => (m.id === entry!.id ? { ...m, text: m.text + delta } : m)))
              }
            } else if (event === 'agent.start') {
              const id = nextId()
              busAgent.id = id
              setMsgs((x) => [...x, { id, type: 'agent', sender: '主控', text: '', streaming: true, systemWake: true, phaseText: '🤔 主控继续推进中…' }])
            } else if (event === 'agent.delta' && busAgent.id && typeof payload.delta === 'string') {
              queueDelta(busAgent.id, payload.delta)
              setMsgs((x) => x.map((m) => (m.id === busAgent.id ? { ...m, phaseText: undefined } : m)))
            } else if (event === 'agent.result' && busAgent.id && typeof payload.text === 'string') {
              if (!typeQueueRef.current.has(busAgent.id)) queueFull(busAgent.id, payload.text)
            } else if (event === 'tool.call' && busAgent.id) {
              const tool = String(payload.tool || '')
              busToolLines.push(`🔧 ${tool} 执行中…`)
              if (!busProc.id) {
                busProc.id = nextId()
                setMsgs((x) => [...x, { id: busProc.id, type: 'agent', sender: '主控', action: '🔧 工具执行（系统唤醒轮）', text: busToolLines.join('\n') }])
              } else {
                setMsgs((x) => x.map((m) => (m.id === busProc.id ? { ...m, text: busToolLines.join('\n') } : m)))
              }
            } else if (event === 'tool.result' && busAgent.id) {
              const tool = String(payload.tool || '')
              const ok = payload.state !== 'ERROR'
              const line = `${ok ? '✅' : '❌'} ${tool} ${ok ? '完成' : '失败'}`
              const idx = busToolLines.lastIndexOf(`🔧 ${tool} 执行中…`)
              if (idx >= 0) busToolLines[idx] = line
              else busToolLines.push(line)
              if (busProc.id) setMsgs((x) => x.map((m) => (m.id === busProc.id ? { ...m, text: busToolLines.join('\n') } : m)))
            } else if (event === 'system.truth') {
              // 唯一真相播报（产品契约：对话文本声称的状态必须有台账对应）：
              // 主控轮结束后系统播报台账实况——用户据此核对主控说的与台账是否一致
              const done = Number(payload.done || 0)
              const executing = Number(payload.executing || 0)
              const waitingAccept = Number(payload.waitingAccept || 0)
              const decisionPending = Number(payload.decisionPending || 0)
              const planned = Number(payload.planned || 0)
              const failed = Number(payload.failed || 0)
              const parts: string[] = []
              if (executing) parts.push(`执行中 ${executing}`)
              if (waitingAccept) parts.push(`待验收 ${waitingAccept}`)
              if (decisionPending) parts.push(`待决策 ${decisionPending}`)
              if (planned) parts.push(`未执行 ${planned}`)
              if (failed) parts.push(`失败 ${failed}`)
              parts.push(`已完成 ${done}`)
              setMsgs((x) => [...x, { id: nextId(), type: 'system', text: `📋 台账实况：${parts.join(' · ')}` }])
            } else if (event === 'stage.finish' && busAgent.id) {
              finishQueue(busAgent.id)
              setMsgs((x) => x.map((m) => (m.id === busAgent.id ? { ...m, streaming: false, phaseText: undefined } : m)))
            }
          })
        } catch { /* 断线重连 */ }
        if (alive) await wait(3000)
      }
    }
    void connect()
    return () => { alive = false; if (timer != null) window.clearTimeout(timer) }
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

  /** 💭 思考段落拆分：执行轨迹里的 💭【思考】…💭【思考结束】段折叠渲染（默认收起）——
   *  协作现场：用户能看到每个智能体的想法，但长思考不淹没正文 */
  const renderWithThinking = (text: string) => {
    if (!text || !text.includes('💭 【思考】')) return <MarkdownView scroll={false} text={text} />
    const parts = text.split(/💭 【思考】([\s\S]*?)(?:💭 【思考结束】|$)/g)
    return parts.map((seg, i) => i % 2 === 0
      ? (seg.trim() ? <MarkdownView key={i} scroll={false} text={seg} /> : null)
      : (
        <details key={i} style={{ margin: '4px 0' }}>
          <summary style={{ fontSize: 12, color: '#8c8c8c', cursor: 'pointer', userSelect: 'none' }}>
            💭 思考过程（点击展开）
          </summary>
          <div style={{
            fontSize: 12, color: '#8c8c8c', whiteSpace: 'pre-wrap', maxHeight: 220, overflowY: 'auto',
            padding: '4px 8px', background: '#fafafa', borderLeft: '2px solid #d9d9d9', borderRadius: 4, marginTop: 2,
          }}>{seg}</div>
        </details>
      ))
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
          // 回放同样区分成败（meta 带 state；旧事件无 state 默认完成）
          const rt = String(evt.meta?.tool || '')
          const rok = evt.meta?.state !== 'ERROR'
          procLines.push(`${rok ? '✅' : '❌'} ${rt} ${rok ? '完成' : '失败'}`)
        } else if (evt.type === 'error') {
          procLines.push(`⚠️ ${evt.text || '执行出错'}`)
        }
      }
      setMsgs((x) => {
        const extra: StageChatMsg[] = []
        if (procLines.length) {
          extra.push({ id: nextId(), type: 'agent', sender: '主控', action: '🔧 工具执行', text: procLines.join('\n') })
        }
        if (buf.trim()) {
          extra.push({ id: agentId, type: 'agent', sender: '主控', text: buf })
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
      { id: agentMsgId, type: 'agent', sender: '主控', text: '', streaming: true, startedAt, phaseText: '🤔 思考与规划中…' },
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
        setMsgs((x) => [...x, { id: procMsgId, type: 'agent', sender: '主控', action: '🔧 工具执行', text: toolLines.join('\n') }])
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
        if (event === 'speech.echo') {
          // 插话回显（2026-10-17 同步化）：讨论/任务执行中用户消息不起新主控轮，
          // 后端直接落库并回提示——把 agent 占位卡转成系统提示
          const tip = String(payload.message || '你的发言已加入讨论流')
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, type: 'system' as const, text: `💬 ${tip}`, streaming: false, phaseText: undefined } : m)))
        } else if (event === 'agent.start') {
          // 不设占位文本：正文完全由打字机队列驱动（占位与 delta 混排是旧瑕疵），等待期由 phaseText 提示
          const who = String(payload.agent || 'agent')
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, phaseText: `🤔 ${who} 处理中…` } : m)))
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
          queueDelta(agentMsgId, delta)
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, phaseText: undefined } : m)))
        } else if (event === 'agent.result' && typeof payload.text === 'string') {
          // 只有还没流式出正文时才用整段结果兜底，否则会把已流出的内容重复一遍
          const full = payload.text
          if (!typeQueueRef.current.has(agentMsgId)) queueFull(agentMsgId, full)
        } else if (event === 'tool.call') {
          const tool = String(payload.tool || '')
          pushToolLine(`🔧 ${tool} 执行中…`)
          setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, phaseText: `🔧 ${tool} 执行中…` } : m)))
        } else if (event === 'tool.result') {
          const tool = String(payload.tool || '')
          // 失败标 ❌（此前写死 ✅，「✅ xx 失败」自相矛盾——实测反馈）
          const ok = payload.state !== 'ERROR'
          const line = `${ok ? '✅' : '❌'} ${tool} ${ok ? '完成' : '失败'}`
          const idx = toolLines.lastIndexOf(`🔧 ${tool} 执行中…`)
          if (idx >= 0) toolLines[idx] = line
          else toolLines.push(line)
          if (procMsgId) {
            setMsgs((x) => x.map((m) => (m.id === procMsgId ? { ...m, text: toolLines.join('\n') } : m)))
          } else {
            procMsgId = nextId()
            setMsgs((x) => [...x, { id: procMsgId, type: 'agent', sender: '主控', action: '🔧 工具执行', text: toolLines.join('\n') }])
          }
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, phaseText: `${ok ? '✅' : '❌'} ${tool} ${ok ? '完成' : '失败'}，继续…` } : m)))
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
          finishQueue(agentMsgId)
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
        finishQueue(agentMsgId)
        setMsgs((x) => x.map((m) => (m.id === agentMsgId
          ? { ...m, streaming: false, phaseText: undefined } : m)))
        // 中断提示延迟到打字机排空后追加（直接改 text 会和打字机队列互相覆盖）
        window.setTimeout(() => setMsgs((x) => x.map((m) => (m.id === agentMsgId
          ? { ...m, text: (m.text || '') + '\n\n（⚠ 连接中断——后台任务会继续执行并留档，稍后刷新页面查看结果）' } : m))), 600)
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
    // 讨论进行中：用户发言直接插进讨论流（【用户】前缀落库，后续发言者与汇总可见）——
    // 用户可参与讨论，而不是旁观后台黑盒；不走主控对话（主控正在等讨论结果）
    const bg = (exec?.steps || []).find((s) => (s.stepType === 'DISCUSS' || s.stepType === 'CONSULT') && s.status === 'EXECUTING')
    if (bg) {
      const text = query
      const seq = bg.seq
      setInput('')
      setFiles([])
      void apiJson(`/api/projects/${projectId}/stage/discuss/speech`, 'POST', { seq, text })
        .then(() => message.success(`发言已加入${bg.stepType === 'DISCUSS' ? '讨论' : '求助'} #${seq}，AI 发言者会看到`))
        .catch((e) => message.error(e instanceof Error ? e.message : String(e)))
      return
    }
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

  // 决策答复的前端自动续跑已下线：answer 端点置 DONE 后由后端 wakeMaster 直接唤醒主控
  //（答复内容进唤醒消息），事件经总线回显——与任务/讨论落板唤醒同一条后端链路。

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit(e.ctrlKey || e.metaKey)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>

      {/* 活跃步骤条：后台执行中的小流程实时可见（讨论已收集多少发言/任务跑多久），不再空等。
          终止按钮就地可点（2026-10-19）：不想等不用开追溯面板找步骤，直接终止，主控跳到下一步 */}
      {(() => {
        const active = (exec?.steps || []).filter((s) => s.status === 'EXECUTING' || s.status === 'WAITING')
        if (active.length === 0) return null
        return (
          <div style={{ padding: '6px 10px', background: '#e6f4ff', borderRadius: 6, border: '1px solid #91caff' }}>
            {active.map((s) => (
              <div key={s.id} style={{ fontSize: 12, lineHeight: '20px', display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{ flex: 1, minWidth: 0 }}>
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
                </span>
                {s.status === 'EXECUTING' && (
                  <Button size="small" type="text" danger style={{ fontSize: 12, flexShrink: 0 }}
                    loading={terminating === s.seq}
                    onClick={() => terminateStep(s.seq, s.stage, s.iteration)}>终止</Button>
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
              {/* 统一时间线归属标注：谁在做什么（多智能体协作一眼分清） */}
              {m.sender && (
                <div style={{ marginBottom: 2, display: 'flex', alignItems: 'baseline', gap: 6 }}>
                  <span style={{
                    fontSize: 12, fontWeight: 600, color: '#fff', background: senderColor(m.sender),
                    padding: '1px 8px', borderRadius: 10, lineHeight: '18px',
                  }}>{m.sender}</span>
                  {m.action && <span style={{ fontSize: 12, color: '#8c8c8c' }}>{m.action}</span>}
                </div>
              )}
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
                  background: m.type === 'user' ? '#1677ff' : m.type === 'error' ? '#fff2f0' : m.type === 'queued' ? '#f6f6f6' : m.type === 'system' ? 'transparent' : m.systemWake ? '#f6f6f6' : '#fff',
                  color: m.type === 'user' ? '#fff' : m.type === 'system' ? '#8c8c8c' : undefined,
                  border: m.type === 'user' ? 'none' : `1px solid ${m.type === 'error' ? '#ffccc7' : m.type === 'queued' ? '#e8e8e8' : m.systemWake ? '#d9d9d9' : '#f0f0f0'}`,
                  opacity: m.type === 'queued' ? 0.75 : 1,
                }}
              >
                {m.systemWake && (
                  <div style={{ marginBottom: 2 }}>
                    <Tag color="default" style={{ marginRight: 0, fontSize: 12, lineHeight: '16px' }}>⚙ 系统唤醒轮</Tag>
                  </div>
                )}
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
                  : renderWithThinking(m.text)}
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
            : (exec?.steps || []).some((s) => (s.stepType === 'DISCUSS' || s.stepType === 'CONSULT') && s.status === 'EXECUTING')
              ? '讨论进行中：在这里发言会直接进入讨论，AI 发言者与汇总都会看到（Enter 发送）'
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
