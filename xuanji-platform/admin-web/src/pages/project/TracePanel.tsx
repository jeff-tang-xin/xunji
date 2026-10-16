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
import { Alert, Card, Empty, message, Modal, Space, Spin, Steps, Tag, Timeline, Tooltip, Typography } from 'antd'
import { FileTextOutlined } from '@ant-design/icons'
import { apiJson } from '../../api/client'

interface TraceArtifact { name: string; file_path: string | null; kind: string }
interface TraceStep {
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
const REQ_STATUS_COLOR: Record<string, string> = {
  CONFIRMED: 'green', IN_PROGRESS: 'blue', PROPOSED: 'gold', DONE: 'cyan',
}
const STAGE_TAG_COLOR: Record<string, string> = { REQUIREMENT: 'gold', DEVELOP: 'geekblue', OPERATE: 'volcano' }

/** 步骤→产出证据的文件内容弹窗（复用 /files/content，≤200KB 文本）。 */
function ArtifactModal({ projectId, path, name, onClose }: { projectId: string; path: string | null; name: string; onClose: () => void }) {
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
        {err ? <Alert type="error" message={err} /> : <pre style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>{content ?? '加载中…'}</pre>}
      </div>
    </Modal>
  )
}

function StepNode({ s, onOpen }: { s: TraceStep; onOpen: (a: TraceArtifact) => void }) {
  const color = STEP_STATUS_COLOR[s.status] || 'default'
  return (
    <div style={{ padding: '2px 0' }}>
      <Space wrap size={6}>
        <Tag style={{ marginInlineEnd: 0 }} color={STAGE_TAG_COLOR[s.stage]}>{s.stage === 'REQUIREMENT' ? '需求' : s.stage === 'DEVELOP' ? '开发' : '运维'}#{s.seq}</Tag>
        <Typography.Text style={{ fontSize: 13 }}>{s.name}</Typography.Text>
        <Tag style={{ marginInlineEnd: 0 }} color={color}>{STEP_STATUS_TEXT[s.status] || s.status}</Tag>
        {s.verified_by === 'tool' && <Tag style={{ marginInlineEnd: 0 }} color="cyan">机器验证</Tag>}
        {s.verified_by === 'human' && <Tag style={{ marginInlineEnd: 0 }} color="purple">人确认</Tag>}
        {s.artifact && (
          <a onClick={() => onOpen(s.artifact!)} style={{ fontSize: 12 }}>
            <FileTextOutlined /> {s.artifact.name}
          </a>
        )}
      </Space>
      {s.error && (
        <Typography.Paragraph type="danger" style={{ fontSize: 12, marginBottom: 0 }} ellipsis>
          {s.error}
        </Typography.Paragraph>
      )}
    </div>
  )
}

export function TracePanel({ projectId, refreshKey = 0 }: { projectId: string; refreshKey?: number }) {
  const [data, setData] = useState<TraceData | null>(null)
  const [loading, setLoading] = useState(true)
  const [viewing, setViewing] = useState<TraceArtifact | null>(null)

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

  return (
    <div>
      {/* 三阶段流水线：项目走到哪儿了，一眼可见 */}
      <Steps size="small" items={stepItems} style={{ marginBottom: 16 }} />

      {data.requirements.length === 0
        ? <Empty description="尚无需求项——需求确认后这里会长出完整追溯链" />
        : data.requirements.map((r) => {
          const done = r.chain.filter((s) => s.status === 'DONE').length
          return (
            <Card key={r.code} size="small" style={{ marginBottom: 10 }}>
              <Space wrap size={6} style={{ marginBottom: r.chain.length ? 8 : 0 }}>
                <Typography.Text strong>{r.code}</Typography.Text>
                <Typography.Text style={{ fontSize: 13 }}>{r.title}</Typography.Text>
                <Tag style={{ marginInlineEnd: 0 }} color={REQ_STATUS_COLOR[r.status] || 'default'}>{r.status}</Tag>
                {r.depth > 0 && <Tooltip title={`依赖前置需求：${r.depends_on.join('、')}`}><Tag style={{ marginInlineEnd: 0 }}>依赖深度 {r.depth}</Tag></Tooltip>}
                {r.chain.length > 0 && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{done}/{r.chain.length} 步完成</Typography.Text>}
              </Space>
              {r.chain.length > 0 && (
                <Timeline
                  style={{ marginBottom: 0 }}
                  items={r.chain.map((s) => ({
                    color: s.status === 'DONE' ? 'green' : s.status === 'FAILED' ? 'red' : s.status === 'EXECUTING' || s.status === 'WAITING' ? 'blue' : 'gray',
                    children: <StepNode s={s} onOpen={setViewing} />,
                  }))}
                />
              )}
            </Card>
          )
        })}

      {data.unlinked.length > 0 && (
        <Card size="small" style={{ marginTop: 8, borderColor: '#faad14' }}>
          <Typography.Text type="warning" style={{ fontSize: 12 }}>
            {data.unlinked.length} 个工作项未声明需求关联（决策/求助步除外）——链路断点，主控应补 requirement_codes
          </Typography.Text>
          <div style={{ marginTop: 6 }}>
            {data.unlinked.map((s) => <StepNode key={`${s.stage}-${s.iteration}-${s.seq}`} s={s} onOpen={setViewing} />)}
          </div>
        </Card>
      )}

      {viewing && (
        <ArtifactModal projectId={projectId} path={viewing.file_path} name={viewing.name} onClose={() => setViewing(null)} />
      )}
    </div>
  )
}
