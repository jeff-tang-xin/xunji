/**
 * 项目看板 —— 软件项目全生命周期管理（设计文档 docs/sdlc-lifecycle-design.md 阶段 D）。
 * 主形态（2026-10-15 收敛）：对话即工作界面（AI 主控编排），右栏聚合 需求/阶段执行/生命周期/文件/交付物/会话。
 * 旧节点链（阶段链横条 + 节点人驱动操作）已下线——只保留新流程，避免两条链路并存互相干扰。
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
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Row,
  Select,
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
  DeleteOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { apiJson } from '../api/client'
import { EXEC_LIVE_STATES, ProjectDetailData, ProjectRow, SHOW_TEMPLATE_ENTRY, STAGE_ORDER, StageExecution, StageView, fmtDate, nextStageOf, projectStatusColor, stageLabel } from './project/types'
import { ArtifactDrawer } from './project/ArtifactDrawer'
import { CreateProjectModal } from './project/CreateProjectModal'
import ProjectFiles from './project/ProjectFiles'
import { StageChatPanel } from './project/StageChatPanel'
import { MarkdownView } from './project/shared'
import { DsOption } from './project/types'
import { StageExecutionPanel } from './project/StageExecutionPanel'
import { StageGroups } from './project/StageGroups'
import { StageSessionsPanel } from './project/StageSessionsPanel'
import { TemplateEditor } from './project/TemplateEditor'
import { RequirementBoard } from './project/RequirementBoard'
import { TracePanel } from './project/TracePanel'

/** 右栏 tab 内容统一限高内部滚动：任何 tab 内容多都不再把整页撑出几屏长（实测反馈）。
 * 高度 = 视口 - 顶部导航/项目头/页签占位，窄屏矮屏自适应。 */
function tabBody(node: React.ReactNode) {
  return (
    <div style={{ maxHeight: 'calc(100vh - 230px)', overflowY: 'auto', overflowX: 'hidden', paddingRight: 2 }}>
      {node}
    </div>
  )
}

function ProjectDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const [detail, setDetail] = useState<ProjectDetailData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [advancing, setAdvancing] = useState(false)
  /** 交付物全文查看：点卡片打开抽屉渲染完整 markdown */
  const [viewArtifact, setViewArtifact] = useState<ProjectDetailData['artifacts'][number] | null>(null)
  /** 项目编辑/删除 */
  const [editOpen, setEditOpen] = useState(false)
  const [savingEdit, setSavingEdit] = useState(false)
  const [editForm] = Form.useForm()
  const [datasources, setDatasources] = useState<DsOption[]>([])

  /** 打开编辑弹窗：回显项目名 + OPERATE 阶段已绑定的服务器/数据库 */
  const openEdit = () => {
    if (!detail) return
    editForm.setFieldsValue({ name: detail.name, servers: [], connectors: [] })
    setEditOpen(true)
    apiJson<Record<string, unknown>>(`/api/projects/${id}/stage/OPERATE/capability`)
      .then((cap) => {
        const capObj = (cap && typeof cap === 'object' ? cap : {}) as { servers?: string[]; connectors?: string[] }
        editForm.setFieldsValue({
          servers: Array.isArray(capObj.servers) ? capObj.servers : [],
          connectors: Array.isArray(capObj.connectors) ? capObj.connectors : [],
        })
      })
      .catch(() => { /* capability 拉取失败保持空值，可正常保存 */ })
    apiJson<DsOption[]>('/api/datasources')
      .then((list) => setDatasources(Array.isArray(list) ? list : []))
      .catch(() => setDatasources([]))
  }

  const submitEdit = async () => {
    const v = await editForm.validateFields().catch(() => null)
    if (!v) return
    setSavingEdit(true)
    try {
      await apiJson(`/api/projects/${id}`, 'PUT', {
        name: (v.name || '').trim(),
        servers: (v.servers || []) as string[],
        connectors: (v.connectors || []) as string[],
      })
      message.success('项目已更新')
      setEditOpen(false)
      await load()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setSavingEdit(false)
    }
  }

  const deleteProject = () => {
    Modal.confirm({
      title: '删除项目',
      icon: <ExclamationCircleOutlined style={{ color: '#ff4d4f' }} />,
      content: `将永久删除项目「${detail?.name}」的全部数据（需求/规划/台账/交付物/会话）与项目目录下的所有文件，不可恢复。确定删除？`,
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await apiJson(`/api/projects/${id}`, 'DELETE')
          message.success('项目已删除')
          onBack()
        } catch (e) {
          if (e instanceof Error) message.error(e.message)
        }
      },
    })
  }
  /** 阶段执行视图数据，由本页轮询后喂给 StageExecutionPanel */
  const [exec, setExec] = useState<StageExecution | null>(null)
  const [execBusy, setExecBusy] = useState(false)
// 右栏受控 tab：对话内决策黄卡「去处理」按钮跳转到「阶段执行」
const [rightTab, setRightTab] = useState('exec')
// 决策答复的前端自动续跑已下线：answer 端点置 DONE 后由后端 wakeMaster 直接唤醒主控
//（答复内容进唤醒消息），前端经会话事件总线实时回显——与任务/讨论落板唤醒同一条后端链路。
  const [artifactDrawer, setArtifactDrawer] = useState(false)

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
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
    // 30s 兜底轮询：实时推送（下方事件订阅）为主通道，轮询只在断线/漏事件时自愈
    const t = setInterval(() => { void loadExec(true) }, 30000)
    return () => clearInterval(t)
  }, [execState, loadExec])

  // 项目状态实时推送（SSE）：步骤落终态/决策答复/需求确认 → 静默刷新对应视图。
  // 后端在状态落定点 publish（TaskRunner/DiscussionEngine/DecisionSettleService/OrchestrationTools），
  // 在线前端收到即刷新，不再「状态变了要手动点刷新」；离线时数据在库里，重开页面拉全量兜底。
  const [reqTick, setReqTick] = useState(0)
  useEffect(() => {
    const es = new EventSource(`/api/projects/${id}/events`)
    const refresh = () => { void loadExec(true); void load(true) }
    es.addEventListener('step.updated', refresh)
    es.addEventListener('decision.answered', refresh)
    es.addEventListener('requirement.updated', () => { setReqTick((x) => x + 1); refresh() })
    return () => es.close()
  }, [id, load, loadExec])

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
  /** 留档：过程性文档（讨论纪要/求助问答/决策记录/对话留档），与交付物分开浏览 */
  const archives = detail.artifacts.filter((a) => a.kind === 'DOC' || a.kind === 'DECISION')

  return (
    <div>
      {/* 头部：左=标识组（返回/标题/状态/路径），右=操作组——信息与动作分组，路径不夹在中间 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 16 }}>
        <Space align="center" wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回</Button>
          <Typography.Title level={4} style={{ margin: 0 }}>{detail.name}</Typography.Title>
          <Tag color={projectStatusColor(detail.status)} style={{ marginInlineEnd: 0 }}>
            {detail.status === 'ARCHIVED' ? '已归档' : '进行中'}
          </Tag>
          <Typography.Text type="secondary" style={{ fontSize: 12 }} copyable={{ text: detail.root_path }}>
            {detail.root_path}
          </Typography.Text>
        </Space>
        <Space align="center">
          <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
          <Button size="small" icon={<EditOutlined />} onClick={openEdit}>编辑</Button>
          <Button size="small" danger icon={<DeleteOutlined />} onClick={deleteProject}>删除</Button>
        </Space>
      </div>

      <Modal
        title="编辑项目"
        open={editOpen}
        onOk={() => void submitEdit()}
        confirmLoading={savingEdit}
        onCancel={() => setEditOpen(false)}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={editForm} layout="vertical">
          <Form.Item
            name="name"
            label="项目名"
            rules={[{ required: true, message: '项目名不能为空' }]}
            extra={`只修改显示名；项目目录（${detail.root_path}）保持不变`}
          >
            <Input placeholder="项目名" maxLength={64} />
          </Form.Item>
          <Form.Item
            name="servers"
            label="运维服务器"
            extra="部署 / 巡检 / 变更的目标机器，写入运维阶段能力绑定；输入后回车添加，清空即解除绑定"
          >
            <Select
              mode="tags"
              placeholder="如 root@1.2.3.4:22（回车确认）"
              tokenSeparators={[' ']}
              open={false}
            />
          </Form.Item>
          <Form.Item
            name="connectors"
            label="可查询数据库"
            extra="运维排障时 agent 可直接查询的数据源连接器（JDBC）；清空即解除绑定"
          >
            <Select
              mode="multiple"
              allowClear
              placeholder={datasources.length ? '选择可用的数据库（可留空）' : '未加载数据源，请先在「数据源」页添加'}
              options={datasources.map((d) => ({
                value: `connector:${d.id}`,
                label: `${d.name}（${d.platform || 'jdbc'} #${d.id}）`,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 旧「阶段链」横条已下线（2026-10-15）：只保留新流程（AI 主控编排），
          旧节点链台账与新流程并存时互相干扰（链上显示全完成、对话里还在推进）。 */}

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
            <StageChatPanel projectId={id} exec={exec} onJumpExec={() => setRightTab('exec')} />
          </Card>
        </Col>

        <Col xs={24} lg={9}>
          <Tabs
            activeKey={rightTab}
            onChange={setRightTab}
            size="small"
            items={[
              {
                key: 'trace',
                label: '追溯',
                children: tabBody(<TracePanel projectId={id} refreshKey={reqTick} />),
              },
              {
                key: 'requirements',
                label: '需求',
                children: tabBody(<RequirementBoard projectId={id} refreshKey={reqTick} />),
              },
              {
                key: 'exec',
                label: '阶段执行',
                children: tabBody(execBusy && !exec ? (
                  <div style={{ padding: 32, textAlign: 'center' }}><Spin /></div>
                ) : (
                  <StageExecutionPanel
                    projectId={id}
                    exec={exec}
                    busy={execBusy}
                    onRefresh={() => void loadExec()}
                    onChanged={() => { void loadExec(); void load() }}
                  />
                )),
              },
              {
                key: 'stages',
                label: '生命周期',
                children: tabBody(
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
                key: 'files',
                label: '文件',
                children: tabBody(<ProjectFiles projectId={id} />),
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
                      : [...deliverables].reverse().map((a) => (
                        <Card key={a.id} size="small" style={{ marginBottom: 8, cursor: 'pointer' }}
                          onClick={() => setViewArtifact(a)}>
                          <Space direction="vertical" size={2} style={{ width: '100%' }}>
                            <Space>
                              <FileTextOutlined />
                              <Typography.Text strong>{a.name}</Typography.Text>
                              {a.version != null && <Tag style={{ marginInlineEnd: 0 }}>v{a.version}</Tag>}
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
                key: 'archives',
                label: `留档 ${archives.length}`,
                children: tabBody(
                  archives.length === 0
                    ? <Empty description="暂无过程留档（讨论纪要/决策记录等自动归档于此）" />
                    : [...archives].reverse().map((a) => (
                      <Card key={a.id} size="small" style={{ marginBottom: 8, cursor: 'pointer' }}
                        onClick={() => setViewArtifact(a)}>
                        <Space direction="vertical" size={2} style={{ width: '100%' }}>
                          <Space wrap>
                            <FileTextOutlined />
                            <Typography.Text strong>{a.name}</Typography.Text>
                            <Tag style={{ marginInlineEnd: 0 }} color={a.kind === 'DECISION' ? 'gold' : 'purple'}>
                              {a.kind === 'DECISION' ? '决策' : '过程'}
                            </Tag>
                            {a.stage && <Tag style={{ marginInlineEnd: 0 }}>{stageLabel(a.stage)}</Tag>}
                            {a.created_by && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{a.created_by}</Typography.Text>}
                          </Space>
                          {a.content_md && (
                            <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }} ellipsis={{ rows: 2 }}>
                              {a.content_md}
                            </Typography.Paragraph>
                          )}
                        </Space>
                      </Card>
                    ))
                ),
              },
              {
                key: 'sessions',
                label: '会话',
                children: tabBody(<StageSessionsPanel projectId={id} />),
              },
            ]}
          />
        </Col>
      </Row>

      {/* 交付物全文查看：点卡片打开，markdown 渲染完整 content_md */}
      <Drawer
        open={viewArtifact != null}
        title={viewArtifact?.name}
        width={640}
        onClose={() => setViewArtifact(null)}
      >
        {viewArtifact && <MarkdownView text={viewArtifact.content_md} />}
      </Drawer>
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
