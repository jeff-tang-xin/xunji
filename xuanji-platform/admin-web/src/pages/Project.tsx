/**
 * 项目看板 —— 软件项目全生命周期管理（设计文档 docs/sdlc-lifecycle-design.md 阶段 D）。
 * 双线路：项目列表（数据驱动加载）→ 项目详情（阶段链 DAG 可视化 + 节点操作 + 留档）。
 * 创建线路：POST /api/projects（模板驱动生成节点链）；加载线路：GET /api/projects/{id}。
 * 节点操作：run（校验 DAG 依赖）/ complete（显式置 DONE）/ artifacts（查看/登记留档）。
 *
 * 2026-10-14 拆分：类型/常量 → project/types.ts，小组件 → project/shared.tsx，
 * 各面板组件 → project/<Component>.tsx；本文件保留 ProjectDetail 与路由主组件。
 */
import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Row,
  Space,
  Spin,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  PlusOutlined,
  ArrowLeftOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { apiJson } from '../api/client'
import { EXEC_LIVE_STATES, NODE_TYPE_LABEL, ProjectDetailData, ProjectRow, SHOW_TEMPLATE_ENTRY, STAGE_ORDER, StageExecution, StageView, WorkNode, fmtDate, nextStageOf, projectStatusColor, stageLabel } from './project/types'
import { ArtifactDrawer } from './project/ArtifactDrawer'
import { CreateProjectModal } from './project/CreateProjectModal'
import { NodeChain, NodeDrawer } from './project/NodeDrawer'
import { StageChatPanel } from './project/StageChatPanel'
import { StageExecutionPanel } from './project/StageExecutionPanel'
import { StageGroups } from './project/StageGroups'
import { StageSessionsPanel } from './project/StageSessionsPanel'
import { TemplateEditor } from './project/TemplateEditor'

function ProjectDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const [detail, setDetail] = useState<ProjectDetailData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selectedNode, setSelectedNode] = useState<WorkNode | null>(null)
  const [advancing, setAdvancing] = useState(false)
  /** 阶段执行视图数据，由本页轮询后喂给 StageExecutionPanel */
  const [exec, setExec] = useState<StageExecution | null>(null)
  const [execBusy, setExecBusy] = useState(false)
  const [artifactDrawer, setArtifactDrawer] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const d = await apiJson<ProjectDetailData>('/api/projects/' + id)
      setDetail(d)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [id])

  /** 拉阶段执行视图；silent=true 时不置 busy，避免轮询把面板闪成加载态 */
  const loadExec = useCallback(async (silent = false) => {
    if (!silent) setExecBusy(true)
    try {
      setExec(await apiJson<StageExecution>(`/api/projects/${id}/stage-execution`))
    } catch {
      // 执行视图取不到不打断详情页主流程（例如规划尚未生成），保留上一次数据即可
    } finally {
      if (!silent) setExecBusy(false)
    }
  }, [id])

  useEffect(() => {
    void load()
    void loadExec()
  }, [load, loadExec])

  // 执行中 / 等决策 / 已批准未起跑时 5s 轮询：审批走 resumeAsync 立即返回、后续节点后台跑，
  // 单个 agent 节点实测 165s~600s+，不轮询的话点完「通过」页面会一直停在旧状态。
  // 跑完（FINISHED）与无规划（NO_PLAN）不轮询，避免空转打后端。
  // 依赖必须是 state 字符串而非 exec 对象：每次轮询响应都是新对象，依赖对象会让 interval
  // 每 5s 被拆建一次（且守卫一改就会触发 cleanup 后不再重建、轮询永久丢失的老 bug）。
  const execState = exec?.state
  useEffect(() => {
    if (!execState || !EXEC_LIVE_STATES.has(execState)) return
    const t = setInterval(() => { void loadExec(true) }, 5000)
    return () => clearInterval(t)
  }, [execState, loadExec])

  const runNode = async (n: WorkNode) => {
    try {
      await apiJson(`/api/projects/${id}/nodes/${n.id}/run`, 'POST', {})
      message.success(`节点「${NODE_TYPE_LABEL[n.type] || n.type}」已启动`)
      await load()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    }
  }

  const completeNode = async (n: WorkNode) => {
    try {
      await apiJson(`/api/projects/${id}/nodes/${n.id}/complete`, 'POST', {})
      message.success(`节点「${NODE_TYPE_LABEL[n.type] || n.type}」已完成`)
      await load()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    }
  }

  if (loading) {
    return <div style={{ padding: 80, textAlign: 'center' }}><Spin /></div>
  }
  if (error || !detail) {
    return (
      <div style={{ padding: 60 }}>
        <Alert type="error" message="加载失败" description={error} action={<Button onClick={() => void load()}>重试</Button>} />
      </div>
    )
  }

  const currentStage = detail.current_stage || 'REQUIREMENT'

  /** 生命周期视图：3 阶段状态按当前阶段推导（V27 起完成度真相在 run，台账仅历史兼容） */
  const views = STAGE_ORDER.map((st) => ({
    stage: st,
    label: stageLabel(st),
    status: STAGE_ORDER.indexOf(st) < STAGE_ORDER.indexOf(currentStage) ? 'DONE'
      : st === currentStage ? 'IN_PROGRESS' : 'NOT_STARTED',
    resident: st === 'OPERATE',
    iterations: [] as string[],
  }))

  /** 推进阶段（AI 自动流转失败时的人工兜底；单向不可回退） */
  const advanceStage = async (v: StageView) => {
    setAdvancing(true)
    try {
      const r = await apiJson<{ current_stage?: string }>(`/api/projects/${id}/stage/advance`, 'POST', {})
      const to = stageLabel(r?.current_stage || nextStageOf(v.stage))
      message.success(`项目已推进到「${to}」阶段`)
      await load()
      // 推进后新阶段会自动产出规划草稿，执行视图要跟着换成新阶段那条
      await loadExec()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setAdvancing(false)
    }
  }

  /** 运维阶段发起新一轮迭代（后端 iterate 端点未建，暂复用 advance，由后端校验拒绝时提示） */
  const deliverables = detail.artifacts.filter((a) => a.kind === 'DELIVERABLE')
  const processes = detail.artifacts.filter((a) => a.kind === 'PROCESS')

  return (
    <div>
      <Space style={{ marginBottom: 16 }} align="center">
        <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回</Button>
        <Typography.Title level={4} style={{ margin: 0 }}>{detail.name}</Typography.Title>
        <Tag color={projectStatusColor(detail.status)}>{detail.status === 'ARCHIVED' ? '已归档' : '进行中'}</Tag>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {detail.root_path}
        </Typography.Text>
        <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </Space>

      <Card title="阶段链" style={{ marginBottom: 16 }}>
        <NodeChain nodes={detail.nodes} onNodeClick={(n) => setSelectedNode(n)} />
      </Card>

      {/* 左右分栏（参考 Easy-Claw 工作区交互）：左侧对话是主工作界面，右侧栏聚合执行/生命周期/交付物/会话 */}
      <Row gutter={16} align="top">
        <Col xs={24} lg={15}>
          {/* 阶段对话（V28）：项目的主工作界面。V27 起规划不再落 project_work_node，
              新项目一个台账节点都没有，原先挂在节点抽屉里的「对话」按钮已无入口，
              所以对话对象改为「当前阶段 + 可选聚焦子任务」，走 POST /stage/chat。 */}
          <Card
            title={`阶段对话（${exec?.stage_label || stageLabel(currentStage)} · 第 ${exec?.iteration || 1} 轮）`}
            size="small"
            extra={
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                阶段规范已作为 skill 绑定到 agent，对话自动携带；最终产出自动登记为交付物留档
              </Typography.Text>
            }
          >
            <StageChatPanel projectId={id} exec={exec} />
          </Card>
        </Col>

        <Col xs={24} lg={9}>
          <Tabs
            defaultActiveKey="exec"
            size="small"
            items={[
              {
                key: 'exec',
                label: '阶段执行',
                children: execBusy && !exec ? (
                  <div style={{ padding: 32, textAlign: 'center' }}><Spin /></div>
                ) : (
                  <StageExecutionPanel
                    projectId={id}
                    exec={exec}
                    busy={execBusy}
                    onRefresh={() => void loadExec()}
                    onChanged={() => { void loadExec(); void load() }}
                  />
                ),
              },
              {
                key: 'stages',
                label: '生命周期',
                children: (
                  <StageGroups
                    views={views}
                    currentStage={currentStage}
                    exec={exec}
                    artifacts={detail.artifacts}
                    onAdvance={(v) => void advanceStage(v)}
                    advancing={advancing}
                  />
                ),
              },
              {
                key: 'artifacts',
                label: `交付物 ${deliverables.length}`,
                children: (
                  <>
                    <div style={{ marginBottom: 8, textAlign: 'right' }}>
                      <Button size="small" type="link" onClick={() => setArtifactDrawer(true)}>登记留档</Button>
                    </div>
                    {deliverables.length === 0
                      ? <Empty description="暂无交付留档" />
                      : deliverables.map((a) => (
                        <Card key={a.id} size="small" style={{ marginBottom: 8 }}>
                          <Space direction="vertical" size={2} style={{ width: '100%' }}>
                            <Space>
                              <FileTextOutlined />
                              <Typography.Text strong>{a.name}</Typography.Text>
                              {a.version != null && <Tag>v{a.version}</Tag>}
                              <Tag color="green">交付</Tag>
                            </Space>
                            {a.content_md && (
                              <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }} ellipsis={{ rows: 2 }}>
                                {a.content_md}
                              </Typography.Paragraph>
                            )}
                          </Space>
                        </Card>
                      ))}
                    {processes.length > 0 && (
                      <>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          过程留档（{processes.length}）
                        </Typography.Text>
                        {processes.map((a) => (
                          <Card key={a.id} size="small" style={{ marginBottom: 8 }}>
                            <Space>
                              <FileTextOutlined />
                              <Typography.Text strong>{a.name}</Typography.Text>
                              <Tag>过程</Tag>
                            </Space>
                          </Card>
                        ))}
                      </>
                    )}
                  </>
                ),
              },
              {
                key: 'sessions',
                label: '会话',
                children: <StageSessionsPanel projectId={id} />,
              },
            ]}
          />
        </Col>
      </Row>

            <NodeDrawer
        node={selectedNode ? detail.nodes.find((n) => n.id === selectedNode.id) ?? selectedNode : null}
        nodes={detail.nodes}
        artifacts={detail.artifacts}
        onClose={() => setSelectedNode(null)}
        onRun={runNode}
        onComplete={completeNode}
      />
      <ArtifactDrawer
        open={artifactDrawer}
        projectId={id}
        nodes={detail.nodes}
        onClose={() => setArtifactDrawer(false)}
        onSaved={() => void load()}
      />
    </div>
  )
}

/** 登记留档抽屉 */

/** 项目看板主页面：列表 + 详情双视图 */
export default function Project() {
  const navigate = useNavigate()
  const { id } = useParams()
  const [projects, setProjects] = useState<ProjectRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [tplOpen, setTplOpen] = useState(false)
  const [tplCode, setTplCode] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await apiJson<ProjectRow[]>('/api/projects')
      setProjects(data)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { if (!id) void load() }, [id, load])

  // 有 id 参数 → 详情视图；返回列表时重拉（列表→详情→返回，组件未重挂载，需手动刷新）
  if (id) {
    return <ProjectDetail id={id} onBack={() => { navigate('/project'); void load() }} />
  }

  return (
    <div>
      <Space style={{ marginBottom: 16 }} align="center">
        <Typography.Title level={4} style={{ margin: 0 }}>项目列表</Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建项目</Button>
        {SHOW_TEMPLATE_ENTRY && (
          <Button size="small" icon={<SettingOutlined />} onClick={() => { setTplCode(null); setTplOpen(true) }}>
            模板管理
          </Button>
        )}
        <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </Space>

      {error && <Alert type="error" message="加载失败" description={error} style={{ marginBottom: 16 }} />}

      {loading ? (
        <div style={{ padding: 80, textAlign: 'center' }}><Spin /></div>
      ) : projects.length === 0 ? (
        <Empty description="暂无项目，点击「新建项目」开始">
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建项目</Button>
        </Empty>
      ) : (
        <Row gutter={[16, 16]}>
          {projects.map((p) => (
            <Col key={p.id} xs={24} sm={12} md={8} lg={6}>
              <Card
                hoverable
                onClick={() => navigate(`/project/${p.id}`)}
                title={
                  <Space>
                    <FolderOpenOutlined />
                    <span>{p.name}</span>
                  </Space>
                }
                extra={<Tag color={projectStatusColor(p.status)}>{p.status === 'ARCHIVED' ? '已归档' : '进行中'}</Tag>}
              >
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }} ellipsis>
                  {p.root_path}
                </Typography.Paragraph>
                <Space size="small" style={{ fontSize: 12, color: '#999' }}>
                  <span>创建人：{p.created_by || '—'}</span>
                  <span>· {fmtDate(p.created_at)}</span>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      )}

      <CreateProjectModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(newId) => {
          setCreateOpen(false)
          navigate(`/project/${newId}`)
        }}
      />
      <TemplateEditor open={tplOpen} code={tplCode} onClose={() => setTplOpen(false)} onSaved={() => void load()} />
    </div>
  )
}
