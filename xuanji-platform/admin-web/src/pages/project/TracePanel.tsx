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
import { Alert, Button, Card, Collapse, Empty, message, Modal, Space, Spin, Steps, Tag, Tooltip, Typography } from 'antd'
import { FileTextOutlined, LockOutlined } from '@ant-design/icons'
import type { StageExecution, StageStepRow } from './types'
import { TraceGraph } from './TraceGraph'
import { DecisionAnswerModal } from './DecisionAnswerModal'
import { StageSessionsPanel } from './StageSessionsPanel'
import { apiJson } from '../../api/client'

interface TraceArtifact { name: string; file_path: string | null; kind: string; content_md?: string | null }
export interface TraceStep {
  stage: string; iteration: number; seq: number; name: string
  step_type: string; status: string; verified_by: string | null
  error: string | null; output_summary: string | null; artifact: TraceArtifact | null
}
interface TraceReq {
  code: string; title: string; status: string; depth: number
  depends_on: string[]; chain: TraceStep[]
}
interface TraceData {
  current_stage: string
  stages: { stage: string; label: string; iteration: number; status: string; anchor: string | null }[]
  requirements: TraceReq[]
  unlinked: TraceStep[]
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
function StepDetailModal({ projectId, s, onClose }: { projectId: string; s: TraceStep | null; onClose: () => void }) {
  return (
    <Modal open={!!s} title={<Space><Tag color={STAGE_TAG_COLOR[s?.stage || '']}>{s?.stage === 'REQUIREMENT' ? '需求' : s?.stage === 'DEVELOP' ? '开发' : '运维'}#{s?.seq}</Tag>{s?.name}</Space>} onCancel={onClose} footer={null} width={680}>
      {s && (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space wrap size={6}>
            <Tag color={STEP_STATUS_COLOR[s.status] || 'default'}>{STEP_STATUS_TEXT[s.status] || s.status}</Tag>
            {s.verified_by === 'tool' && <Tag color="cyan">机器验证</Tag>}
            {s.verified_by === 'human' && <Tag color="purple">人确认</Tag>}
            {s.verified_by === 'self' && <Tooltip title="AI 自述完成——未经机器判定或人确认"><Tag>AI 自述</Tag></Tooltip>}
          </Space>
          {s.error && <Alert type="error" message={s.error} />}
          {s.output_summary && <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>{s.output_summary}</Typography.Paragraph>}
          {s.artifact && <ArtifactLink projectId={projectId} artifact={s.artifact} />}
        </Space>
      )}
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
  const deliverables = artifacts.filter((a) => a.kind === 'DELIVERABLE')

  return (
    <div>
      {/* 三阶段流水线：项目走到哪儿了，一眼可见 */}
      <Steps size="small" items={stepItems} style={{ marginBottom: 16 }} />

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
            <TraceGraph data={{ stages: data.stages, requirements: data.requirements, unlinked: data.unlinked }} onOpenStep={setDetailStep} />
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
      <StepDetailModal projectId={projectId} s={detailStep} onClose={() => setDetailStep(null)} />
    </div>
  )
}
