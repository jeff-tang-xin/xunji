/**
 * 追溯链视图（用户定调 2026-10-16：流程拓扑与链路可追溯性是唯一主视图，不是内容平铺）。
 *
 * 一条主线读完整个项目：需求项 → 三阶段工作步骤 → 产出证据。
 * - 顶部：三阶段流水线（需求→开发→运维），当前阶段高亮，意图锚摘要；
 * - 主体：按需求分组的链路时间线——每个需求下面挂它被哪些阶段的哪些步骤推进、
 *   每步的机器/人工验证标记、产出文件（点击弹窗看全文）；
 * - 未挂链工作项折叠在底部（透明暴露，倒逼主控补 requirement_codes）。
 *
 * 数据源 GET /api/projects/{id}/trace（ProjectService.trace 聚合，零轮询——
 * 复用父组件 requirement.updated/step.updated 事件刷新机制由 refreshKey 驱动）。
 */
import { useEffect, useState } from 'react'
import { Alert, Button, Card, Collapse, Empty, Input, message, Modal, Space, Spin, Steps, Tag, Tooltip, Typography } from 'antd'
import { AuditOutlined, FileTextOutlined, LockOutlined } from '@ant-design/icons'
import type { StageExecution, StageStepRow } from './types'
import { TraceGraph } from './TraceGraph'
import { DecisionAnswerModal } from './DecisionAnswerModal'
import { StageSessionsPanel } from './StageSessionsPanel'
import { MarkdownView } from './shared'
import { apiJson } from '../../api/client'

interface TraceArtifact { name: string; file_path: string | null; kind: string; content_md?: string | null }
export interface TraceStep {
  stage: string; iteration: number; seq: number; name: string
  step_type: string; status: string; verified_by: string | null
  error: string | null; output_summary: string | null; artifact: TraceArtifact | null
  /** 指令/问题原文（DECISION=向用户提的问题，TASK/DISCUSS=执行指令） */
  instruction?: string | null
  /** 声明服务的需求项（空=全局步骤） */
  requirement_codes?: string[]
}
interface TraceReq {
  code: string; title: string; status: string; depth?: number
  depends_on: string[]; chain: TraceStep[]
  /** 需求详情（2026-10-19 补）：描述/验收断言（JSON 字符串）/确认来源 */
  description?: string | null
  acceptance?: string | null
  source?: string | null
  /** V36 需求拆分层级：父需求编号（空=原始需求） */
  parent_code?: string | null
}

/** 需求状态中文（与后端状态机 PROPOSED→CONFIRMED→IN_PROGRESS→DONE/DROPPED 对齐） */
const REQ_STATUS_VIEW: Record<string, { color: string; text: string }> = {
  PROPOSED: { color: 'gold', text: '已提出' },
  CONFIRMED: { color: 'cyan', text: '已确认' },
  IN_PROGRESS: { color: 'blue', text: '进行中' },
  DONE: { color: 'green', text: '已完成' },
  DROPPED: { color: 'default', text: '已废弃' },
}
interface TraceData {
  current_stage: string
  stages: { stage: string; label: string; iteration: number; status: string; anchor: string | null }[]
  requirements: TraceReq[]
  unlinked: TraceStep[]
  /** V36 交付图：用例/bug 随 trace 返回（TraceGraph 关系图数据源） */
  test_cases?: { code: string; title: string; status: string; requirement_code: string | null; content?: string | null }[]
  bugs?: { code: string; title: string; severity: string; status: string; requirement_code: string | null; test_case_code: string | null; content?: string | null }[]
}

const STEP_STATUS_COLOR: Record<string, string> = {
  DONE: 'green', FAILED: 'red', SKIPPED: 'default',
  EXECUTING: 'blue', WAITING: 'orange', PLANNED: 'purple',
}
const STEP_STATUS_TEXT: Record<string, string> = {
  DONE: '完成', FAILED: '失败', SKIPPED: '跳过',
  EXECUTING: '执行中', WAITING: '待决策', PLANNED: '待执行',
}
const STAGE_TAG_COLOR: Record<string, string> = { REQUIREMENT: 'gold', DEVELOP: 'geekblue', OPERATE: 'volcano' }

/** 步骤→产出证据的文件内容弹窗：有 file_path 走 /files/content（≤200KB 文本），否则显示登记时的 content_md。 */
function ArtifactModal({ projectId, path, name, contentMd, onClose }: { projectId: string; path: string | null; name: string; contentMd?: string | null; onClose: () => void }) {
  const [content, setContent] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => {
    if (!path) return
    apiJson<{ content: string }>(`/api/projects/${projectId}/files/content?path=${encodeURIComponent(path)}`)
      .then((r) => setContent(r?.content ?? ''))
      .catch((e: Error) => setErr(e.message))
  }, [projectId, path])
  return (
    <Modal open title={<Space><FileTextOutlined />{name}</Space>} onCancel={onClose} footer={null} width={780}>
      <div style={{ maxHeight: '65vh', overflow: 'auto' }}>
        {err
          ? <Alert type="error" message={err} />
          : <pre style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>{path ? (content ?? '加载中…') : (contentMd || '（无正文）')}</pre>}
      </div>
    </Modal>
  )
}

/** 步骤详情弹窗：流程正义的落点——谁做的、验证成色、产出证据、错误，全部可查。 */
/** 需求详情弹窗（2026-10-19）：点需求胶囊看需求本身——描述/验收断言/依赖/来源 + 完整追溯链，链上步骤可再点进步骤详情 */
function RequirementDetailModal({ r, onOpenStep, onClose }: { r: TraceReq | null; onOpenStep: (s: TraceStep) => void; onClose: () => void }) {
  if (!r) return null
  const sv = REQ_STATUS_VIEW[r.status] || { color: 'default', text: r.status }
  // 验收断言：JSON 数组 [{type,assert}] 容错解析——解析失败显示原文
  let asserts: { type?: string; assert?: string; [k: string]: unknown }[] | null = null
  try {
    const parsed = r.acceptance ? JSON.parse(r.acceptance) : null
    if (Array.isArray(parsed) && parsed.length > 0) asserts = parsed
  } catch { /* 原文显示 */ }
  return (
    <Modal open={!!r} title={<Space><Tag color="gold">{r.code}</Tag>{r.title}</Space>} onCancel={onClose} footer={null} width={640}>
      <Space direction="vertical" size={10} style={{ width: '100%' }}>
        <Space wrap size={6}>
          <Tag color={sv.color}>{sv.text}</Tag>
          {r.depends_on.length > 0 && <Tag>依赖 {r.depends_on.join('、')}</Tag>}
          {r.source && <Tooltip title="需求口径的确认来源"><Tag>来源 {r.source}</Tag></Tooltip>}
        </Space>
        {r.description && (
          <div style={{ fontSize: 13, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{r.description}</div>
        )}
        {asserts && (
          <div>
            <Typography.Text strong style={{ fontSize: 12 }}>✅ 验收断言</Typography.Text>
            <div style={{ marginTop: 4 }}>
              {asserts.map((a, i) => (
                <div key={i} style={{ fontSize: 12, lineHeight: '22px' }}>
                  {a.type ? <Tag style={{ marginInlineEnd: 6 }}>{String(a.type)}</Tag> : null}
                  <span>{a.assert ? String(a.assert) : JSON.stringify(a)}</span>
                </div>
              ))}
            </div>
          </div>
        )}
        <div>
          <Typography.Text strong style={{ fontSize: 12 }}>🔗 追溯链（{r.chain.length} 步）</Typography.Text>
          {r.chain.length === 0 && <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', marginTop: 4 }}>尚无关联步骤</div>}
          <div style={{ marginTop: 4 }}>
            {r.chain.map((s) => (
              <div key={`${s.stage}-${s.iteration}-${s.seq}`}
                onClick={() => onOpenStep(s)}
                style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '5px 8px', borderRadius: 6, cursor: 'pointer', fontSize: 12 }}
                onMouseEnter={(e) => { e.currentTarget.style.background = '#f5f5f5' }}
                onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent' }}>
                <Tag color={STAGE_TAG_COLOR[s.stage] || 'default'} style={{ marginInlineEnd: 0, flexShrink: 0 }}>
                  {s.stage === 'REQUIREMENT' ? '需求' : s.stage === 'DEVELOP' ? '开发' : '运维'}
                </Tag>
                <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>#{s.seq} {s.name}</span>
                <Tag color={STEP_STATUS_COLOR[s.status] || 'default'} style={{ marginInlineEnd: 0, flexShrink: 0 }}>{STEP_STATUS_TEXT[s.status] || s.status}</Tag>
              </div>
            ))}
          </div>
        </div>
      </Space>
    </Modal>
  )
}

function StepDetailModal({ projectId, s, onClose, onChanged }: { projectId: string; s: TraceStep | null; onClose: () => void; onChanged?: () => void }) {
  const [busy, setBusy] = useState<string | null>(null)
  if (!s) return null
  const act = (action: 'terminate' | 'retry') => {
    setBusy(action)
    apiJson<{ message?: string }>(`/api/projects/${projectId}/stage/step/${s.seq}/${action}`, 'POST')
      .then((r) => {
        message.success(r.message || (action === 'terminate' ? '已终止' : '已重新发起'))
        onChanged?.()
        onClose()
      })
      .catch((e: Error) => message.error(e.message))
      .finally(() => setBusy(null))
  }
  return (
    <Modal open={!!s} title={<Space><Tag color={STAGE_TAG_COLOR[s.stage]}>{s.stage === 'REQUIREMENT' ? '需求' : s.stage === 'DEVELOP' ? '开发' : '运维'}#{s.seq}</Tag>{s.name}</Space>} onCancel={onClose} footer={null} width={720}>
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Space wrap size={6}>
          <Tag color={STEP_STATUS_COLOR[s.status] || 'default'}>{STEP_STATUS_TEXT[s.status] || s.status}</Tag>
          {s.verified_by === 'tool' && <Tag color="cyan">机器验证</Tag>}
          {s.verified_by === 'human' && <Tag color="purple">人确认</Tag>}
          {s.verified_by === 'self' && <Tooltip title="AI 自述完成——未经机器判定或人确认"><Tag>AI 自述</Tag></Tooltip>}
        </Space>
        {s.error && <Alert type="error" message={s.error} />}
        {/* 指令/问题（2026-10-19 用户反馈「点击没内容」）：PLANNED 步没有产出，指令就是它的全部内容——
            DECISION 显示向用户提的问题，TASK/DISCUSS 显示执行指令 */}
        {s.instruction && (
          <div style={{ background: '#f8f9fb', border: '1px solid #eef0f3', borderRadius: 6, padding: '8px 12px' }}>
            <Typography.Text strong style={{ fontSize: 12 }}>{s.step_type === 'DECISION' ? '❓ 向你提出的问题' : '📌 执行指令'}</Typography.Text>
            <div style={{ fontSize: 12, marginTop: 4, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{s.instruction}</div>
          </div>
        )}
        {s.status === 'EXECUTING' && (
          <Alert type="info" showIcon message="正在执行（同步无超时，做完为止）——不想等可以直接终止，主控会跳过它进入下一步"
            action={<Button size="small" danger loading={busy === 'terminate'} onClick={() => act('terminate')}>终止</Button>} />
        )}
        {(s.status === 'FAILED' || s.status === 'SKIPPED') && (
          <Button size="small" type="primary" ghost loading={busy === 'retry'} onClick={() => act('retry')}>重试此步骤</Button>
        )}
        {s.requirement_codes && s.requirement_codes.length > 0 && (
          <Space wrap size={4}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>服务需求：</Typography.Text>
            {s.requirement_codes.map((c) => <Tag key={c} color="gold" style={{ fontSize: 11 }}>{c}</Tag>)}
            {s.requirement_codes.length >= 3 && (
              <Tooltip title="该步骤声明了几乎全部需求——追溯图每条链都会出现（公共步骤）">
                <Tag color="orange" style={{ fontSize: 11 }}>公共步骤</Tag>
              </Tooltip>
            )}
          </Space>
        )}
        {s.output_summary && <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>{s.output_summary}</Typography.Paragraph>}
        {(s.step_type === 'DISCUSS' || s.step_type === 'CONSULT') && s.artifact?.content_md && (
          <div>
            <Typography.Text strong style={{ fontSize: 12 }}>{s.step_type === 'DISCUSS' ? '📋 会议纪要' : '💬 问答全文'}</Typography.Text>
            <div style={{ maxHeight: '45vh', overflowY: 'auto', border: '1px solid #f0f0f0', borderRadius: 6, padding: '8px 12px', marginTop: 4, background: '#fafafa' }}>
              <MarkdownView text={s.artifact.content_md} />
            </div>
          </div>
        )}
        {s.artifact && <ArtifactLink projectId={projectId} artifact={s.artifact} />}
      </Space>
    </Modal>
  )
}

/** 产出证据链接：有文件路径走文件弹窗，否则提示看交付物折叠区。 */
function ArtifactLink({ projectId, artifact }: { projectId: string; artifact: TraceArtifact }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <a onClick={() => setOpen(true)} style={{ fontSize: 12 }}>
        <FileTextOutlined /> 产出证据：{artifact.name}
      </a>
      {open && (
        <ArtifactModal projectId={projectId} path={artifact.file_path} name={artifact.name} contentMd={artifact.content_md} onClose={() => setOpen(false)} />
      )}
    </>
  )
}


export function TracePanel({ projectId, refreshKey = 0, exec, artifacts = [], onRefresh }: {
  projectId: string
  refreshKey?: number
  /** 执行视图：待决策区数据源（DECISION 步 WAITING/PLANNED 即待答复） */
  exec: StageExecution | null
  /** 全部交付物（kind=DELIVERABLE）：链路证据之外的登记型交付物也在这里可见 */
  artifacts?: { id: number; name: string; kind: string; stage?: string | null; content_md?: string | null; version?: number | null }[]
  onRefresh?: () => void
}) {
  const [data, setData] = useState<TraceData | null>(null)
  const [loading, setLoading] = useState(true)
  const [viewing, setViewing] = useState<TraceArtifact | null>(null)
  /** 待答复决策步：点「处理」打开答复 Modal（有未处理决策时左侧对话输入被锁定，这里是唯一解锁通道） */
  const [decisionStep, setDecisionStep] = useState<StageStepRow | null>(null)
  /** 矩阵节点点击详情 */
  const [detailStep, setDetailStep] = useState<TraceStep | null>(null)
  /** 需求胶囊点击详情（2026-10-19：点需求看需求本身，不再借道第一个步骤） */
  const [detailReq, setDetailReq] = useState<TraceReq | null>(null)
  const [detailCase, setDetailCase] = useState<NonNullable<TraceData['test_cases']>[number] | null>(null)
  const [detailBug, setDetailBug] = useState<NonNullable<TraceData['bugs']>[number] | null>(null)
  // P0 验收责任链：验收操作状态（hooks 必须在条件 return 之前——React #310 教训）
  const [acceptBusy, setAcceptBusy] = useState(false)
  const [rejectStep, setRejectStep] = useState<StageStepRow | null>(null)
  const [rejectComment, setRejectComment] = useState('')

  const load = () => {
    setLoading(true)
    apiJson<TraceData>(`/api/projects/${projectId}/trace`)
      .then(setData)
      .catch((e: Error) => message.error(e.message))
      .finally(() => setLoading(false))
  }
  useEffect(() => { load() }, [projectId, refreshKey])

  if (loading && !data) return <Spin style={{ display: 'block', margin: '40px auto' }} />
  if (!data) return <Empty description="追溯数据加载失败" />

  const stepItems = data.stages.map((s) => ({
    title: s.label,
    description: s.anchor ? <Typography.Text type="secondary" style={{ fontSize: 11 }} ellipsis>{s.anchor}</Typography.Text> : null,
    status: (s.status === 'DONE' ? 'finish' : s.status === 'ACTIVE' ? 'process' : 'wait') as 'finish' | 'process' | 'wait',
  }))

  // 待答复决策步（新内核口径）：DECISION 且未 DONE/SKIPPED——决策是链路上的活结，置顶展示
  const pendingDecisions = (exec?.steps || []).filter(
    (s) => s.stepType === 'DECISION' && s.status !== 'DONE' && s.status !== 'SKIPPED',
  )
  // 验收责任链（P0）：human 验收步执行完停在 WAITING_ACCEPT——用户通过才 DONE，打回带原因重做
  const pendingAccepts = (exec?.steps || []).filter((s) => s.status === 'WAITING_ACCEPT')

  /** 用户验收：通过=DONE(verified_by=human) 主控被唤醒继续；打回=FAILED 带原因，主控组织重做。 */
  const submitAccept = async (seq: number, pass: boolean, comment: string) => {
    setAcceptBusy(true)
    try {
      await apiJson(`/api/projects/${projectId}/stage/step/${seq}/accept`, 'POST', { pass, comment })
      message.success(pass ? '验收通过，主控继续推进' : '已打回，主控会组织重做')
      setRejectStep(null)
      setRejectComment('')
      if (onRefresh) onRefresh()
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e))
    } finally {
      setAcceptBusy(false)
    }
  }
  const deliverables = artifacts.filter((a) => a.kind === 'DELIVERABLE')

  return (
    <div>
      {/* 三阶段流水线：项目走到哪儿了，一眼可见 */}
      <Steps size="small" items={stepItems} style={{ marginBottom: 16 }} />

      {/* 待验收区（P0 验收责任链）：human 验收步停在 WAITING_ACCEPT，用户裁决后才算 DONE */}
      {pendingAccepts.length > 0 && (
        <Card size="small" style={{ marginBottom: 10, borderColor: '#722ed1' }}>
          <Space wrap size={6} style={{ marginBottom: 6 }}>
            <AuditOutlined style={{ color: '#722ed1' }} />
            <Typography.Text strong>{pendingAccepts.length} 个交付待验收</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>验收通过后主控继续推进；不合规打回并写明问题</Typography.Text>
          </Space>
          {pendingAccepts.map((s) => (
            <div key={s.id} style={{ padding: '4px 0', borderTop: '1px solid #f5f5f5' }}>
              <Space wrap size={6}>
                <Tag style={{ marginInlineEnd: 0 }} color="purple">验收 #{s.seq}</Tag>
                <Typography.Text style={{ fontSize: 13 }}>{s.name}</Typography.Text>
                <Button size="small" onClick={() => setDetailStep(s as never)}>看产出</Button>
                <Button size="small" type="primary" loading={acceptBusy}
                  onClick={() => submitAccept(s.seq, true, '')}>
                  验收通过
                </Button>
                <Button size="small" danger onClick={() => { setRejectStep(s); setRejectComment('') }}>
                  打回
                </Button>
              </Space>
            </div>
          ))}
        </Card>
      )}

      {/* 待决策区：链路上的活结——不处理，主控就停在这里（对话输入同时被锁定） */}
      {pendingDecisions.length > 0 && (
        <Card size="small" style={{ marginBottom: 10, borderColor: '#fa8c16' }}>
          <Space wrap size={6} style={{ marginBottom: 6 }}>
            <LockOutlined style={{ color: '#fa8c16' }} />
            <Typography.Text strong>{pendingDecisions.length} 个决策待处理</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>主控在等你的决定，答复后继续推进</Typography.Text>
          </Space>
          {pendingDecisions.map((s) => (
            <div key={s.id} style={{ padding: '4px 0', borderTop: '1px solid #f5f5f5' }}>
              <Space wrap size={6}>
                <Tag style={{ marginInlineEnd: 0 }} color="orange">决策 #{s.seq}</Tag>
                <Typography.Text style={{ fontSize: 13 }}>{s.name}</Typography.Text>
                <Button size="small" type="primary" onClick={() => setDecisionStep(s)}>
                  处理
                </Button>
              </Space>
            </div>
          ))}
        </Card>
      )}

      {data.requirements.length === 0 && data.unlinked.length === 0
        ? <Empty description="尚无需求项——需求确认后这里会长出完整追溯链" />
        : (
          <Card size="small" title="追溯拓扑" style={{ marginBottom: 10 }}>
            <TraceGraph data={{ stages: data.stages, requirements: data.requirements, unlinked: data.unlinked, test_cases: data.test_cases, bugs: data.bugs }} onOpenStep={setDetailStep} onOpenReq={setDetailReq} onOpenCase={setDetailCase} onOpenBug={setDetailBug} />
          </Card>
        )}

      {/* 交付物与历史会话收进折叠区：真相主线（需求链路）不被打断，登记型内容按需展开 */}
      <Collapse
        size="small"
        style={{ marginTop: 8 }}
        items={[
          {
            key: 'deliverables',
            label: `交付物 ${deliverables.length}`,
            children: deliverables.length === 0
              ? <Empty description="暂无交付物" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              : [...deliverables].reverse().map((a) => (
                <Card key={a.id} size="small" style={{ marginBottom: 8, cursor: 'pointer' }} onClick={() => setViewing({ name: a.name, file_path: null, kind: 'DELIVERABLE', content_md: a.content_md })}>
                  <Space>
                    <FileTextOutlined />
                    <Typography.Text strong>{a.name}</Typography.Text>
                    {a.version != null && <Tag style={{ marginInlineEnd: 0 }}>v{a.version}</Tag>}
                  </Space>
                </Card>
              )),
          },
          {
            key: 'sessions',
            label: '历史会话',
            children: <StageSessionsPanel projectId={projectId} />,
          },
        ]}
      />

      {viewing && (
        <ArtifactModal projectId={projectId} path={viewing.file_path} name={viewing.name} contentMd={viewing.content_md} onClose={() => setViewing(null)} />
      )}
      <DecisionAnswerModal
        projectId={projectId}
        step={decisionStep}
        onClose={() => setDecisionStep(null)}
        onAnswered={() => { if (onRefresh) onRefresh() }}
      />
      {/* 打回验收：原因必填——主控拿打回原因组织重做（原地重试，追溯图节点不变） */}
      <Modal
        open={!!rejectStep}
        title={`打回：${rejectStep?.name || ''}`}
        okText="确认打回"
        okButtonProps={{ danger: true, disabled: !rejectComment.trim(), loading: acceptBusy }}
        cancelText="取消"
        onOk={() => rejectStep && submitAccept(rejectStep.seq, false, rejectComment.trim())}
        onCancel={() => setRejectStep(null)}
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          打回后该步骤置为失败，主控会带着你的打回原因组织重做。
        </Typography.Paragraph>
        <Input.TextArea
          rows={3}
          value={rejectComment}
          onChange={(e) => setRejectComment(e.target.value)}
          placeholder="写明打回原因（必填）：哪里不合规、期望改成什么样"
        />
      </Modal>
      <RequirementDetailModal
        r={detailReq}
        onOpenStep={(s) => { setDetailReq(null); setDetailStep(s) }}
        onClose={() => setDetailReq(null)}
      />
      <StepDetailModal projectId={projectId} s={detailStep} onClose={() => setDetailStep(null)} onChanged={load} />
      {/* 用例详情弹窗（V36 交付图节点下钻）：状态/正文/关联需求/关联 bug */}
      <Modal open={!!detailCase} title={detailCase ? `${detailCase.code} ${detailCase.title}` : ''} onCancel={() => setDetailCase(null)} footer={null} width={560}>
        {detailCase && (
          <div style={{ display: 'grid', gap: 10 }}>
            <Space size={6}>
              <Tag color={detailCase.status === 'PASSED' ? 'green' : detailCase.status === 'FAILED' ? 'red' : detailCase.status === 'BLOCKED' ? 'orange' : 'default'}>
                {{ ACTIVE: '待执行', PASSED: '通过', FAILED: '失败', BLOCKED: '阻塞' }[detailCase.status] || detailCase.status}
              </Tag>
              {detailCase.requirement_code && <Tag color='gold'>{detailCase.requirement_code}</Tag>}
            </Space>
            <div>
              <Typography.Text strong style={{ fontSize: 12 }}>📋 用例正文</Typography.Text>
              <div style={{ fontSize: 12, marginTop: 4, whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: '#f8f9fb', border: '1px solid #eef0f3', borderRadius: 6, padding: '8px 12px', maxHeight: '45vh', overflowY: 'auto' }}>
                {detailCase.content || '（未填写正文——用例只有标题，建议测试设计时补前置/步骤/预期）'}
              </div>
            </div>
            {(() => {
              const linked = (data.bugs || []).filter((b) => b.test_case_code === detailCase.code)
              return linked.length > 0 ? (
                <div>
                  <Typography.Text strong style={{ fontSize: 12 }}>🐛 发现的 bug（{linked.length}）</Typography.Text>
                  <div style={{ marginTop: 4 }}>
                    {linked.map((b) => (
                      <div key={b.code} style={{ fontSize: 12, padding: '3px 0', borderTop: '1px solid #f0f0f0' }}>
                        <Tag color={b.status === 'OPEN' ? 'red' : b.status === 'FIXING' ? 'orange' : b.status === 'FIXED' ? 'green' : 'default'} style={{ marginInlineEnd: 4 }}>{b.severity}</Tag>
                        {b.code} {b.title}
                      </div>
                    ))}
                  </div>
                </div>
              ) : null
            })()}
          </div>
        )}
      </Modal>
      {/* bug 详情弹窗（V36 交付图节点下钻）：严重度/状态/正文/发现自用例 */}
      <Modal open={!!detailBug} title={detailBug ? `${detailBug.code} ${detailBug.title}` : ''} onCancel={() => setDetailBug(null)} footer={null} width={560}>
        {detailBug && (
          <div style={{ display: 'grid', gap: 10 }}>
            <Space size={6}>
              <Tag color={detailBug.severity === 'P0' ? 'red' : detailBug.severity === 'P1' ? 'volcano' : detailBug.severity === 'P2' ? 'orange' : 'default'}>{detailBug.severity}</Tag>
              <Tag color={detailBug.status === 'OPEN' ? 'red' : detailBug.status === 'FIXING' ? 'orange' : detailBug.status === 'FIXED' ? 'green' : 'default'}>
                {{ OPEN: '待修复', FIXING: '修复中', FIXED: '已修复', CLOSED: '已关闭', REJECTED: '误报' }[detailBug.status] || detailBug.status}
              </Tag>
              {detailBug.requirement_code && <Tag color='gold'>{detailBug.requirement_code}</Tag>}
            </Space>
            {detailBug.test_case_code && (
              <Typography.Text type='secondary' style={{ fontSize: 12 }}>发现自用例：{detailBug.test_case_code}</Typography.Text>
            )}
            <div>
              <Typography.Text strong style={{ fontSize: 12 }}>🐛 现象/根因/修复说明</Typography.Text>
              <div style={{ fontSize: 12, marginTop: 4, whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: '#f8f9fb', border: '1px solid #eef0f3', borderRadius: 6, padding: '8px 12px', maxHeight: '45vh', overflowY: 'auto' }}>
                {detailBug.content || '（未填写说明）'}
              </div>
            </div>
          </div>
        )}
      </Modal>
    </div>
  )
}
