import {
  Button,
  Collapse,
  Space,
  Steps,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import {
  SyncOutlined,
  ForwardOutlined,
} from '@ant-design/icons'
import { Artifact, PlanTask, STAGE_ORDER, STEP_STATUS_COLOR, STEP_STATUS_LABEL, StageExecution, StageView, advanceCheck, isOrchestrated, nextStageOf, stageColor, stageLabel, stageStatusView } from './types'

export function StageGroups({ views, currentStage, exec, artifacts, onAdvance, advancing }: {
  views: StageView[]
  currentStage: string
  /** 阶段执行视图：推进判定要以它（即 run）为准，不能用台账节点状态 */
  exec?: StageExecution | null
  /** 交付物留档：按 stage 归属计数（V29 起阶段对话登记的交付物带 stage） */
  artifacts: Artifact[]
  onAdvance: (v: StageView) => void
  advancing: boolean
}) {
  const stageArts = (stage: string) => artifacts.filter((a) => a.stage === stage)
  const runStateTag = (st?: string) => {
    const s = st || 'PENDING'
    const color = s === 'SUCCESS' ? 'success' : s === 'RUNNING' ? 'processing'
      : s === 'FAILED' ? 'error' : s === 'SUSPENDED' ? 'warning' : 'default'
    const text = s === 'SUCCESS' ? '完成' : s === 'RUNNING' ? '执行中' : s === 'FAILED' ? '失败'
      : s === 'SUSPENDED' ? '待决策' : s === 'PENDING' ? '未起跑' : s
    return <Tag color={color} style={{ marginInlineEnd: 0 }}>{text}</Tag>
  }
  return (
    <div>
      <Steps
        size="small"
        current={Math.max(0, STAGE_ORDER.indexOf(currentStage))}
        style={{ marginBottom: 16 }}
        items={views.map((v) => ({
          title: v.label,
          description: stageStatusView(v.status).text + (v.resident ? ' · 常驻' : ''),
          status: v.status === 'DONE'
            ? 'finish'
            : v.status === 'BLOCKED'
              ? 'error'
              : v.stage === currentStage
                ? 'process'
                : 'wait',
        }))}
      />
      <Collapse
        size="small"
        defaultActiveKey={views.map((v) => v.stage)}
        items={views.map((v) => {
          const sv = stageStatusView(v.status)
          const arts = stageArts(v.stage)
          const isCurrent = v.stage === currentStage
          const isOperate = v.stage === 'OPERATE'
          const check = advanceCheck(v, exec)
          // 新内核（AI 编排）模式：主控 complete_stage 自动收口推进，右边栏不提供人驱动按钮
          const orchestrated = isOrchestrated(exec)
          const tasks = isCurrent && exec?.plan?.tasks ? (exec.plan.tasks as PlanTask[]) : []
          const nodeRuns = exec?.node_runs || []
          return {
            key: v.stage,
            label: (
              <Space size={6} wrap>
                <Tag color={stageColor(v.stage)} style={{ marginInlineEnd: 0 }}>{v.label}</Tag>
                <Tag color={sv.color} style={{ marginInlineEnd: 0 }}>{sv.text}</Tag>
                {arts.length > 0 && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    交付物 {arts.length}
                  </Typography.Text>
                )}
                {isOperate && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    迭代 {(isCurrent && exec?.iteration) || 1} 轮
                  </Typography.Text>
                )}
                {v.resident && <Tag color="purple" style={{ marginInlineEnd: 0 }}>常驻</Tag>}
                {isCurrent && <Tag color="processing" style={{ marginInlineEnd: 0 }}>当前阶段</Tag>}
              </Space>
            ),
            extra: isCurrent && !isOperate ? (
              orchestrated ? (
                <Tag color="processing" style={{ marginInlineEnd: 0 }}>AI 自动流转</Tag>
              ) : (
              <Tooltip
                title={check.enabled
                  ? `AI 会自动推进；此按钮用于自动流转失败时人工兜底。目标「${stageLabel(nextStageOf(v.stage))}」（单向，不可回退）`
                  : check.reason}
              >
                <span style={{ display: 'inline-block' }}>
                  <Button
                    size="small"
                    type="primary"
                    icon={<ForwardOutlined />}
                    disabled={!check.enabled}
                    loading={advancing}
                    onClick={(e) => { e.stopPropagation(); onAdvance(v) }}
                  >
                    手动推进
                  </Button>
                </span>
              </Tooltip>
              )
            ) : isCurrent && isOperate ? (
              orchestrated ? (
                <Tag color="processing" style={{ marginInlineEnd: 0 }}>AI 自主运维</Tag>
              ) : (
              <Tooltip title="迭代入口开发中（后端 startIteration 端点未上线）：当前请直接在对话里提出新需求，AI 会自主承接">
                <span style={{ display: 'inline-block' }}>
                  <Button size="small" icon={<SyncOutlined />} disabled>
                    发起迭代
                  </Button>
                </span>
              </Tooltip>
              )
            ) : null,
            children: isCurrent ? (
              <Space direction="vertical" size={6} style={{ width: '100%' }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  AI 自动流转：本阶段流程跑完后自动收口并推进下一阶段；需要你介入的只有决策门审批与对话澄清。
                </Typography.Text>
                {orchestrated ? (
                  // 新内核：plan.tasks 是创建时的规划草稿（不参与执行），显示真实 steps 台账。
                  // 行内 nowrap + 名称/摘要截断悬停看全文——Space wrap 里长名不收缩会把行撑破容器（实测反馈）
                  (exec?.steps || []).length > 0 ? (
                    (exec?.steps || []).map((s) => (
                      <div key={String(s.id)} style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
                        <Typography.Text style={{ fontSize: 13, flexShrink: 0 }}>#{s.seq}</Typography.Text>
                        <Tooltip title={s.name}>
                          <span style={{
                            fontSize: 13, flex: '0 1 auto', minWidth: 0,
                            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                          }}>
                            {s.name}
                          </span>
                        </Tooltip>
                        <Tag color={STEP_STATUS_COLOR[s.status] || 'default'} style={{ marginInlineEnd: 0, flexShrink: 0 }}>
                          {STEP_STATUS_LABEL[s.status] || s.status}
                        </Tag>
                        {s.outputSummary && (
                          <Tooltip title={s.outputSummary}>
                            <span style={{
                              fontSize: 12, color: 'rgba(0,0,0,0.45)', flex: '0 1 auto', minWidth: 0,
                              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                            }}>
                              {s.outputSummary.length > 30 ? s.outputSummary.slice(0, 30) + '…' : s.outputSummary}
                            </span>
                          </Tooltip>
                        )}
                      </div>
                    ))
                  ) : (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      主控尚未规划本阶段步骤——在左侧对话里说一句话即可启动。
                    </Typography.Text>
                  )
                ) : tasks.length > 0 ? (
                  tasks.map((tk) => {
                    const run = nodeRuns.find((nr) => String(nr.seq) === String(tk.key))
                    return (
                      <div key={String(tk.key ?? tk.name)} style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
                        <Tooltip title={tk.name || `子任务 ${tk.key}`}>
                          <span style={{
                            fontSize: 13, flex: '0 1 auto', minWidth: 0,
                            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                          }}>
                            {tk.name || `子任务 ${tk.key}`}
                          </span>
                        </Tooltip>
                        {((tk.agents || tk.participants) || []).length > 0 && (
                          <Typography.Text type="secondary" style={{ fontSize: 12, flexShrink: 0 }}>
                            {((tk.agents || tk.participants) as string[]).join('、')}
                          </Typography.Text>
                        )}
                        <span style={{ flexShrink: 0 }}>{runStateTag(run?.status)}</span>
                      </div>
                    )
                  })
                ) : (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {exec?.state === 'PENDING_APPROVAL' ? '规划就绪——在左侧对话里说一句话即可启动' : '尚无规划子任务'}
                  </Typography.Text>
                )}
              </Space>
            ) : v.status === 'DONE' ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                AI 已自动收口并推进{arts.length > 0 ? `；留档 ${arts.length} 份见「留档」页签` : ''}
              </Typography.Text>
            ) : (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                前序阶段完成后 AI 自动推进至此
              </Typography.Text>
            ),
          }
        })}
      />
    </div>
  )
}



