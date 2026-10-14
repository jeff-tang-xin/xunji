import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import {
  Alert,
  Button,
  Card,
  Collapse,
  Empty,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import {
  CheckCircleOutlined,
  ReloadOutlined,
  ProfileOutlined,
  SyncOutlined,
  LockOutlined,
} from '@ant-design/icons'
import { apiJson } from '../../api/client'
import { EXEC_STATE_COLOR, EXEC_STATE_LABEL, NODE_RUN_STATUS_COLOR, NODE_RUN_STATUS_LABEL, NodeRunRow, STEP_STATUS_COLOR, STEP_STATUS_LABEL, STEP_TYPE_LABEL, StageExecution, StageStepRow, WaitingDecision, isOrchestrated } from './types'
import { OutputBlock, MarkdownView } from './shared'
import { StageSettingsModal } from './StageSettingsModal'

export function StageExecutionPanel({ projectId, exec, busy, onRefresh, onChanged }: {
  projectId: string
  exec: StageExecution | null
  busy: boolean
  onRefresh: () => void
  onChanged: () => void
}) {
  const [gate, setGate] = useState<WaitingDecision | null>(null)
  const [decision, setDecision] = useState<'APPROVE' | 'REJECT' | 'SKIP'>('APPROVE')
  const [comment, setComment] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [gateErr, setGateErr] = useState<string | null>(null)
  const [approving, setApproving] = useState(false)
  const [planErr, setPlanErr] = useState<string | null>(null)
  const [planRejectOpen, setPlanRejectOpen] = useState(false)
  const [planReason, setPlanReason] = useState('')
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [rerunning, setRerunning] = useState(false)
  /** 阶段对话里用户的最近批注：决策门审批时「抄」过来做上下文——批注与决策本是同一条交互链 */
  const [stageNotes, setStageNotes] = useState<{ text: string; timestamp?: string }[]>([])

  // 决策门挂起时拉取本阶段对话的用户批注（最近 3 条），供审批时参考
  useEffect(() => {
    const stage = exec?.stage
    if (!stage || !(exec?.waiting_decisions || []).length) {
      setStageNotes([])
      return
    }
    let alive = true
    apiJson<{ messages?: { role?: string; text?: string; timestamp?: string }[] }>(
      `/api/projects/${projectId}/stage/chat/history?stage=${stage}&iteration=${exec?.iteration || 1}`,
    )
      .then((r) => {
        if (!alive) return
        const notes = (r?.messages || [])
          .filter((m) => m.role === 'user' && (m.text || '').trim())
          .slice(-3)
          .map((m) => ({ text: m.text || '', timestamp: m.timestamp }))
        setStageNotes(notes)
      })
      .catch(() => { if (alive) setStageNotes([]) })
    return () => { alive = false }
  }, [projectId, exec?.stage, exec?.iteration, (exec?.waiting_decisions || []).length])
  /** 重跑当前阶段：旧执行（孤儿 RUNNING / FAILED）整体废弃，按既有定义直接重新起跑 */
  const rerunStage = async () => {
    const stage = exec?.stage
    if (!stage) return
    setRerunning(true)
    try {
      const r = await apiJson<{ run_id?: string }>(`/api/projects/${projectId}/stage/${stage}/rerun`, 'POST', {})
      message.success(`已重新起跑：run ${r?.run_id || ''}`)
      onRefresh()
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e))
    } finally {
      setRerunning(false)
    }
  }


  /**
   * 批准 / 驳回 PM 规划（POST /plan/{planId}/decide）。
   * 批准即「编译成画布 + 发布 + 起跑」；返回里的 skipped 是引用了未发布 agent 而被跳过的子任务，
   * 必须显式告知操作人——否则用户以为整条流程都跑了，实际少一步。
   */
  const decidePlan = async (planId: number, d: 'APPROVE' | 'REJECT', reason?: string) => {
    setApproving(true)
    setPlanErr(null)
    try {
      const r = await apiJson<{ skipped?: unknown[] }>(
        `/api/projects/${projectId}/plan/${planId}/decide`,
        'POST',
        d === 'APPROVE' ? { decision: 'APPROVE' } : { decision: 'REJECT', reason },
      )
      if (d === 'APPROVE') {
        const skipped = (r?.skipped || [])
          .map((s) => (typeof s === 'string' ? s : (s as { name?: string })?.name || ''))
          .filter(Boolean)
        message.success(skipped.length
          ? `规划已批准并起跑；${skipped.length} 个子任务被跳过：${skipped.join('、')}`
          : '规划已批准并起跑，节点在后台执行')
      } else {
        message.success('已驳回，PM 会重出一版草稿')
      }
      setPlanRejectOpen(false)
      onChanged()
    } catch (e) {
      setPlanErr(e instanceof Error ? e.message : String(e))
    } finally {
      setApproving(false)
    }
  }

  const openGate = (w: WaitingDecision, d: 'APPROVE' | 'REJECT' | 'SKIP') => {
    setGate(w)
    setDecision(d)
    setComment('')
    setGateErr(null)
  }

  const submitGate = async () => {
    if (!gate) return
    // 后端对驳回强制要求批注（空批注只会让 agent 重出一版同样的东西），前端先拦一道省一次往返
    if (decision === 'REJECT' && comment.trim().length === 0) {
      setGateErr('驳回必须写批注，否则无法告知 agent 要改什么')
      return
    }
    setSubmitting(true)
    setGateErr(null)
    try {
      await apiJson(`/api/projects/${projectId}/gate/decide`, 'POST', {
        node_id: gate.node_id,
        decision,
        comment: comment.trim() || undefined,
      })
      message.success(decision === 'APPROVE'
        ? `已放行「${gate.node_name}」，后续节点在后台执行`
        : decision === 'SKIP'
          ? `已跳过「${gate.node_name}」，从其后继续执行`
          : `已驳回「${gate.node_name}」，agent 将按批注重生成`)
      setGate(null)
      onChanged()
    } catch (e) {
      const m = e instanceof Error ? e.message : String(e)
      // 409 表示门已被别人放行或 run 状态已变；后端 reason 不进响应体，这里补可行动提示
      setGateErr(/409/.test(m) ? `${m}——待决策状态可能已变化，请刷新后重试` : m)
    } finally {
      setSubmitting(false)
    }
  }

  if (!exec) {
    return <Empty description="未取到执行信息" image={Empty.PRESENTED_IMAGE_SIMPLE} />
  }
  const nodes = exec.node_runs || []
  const waiting = exec.waiting_decisions || []
  const planId = exec.plan?.id
  // 新内核（AI 编排）模式：step 台账非空。右边栏只读化——进度/产出/决策仅展示，
  // 人驱动动作（重跑/门审批按钮）只在旧链路模式保留；决策答复走左侧对话。
  const orchestrated = isOrchestrated(exec)
  const steps = exec.steps || []
  const cols: ColumnsType<NodeRunRow> = [
    { title: '序号', dataIndex: 'seq', width: 52 },
    {
      title: '节点', dataIndex: 'node_name', width: 150,
      // 名称与 node_id 上下两行堆叠：右栏空间窄，横排 Space 会把列压成一字一行
      render: (v: string, r) => (
        <div>
          <div>{v || r.node_id}</div>
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>{r.node_id}</Typography.Text>
        </div>
      ),
    },
    {
      title: '状态', dataIndex: 'status', width: 84,
      render: (v: string) => <Tag color={NODE_RUN_STATUS_COLOR[v] || 'default'}>{NODE_RUN_STATUS_LABEL[v] || v}</Tag>,
    },
    {
      title: '开始', dataIndex: 'started_at', width: 80,
      render: (v?: string) => (v ? String(v).replace('T', ' ').slice(11, 19) : '—'),
    },
    {
      title: '结束', dataIndex: 'finished_at', width: 80,
      render: (v?: string) => (v ? String(v).replace('T', ' ').slice(11, 19) : '—'),
    },
    {
      title: '错误', dataIndex: 'error', width: 90, ellipsis: true,
      render: (v?: string | null) => (v
        ? <Tooltip title={v}><Typography.Text type="danger" style={{ fontSize: 12 }}>{v}</Typography.Text></Tooltip>
        : '—'),
    },
  ]

  /** 编排步骤表（新内核只读台账）：产出摘要直接看，全文/理由/错误展开看 */
  const stepCols: ColumnsType<StageStepRow> = [
    { title: '#', dataIndex: 'seq', width: 44 },
    {
      title: '步骤', dataIndex: 'name', width: 170,
      render: (v: string, r) => (
        <div>
          <div>{v || `步骤 ${r.seq}`}</div>
          {(r.participants || []).length > 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>{(r.participants || []).join('、')}</Typography.Text>
          )}
        </div>
      ),
    },
    {
      title: '类型', dataIndex: 'stepType', width: 64,
      render: (v: string) => <Tag style={{ marginInlineEnd: 0 }}>{STEP_TYPE_LABEL[v] || v}</Tag>,
    },
    {
      title: '执行者', dataIndex: 'agent', width: 110, ellipsis: true,
      render: (v?: string | null) => (v ? <Tooltip title={v}><span style={{ fontSize: 12 }}>{v}</span></Tooltip> : '—'),
    },
    {
      title: '状态', dataIndex: 'status', width: 84,
      render: (v: string) => <Tag color={STEP_STATUS_COLOR[v] || 'default'}>{STEP_STATUS_LABEL[v] || v}</Tag>,
    },
    {
      title: '产出 / 说明', dataIndex: 'outputSummary', ellipsis: true,
      render: (v, r) => {
        const txt = v || r.skipReason || r.error || (r.status === 'WAITING' ? r.instruction : '') || '—'
        return (
          <Tooltip title={txt}>
            <Typography.Text style={{ fontSize: 12 }} type={r.status === 'FAILED' ? 'danger' : undefined}>
              {txt}
            </Typography.Text>
          </Tooltip>
        )
      },
    },
  ]

  return (
    <>
      <Space wrap style={{ marginBottom: 12 }} align="center">
        <Tag color={EXEC_STATE_COLOR[exec.state] || 'default'}>
          {EXEC_STATE_LABEL[exec.state] || exec.state}
        </Tag>
        {exec.run_id && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            run {exec.run_id}（{exec.run_status}）{exec.definition_id ? ` · def ${exec.definition_id}` : ''}
          </Typography.Text>
        )}
        <Button size="small" icon={<ReloadOutlined />} loading={busy} onClick={onRefresh}>刷新</Button>
        {exec.stage && (
          <Button size="small" icon={<ProfileOutlined />} onClick={() => setSettingsOpen(true)}>
            阶段设置
          </Button>
        )}
        {!orchestrated && exec.state === 'EXECUTING' && exec.run_status === 'FAILED' && (
          <Button danger size="small" icon={<SyncOutlined />} loading={rerunning} onClick={() => void rerunStage()}>
            重跑当前阶段
          </Button>
        )}
      </Space>

      {/* 阶段设置：绑定工作流 / 阶段智能体；保存后刷新执行视图（绑定影响下一次批准起跑） */}
      {exec.stage && (
        <StageSettingsModal
          open={settingsOpen}
          projectId={projectId}
          stage={exec.stage}
          onClose={() => setSettingsOpen(false)}
          onSaved={onRefresh}
        />
      )}

      {exec.error && (
        <Alert type="error" showIcon message="执行报错" description={exec.error} style={{ marginBottom: 12 }} />
      )}

      {exec.state === 'PENDING_APPROVAL' && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="在左侧对话里说一句话即可启动"
          description={
            <Space direction="vertical" size={4}>
              <span>AI 驱动：你的第一句话就是需求，主控会自动生成执行计划并起跑，不需要手动批准。规划内容可在对话里随时调整。</span>
              {exec.plan?.id != null && (
                <Space>
                  <Button size="small" type="primary" loading={approving} onClick={() => { const pid = exec.plan?.id; if (pid != null) void decidePlan(pid, 'APPROVE') }}>
                    立即批准并起跑
                  </Button>
                  <Button size="small" loading={approving} onClick={() => { setPlanErr(null); setPlanRejectOpen(true) }}>
                    驳回重规划
                  </Button>
                </Space>
              )}
            </Space>
          }
        />
      )}

      {waiting.length > 0 && (
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          {waiting.map((w) => (
            <Card
              key={String(w.node_run_id)}
              size="small"
              type="inner"
              title={<Space><LockOutlined /><span>待决策：{w.node_name || w.node_id}</span></Space>}
              extra={orchestrated ? (
                <Tag color="gold" style={{ marginInlineEnd: 0 }}>在左侧对话中答复</Tag>
              ) : (
                <Space>
                  <Button type="primary" size="small" icon={<CheckCircleOutlined />} onClick={() => openGate(w, 'APPROVE')}>
                    通过
                  </Button>
                  <Button size="small" onClick={() => openGate(w, 'SKIP')}>跳过</Button>
                  <Button danger size="small" onClick={() => openGate(w, 'REJECT')}>驳回</Button>
                </Space>
              )}
            >
              <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
                {orchestrated
                  ? '主控 AI 在等待你的决定——直接在左侧对话里回复即可（例如「同意，按方案二执行」），主控会记录答复并继续推进。'
                  : '该节点已产出并挂起等待人工决策。通过后继续执行后续节点（后台跑，页面会自动刷新）；驳回必须写批注。'}
              </Typography.Paragraph>
              {stageNotes.length > 0 && (
                <div style={{ marginBottom: 8, padding: '6px 10px', background: '#fafafa', borderRadius: 4 }}>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    阶段对话里的最近批注（决策与批注是同一条交互链，审批时一并参考）：
                  </Typography.Text>
                  {stageNotes.map((n, i) => (
                    <Typography.Paragraph key={i} style={{ fontSize: 12, margin: '4px 0 0', whiteSpace: 'pre-wrap' }}>
                      · {n.text.length > 160 ? n.text.slice(0, 160) + '…' : n.text}
                    </Typography.Paragraph>
                  ))}
                </div>
              )}
              <Collapse
                size="small"
                items={[{ key: 'out', label: '查看节点产出', children: <OutputBlock raw={w.output} /> }]}
              />
            </Card>
          ))}
        </Space>
      )}

      {orchestrated ? (
        steps.length > 0 ? (
          <Table
            size="small"
            rowKey={(r) => String(r.id)}
            dataSource={steps}
            columns={stepCols}
            pagination={false}
            expandable={{
              expandedRowRender: (r) => (
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  {r.instruction && (
                    <div>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>指令 / 问题：</Typography.Text>
                      <Typography.Paragraph style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap' }}>{r.instruction}</Typography.Paragraph>
                    </div>
                  )}
                  {r.skipReason && (
                    <Typography.Text type="warning" style={{ fontSize: 12 }}>跳过理由：{r.skipReason}</Typography.Text>
                  )}
                  {r.error && <Typography.Text type="danger" style={{ fontSize: 12 }}>错误：{r.error}</Typography.Text>}
                  {r.outputFull && <MarkdownView scroll={false} text={r.outputFull} />}
                </Space>
              ),
              rowExpandable: (r) => !!(r.outputFull || r.skipReason || r.error || r.instruction),
            }}
          />
        ) : (
          <Empty description="主控尚未规划本阶段步骤——在左侧对话里说一句话即可启动" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        )
      ) : nodes.length > 0 ? (
        <Table
          size="small"
          rowKey={(r) => String(r.id)}
          dataSource={nodes}
          columns={cols}
          pagination={false}
          scroll={{ x: 540 }}
          expandable={{
            expandedRowRender: (r) => <OutputBlock raw={r.output_json} />,
            rowExpandable: (r) => !!r.output_json,
          }}
        />
      ) : (
        <Empty
          description={exec.state === 'NO_PLAN'
            ? '在左侧对话里说一句话即可启动——你的第一句话就是需求，主控 AI 会自动规划并执行'
            : '本阶段还没有执行记录（规划未批准或未起跑）'}
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        />
      )}

      <Modal
        open={!!gate}
        title={`${decision === 'APPROVE' ? '通过' : decision === 'SKIP' ? '跳过' : '驳回'}决策门：${gate?.node_name || gate?.node_id || ''}`}
        okText={decision === 'APPROVE' ? '确认通过' : decision === 'SKIP' ? '确认跳过' : '确认驳回'}
        okButtonProps={{ danger: decision === 'REJECT', loading: submitting }}
        onOk={() => void submitGate()}
        onCancel={() => setGate(null)}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          节点 {gate?.node_id} · run {exec.run_id}
        </Typography.Paragraph>
        <Typography.Text style={{ display: 'block', marginBottom: 6 }}>
          {decision === 'REJECT'
            ? '驳回批注（必填，agent 会按它重生成）'
            : decision === 'SKIP'
              ? '跳过原因（可选，仅留档）——该节点不执行、无产出，下游按缺省处理'
              : '通过批注（可选，仅留档）'}
        </Typography.Text>
        <Input.TextArea
          rows={4}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          placeholder={decision === 'REJECT' ? '例如：接口错误码未覆盖并发场景，补齐 409/429 的处理与用例' : '可留空'}
        />
        {gateErr && <Alert type="error" showIcon message={gateErr} style={{ marginTop: 12 }} />}
      </Modal>

      <Modal
        open={planRejectOpen}
        title="驳回规划草稿"
        okText="确认驳回"
        okButtonProps={{ danger: true, loading: approving, disabled: planReason.trim().length === 0 }}
        onOk={() => {
          if (planId == null) return
          if (planReason.trim().length === 0) {
            setPlanErr('驳回必须写理由，否则 PM 不知道要改什么')
            return
          }
          void decidePlan(planId, 'REJECT', planReason.trim())
        }}
        onCancel={() => setPlanRejectOpen(false)}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          驳回后 PM 会重出一版草稿，本轮不会起跑。
        </Typography.Paragraph>
        <Input.TextArea
          rows={4}
          value={planReason}
          onChange={(e) => setPlanReason(e.target.value)}
          placeholder="例如：子任务缺少数据迁移与回滚方案，补齐后再排开发"
        />
        {planErr && <Alert type="error" showIcon message={planErr} style={{ marginTop: 12 }} />}
      </Modal>
    </>
  )
}

/** 表单字段封装：label + hint + children（模板编辑器用） */
