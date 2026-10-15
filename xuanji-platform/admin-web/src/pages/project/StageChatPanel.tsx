import { useEffect, useRef, useState } from 'react'
import {
  Button,
  Empty,
  Input,
  Select,
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
} from '@ant-design/icons'
import { apiFetch, apiJson } from '../../api/client'
import { PlanTask, STEP_STATUS_COLOR, STEP_STATUS_LABEL, StageExecution, StageStepRow, isOrchestrated } from './types'
import { MarkdownView } from './shared'

/** 附件（对话消息可带图片/文本附件） */
interface ChatAttachment {
  name: string
  mediaType?: string
  dataBase64?: string
  content?: string
  dataUrl?: string
}

/** 阶段对话消息（user 右侧蓝底，agent 左侧白底，error 红底，queued 灰底待发送，process 过程留档卡片） */
export interface StageChatMsg {
  id: number
  type: 'user' | 'agent' | 'error' | 'queued' | 'system' | 'process'
  text: string
  attachments?: ChatAttachment[]
  /** agent 回复流式生成中 */
  streaming?: boolean
  /** process 消息的标题（如「💬 #3 讨论·发言」「❓ #5 求助·architect」） */
  title?: string
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
export function StageChatPanel({ projectId, exec }: { projectId: string; exec?: StageExecution | null }) {
  const [msgs, setMsgs] = useState<StageChatMsg[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [taskKey, setTaskKey] = useState<string | undefined>(undefined)
  const idRef = useRef(0)
  const nextId = () => ++idRef.current
  const listRef = useRef<HTMLDivElement>(null)
  // 多轮交流：busy 时输入不锁——Enter 入队 / Ctrl+Enter 插队，当前轮结束自动发队首。
  // 队列用 ref 存（同步读写），queueLen 只做渲染镜像。
  const queueRef = useRef<{ id: number; text: string; taskKey?: string; attachments?: ChatAttachment[] }[]>([])
  const [queueLen, setQueueLen] = useState(0)
  const abortRef = useRef<AbortController | null>(null)
  const busyRef = useRef(false)
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

  // 新内核（AI 编排）模式：子任务聚焦是旧链路概念（plan.tasks 草稿与新内核 steps 无关），隐藏
  const orchestrated = isOrchestrated(exec)
  const tasks = orchestrated ? [] : ((exec?.plan?.tasks || []) as PlanTask[])

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
      if (!before) continue // 首次见到的步骤由历史回放负责展示，避免重复
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
        if (delta.trim()) processMsgs.push({ title: `💬 #${s.seq} ${s.name}·新发言`, text: renderSpeech(delta) })
      }
      // 讨论完成：汇总进对话流（发言已按增量推过，只推汇总段）
      if (s.stepType === 'DISCUSS' && s.status === 'DONE' && before.status !== 'DONE') {
        const sum = summaryPart(full)
        if (sum) processMsgs.push({ title: `📋 #${s.seq} ${s.name}·汇总`, text: renderSpeech(sum) })
      }
      // 求助问答完成：问答全文进对话流
      if (s.stepType === 'CONSULT' && s.status === 'DONE' && before.status !== 'DONE') {
        if (full.trim()) processMsgs.push({ title: `❓ #${s.seq} ${s.name}`, text: renderSpeech(full) })
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
                ? `📋 #${s.seq} ${s.name}（讨论纪要${s.status === 'FAILED' ? '·中断' : ''}）`
                : `❓ #${s.seq} ${s.name}`
              return [{ id: nextId(), type: 'process' as const, title: label, text: renderSpeech(s.outputFull || '') }]
            })
          const divider = proc.length > 0
            ? [{ id: nextId(), type: 'system' as const, text: '── 以下为讨论与求助留档 ──' }]
            : []
          if (hist.length || divider.length) setMsgs([...hist, ...divider, ...proc])
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

  /** 发送一条（q 已在 msgs 里以 queued 形态占位）：SSE 流式回填，停止走 AbortController */
  const sendMsg = async (q: { id: number; text: string; taskKey?: string; attachments?: ChatAttachment[] }) => {
    const agentMsgId = nextId()
    setMsgs((x) => [
      ...x.map((m) => (m.id === q.id ? { ...m, type: 'user' as const } : m)),
      { id: agentMsgId, type: 'agent', text: '', streaming: true },
    ])
    busyRef.current = true
    setBusy(true)
    const ac = new AbortController()
    abortRef.current = ac
    try {
      const r = await apiFetch(`/api/projects/${projectId}/stage/chat`, {
        method: 'POST',
        body: JSON.stringify({
          message: q.text,
          ...(q.taskKey ? { task_key: q.taskKey } : {}),
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
      await readSse(r, (event, data) => {
        let payload: Record<string, unknown> = {}
        try { payload = JSON.parse(data) } catch { /* 忽略非 JSON 帧 */ }
        if (event === 'agent.start') {
          const who = String(payload.agent || 'agent')
          setMsgs((x) => x.map((m) => (m.id === agentMsgId
            ? { ...m, text: m.text || `（${who} 正在处理…）` } : m)))
        } else if (event === 'agent.delta' && typeof payload.delta === 'string') {
          const delta = payload.delta
          setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, text: (m.text || '') + delta } : m)))
        } else if (event === 'agent.result' && typeof payload.text === 'string') {
          // 只有还没流式出正文时才用整段结果兜底，否则会把已流出的内容重复一遍
          const full = payload.text
          setMsgs((x) => x.map((m) => (m.id === agentMsgId && (!m.text || m.text.startsWith('（'))
            ? { ...m, text: full } : m)))
        } else if (event === 'agent.end' || event === 'stage.finish') {
          setMsgs((x) => x.map((m) => (m.id === agentMsgId ? { ...m, streaming: false } : m)))
        } else if (event === 'error') {
          setMsgs((x) => [...x, {
            id: nextId(), type: 'error', text: String(payload.message || '执行出错'),
          }])
        }
      })
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
    const q = { id: nextId(), text: query || '（见附件）', taskKey, attachments: atts }
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
      <Space wrap size={6}>
        {tasks.length > 0 && (
          <>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>本轮聚焦：</Typography.Text>
            <Select
              size="small"
              style={{ minWidth: 280 }}
              placeholder={`整个${stageText}（不指定子任务）`}
              allowClear
              value={taskKey}
              onChange={(v) => setTaskKey(v)}
              options={tasks.map((t, i) => ({
                value: String(t.key ?? i),
                label: `${t.key ?? i + 1}. ${t.name || '未命名子任务'}`,
              }))}
            />
          </>
        )}
        {msgs.length > 0 && (
          <Button size="small" type="text" onClick={() => setMsgs([])}>清空显示</Button>
        )}
      </Space>

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
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    {' '}（{(s.participants || []).join('、')}）
                  </Typography.Text>
                )}
                {s.outputFull && (
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    {' '}· 已收集 {s.outputFull.length} 字
                  </Typography.Text>
                )}
              </div>
            ))}
          </div>
        )
      })()}

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
              {m.type === 'process' ? (
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
                    <Tag color="default" style={{ marginRight: 0, fontSize: 11, lineHeight: '16px' }}>待发送</Tag>
                    <Button
                      type="text" size="small" style={{ height: 16, padding: '0 2px', fontSize: 11 }}
                      onClick={() => revoke(m.id)}
                    >
                      撤回
                    </Button>
                  </Space>
                )}
                {m.attachments?.map((a, i) => (a.dataUrl
                  ? <img key={i} src={a.dataUrl} alt={a.name} style={{ maxWidth: 260, maxHeight: 180, borderRadius: 6, display: 'block', marginBottom: 4 }} />
                  : <div key={i} style={{ marginBottom: 4 }}><PaperClipOutlined /> {a.name}</div>))}
                {m.type === 'user' || m.type === 'queued'
                  ? m.text
                  : <MarkdownView scroll={false} text={m.text} />}
                {m.streaming && <Typography.Text type="secondary"> ▍</Typography.Text>}
              </div>
              )}
            </div>
          ))
        )}
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
          placeholder={`说明你要在${stageText}做什么（Enter 发送，Shift+Enter 换行，可直接粘贴截图）`}
          autoSize={{ minRows: 2, maxRows: 5 }}
        />
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
            disabled={busy && !input.trim() && files.length === 0} onClick={() => submit(false)}
          >
            {busy ? `入队${queueLen ? ` (${queueLen})` : ''}` : '发送'}
          </Button>
        </div>
      </div>
    </div>
  )
}

/**
 * 右侧栏「会话」tab：项目内全部阶段会话（会话粒度=阶段，阶段推进自动开新会话），
 * 点击展开回放该阶段的对话历史（GET /stage/chat/history）。
 */
