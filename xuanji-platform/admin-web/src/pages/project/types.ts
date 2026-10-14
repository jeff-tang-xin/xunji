/**
 * 项目看板共享类型与常量（从 Project.tsx 拆出）。
 * 字段名与后端 snake_case/Jackson 序列化一致，改动前先核对接口返回。
 */
export interface StageExecution {
  stage?: string
  stage_label?: string
  iteration?: number
  plan?: { id?: number; status?: string; tasks?: unknown[]; exec_run_id?: string | null } | null
  state: string
  run_id?: string
  run_status?: string
  definition_id?: number | string
  error?: string | null
  node_runs?: NodeRunRow[]
  waiting_decisions?: WaitingDecision[]
  /** 新内核（AI 编排）模式：step 台账原文；非空即 orchestrated 模式（右边栏只读化） */
  steps?: StageStepRow[]
}

/** 单个节点的执行记录（wf_node_run，按 seq 升序） */
export interface NodeRunRow {
  id: number | string
  node_id: string
  node_name: string
  seq: number
  status: string
  error?: string | null
  started_at?: string
  finished_at?: string
  output_json?: string | null
}

/** 待人工决策的节点：引擎语义是「先产出再挂起」，节点自身已记 SUCCESS，挂起点在 wf_run.gate_node_id */
export interface WaitingDecision {
  node_run_id: number | string
  node_id: string
  node_name: string
  output?: string | null
}

/** 规划子任务（plan_json.tasks 元素，由 StagePlanCompiler 编译成串行节点） */
export interface PlanTask {
  key?: number
  name?: string
  agents?: string[]
  participants?: string[]
  approval_mode?: string
  scope?: string
  depends?: number[]
}


/**
 * 模板节点 —— 阶段「通用层」知识（跨项目复用，作为 AI 的工作指导）。
 * 注意：不含项目任务边界（scope），scope 属于项目层，在新建项目时按节点填写。
 * agent_name：该阶段由哪个内置 agent 执行（留空则后端按 owner_role 映射回退）。
 * workflow_def_id：该阶段默认跑哪个已发布工作流；创建项目时快照进节点 workflow_ref。
 */
export interface TemplateNode {
  type: string
  seq: number
  owner_role?: string
  agent_name?: string | null
  workflow_def_id?: number | null
  standards?: string[]
  requirements?: string[]
  methodology?: string
  deliverable?: string
  depends_on?: number[]
  preconditions?: string[]
}

/** 智能体候选（GET /api/agents） */
export interface AgentOption {
  id: number
  name: string
  description?: string
  status?: string
}

/** 工作流候选（GET /api/definitions）：仅 PUBLISHED 可绑定 */
export interface WorkflowOption {
  id: number
  name: string
  status?: string
  current_version?: number
}

/** 执行态中文 */
export const EXEC_STATE_LABEL: Record<string, string> = {
  NO_PLAN: '尚无规划',
  PENDING_APPROVAL: '规划就绪',
  NOT_STARTED: '已批准，未起跑',
  EXECUTING: '执行中',
  WAITING_DECISION: '等待人工决策',
  FINISHED: '本轮已跑完',
}

/** 执行态颜色 */
export const EXEC_STATE_COLOR: Record<string, string> = {
  NO_PLAN: 'default',
  PENDING_APPROVAL: 'orange',
  NOT_STARTED: 'blue',
  EXECUTING: 'processing',
  WAITING_DECISION: 'gold',
  FINISHED: 'green',
}

/** 节点执行状态中文（wf_node_run.status） */
export const NODE_RUN_STATUS_LABEL: Record<string, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  SUCCESS: '完成',
  FAILED: '失败',
  SKIPPED: '跳过',
}

export const NODE_RUN_STATUS_COLOR: Record<string, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  SKIPPED: 'orange',
}

/** 需要持续拉取执行视图的状态（跑完/无规划就不必轮询） */
export const EXEC_LIVE_STATES = new Set(['EXECUTING', 'WAITING_DECISION', 'NOT_STARTED'])

/**
 * 编排步骤（新内核黑板台账，GET /stage-execution 的 steps 字段）。
 * 字段名与后端 StageStep record 的 Jackson 序列化一致（驼峰）。
 */
export interface StageStepRow {
  id: number
  seq: number
  name: string
  stepType: 'TASK' | 'DISCUSS' | 'DECISION'
  agent?: string | null
  participants?: string[]
  status: 'PLANNED' | 'EXECUTING' | 'DONE' | 'SKIPPED' | 'FAILED' | 'WAITING'
  /** TASK=指令 / DECISION=向用户提出的问题 */
  instruction?: string | null
  outputSummary?: string | null
  outputFull?: string | null
  artifactId?: number | null
  skipReason?: string | null
  error?: string | null
  createdAt?: string
  startedAt?: string
  finishedAt?: string
}

/** 步骤状态中文（与后端 StepStatus 对齐） */
export const STEP_STATUS_LABEL: Record<string, string> = {
  PLANNED: '待执行',
  EXECUTING: '执行中',
  DONE: '完成',
  SKIPPED: '已跳过',
  FAILED: '失败',
  WAITING: '等你答复',
}

export const STEP_STATUS_COLOR: Record<string, string> = {
  PLANNED: 'default',
  EXECUTING: 'processing',
  DONE: 'success',
  SKIPPED: 'orange',
  FAILED: 'error',
  WAITING: 'gold',
}

/** 步骤类型中文 */
export const STEP_TYPE_LABEL: Record<string, string> = {
  TASK: '任务',
  DISCUSS: '讨论',
  DECISION: '决策',
}

/** 项目行（后端 snake_case） */
export interface ProjectRow {
  id: string
  name: string
  root_path: string
  status: string
  created_by?: string
  created_at?: string
}

/** 工作节点 */
export interface WorkNode {
  id: string
  workspace_id: string
  type: string
  seq: number
  status: string
  depends_on?: string | string[] | null
  preconditions?: string | string[] | null
  workflow_ref?: string | null
  input_refs?: string | string[] | null
  output_refs?: string | string[] | null
  owner_role?: string
}

/** 留档 */
export interface Artifact {
  id: number
  workspace_id: string
  node_id?: string
  kind: string
  name: string
  type: string
  file_path?: string
  content_md?: string
  version?: number | null
  status: string
  /** 产出阶段（V29）：阶段对话自动登记的交付物带 stage，前端按阶段分组 */
  stage?: string | null
  created_by?: string
  created_at?: string
}

/** 项目详情 */
export interface ProjectDetailData {
  id: string
  name: string
  root_path: string
  status: string
  created_by?: string
  created_at?: string
  /** 当前阶段（V27 阶段生命周期） */
  current_stage?: string
  nodes: WorkNode[]
  artifacts: Artifact[]
}

/** 节点类型 → 中文名（产品语言） */
export const NODE_TYPE_LABEL: Record<string, string> = {
  DISCUSS: '讨论',
  REQUIREMENT: '需求',
  ARCHITECTURE: '架构',
  DESIGN: '详设',
  TEST_CASE: '测试用例',
  DEVELOP: '开发',
  SMOKE: '冒烟',
  SUBMIT: '提测',
  TEST: '测试执行',
  RELEASE: '发布',
}

/** 节点状态 → 颜色与文案 */
export function nodeStatusView(s?: string): { color: string; text: string } {
  switch (s) {
    case 'DONE': return { color: 'green', text: '已完成' }
    case 'RUNNING': return { color: 'blue', text: '执行中' }
    case 'BLOCKED': return { color: 'orange', text: '阻塞' }
    case 'WAITING_APPROVAL': return { color: 'orange', text: '待审批' }
    case 'REJECTED': return { color: 'red', text: '已驳回' }
    default: return { color: 'default', text: '未开始' }
  }
}

/** 项目状态 → 颜色 */
export function projectStatusColor(s?: string): string {
  return s === 'ARCHIVED' ? 'default' : 'green'
}

/** 安全格式化日期（非法/空值返回 '—'） */
export function fmtDate(v?: string): string {
  if (!v) return '—'
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleDateString()
}

/** 解析 JSON 字符串或数组为字符串数组 */
export function parseList(v: string | string[] | null | undefined): string[] {
  if (!v) return []
  if (Array.isArray(v)) return v.map(String)
  try {
    const parsed = JSON.parse(v)
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}


/** 模板管理入口开关：模板编辑器就绪后置 true（列表页「模板管理」按钮 + 新建项目模板下拉） */
export const SHOW_TEMPLATE_ENTRY = true


/** 数据源候选（GET /api/datasources） */
export interface DsOption {
  id: number
  name: string
  platform?: string
}

export interface TemplateDetail {
  id: number
  code: string
  name: string
  description?: string
  builtin?: boolean
  nodes?: TemplateNode[]
  /** V30 新形态：3 阶段执行路径预设 {REQUIREMENT:{workflow,agents},...,OPERATE:{...,servers,connectors}} */
  stages?: Record<string, { workflow?: string; agents?: string[]; servers?: string[]; connectors?: string[] }>
}

export interface ProjectTemplate {
  id: number
  code: string
  name: string
  description?: string
  node_count?: number
  builtin?: boolean
  /** V30：3 阶段预设摘要（列表页展示用） */
  stages?: Record<string, { workflow?: string; agents?: string[]; servers?: string[]; connectors?: string[] }>
}

/** 阶段顺序（单向流转，不可回退） */
export const STAGE_ORDER = ['REQUIREMENT', 'DEVELOP', 'OPERATE']

/** 阶段中文标签 */
export function stageLabel(stage?: string | null): string {
  const m: Record<string, string> = { REQUIREMENT: '需求', DEVELOP: '开发', OPERATE: '运维' }
  return m[stage || ''] || stage || '—'
}

/** 阶段颜色 */
export function stageColor(stage?: string | null): string {
  const m: Record<string, string> = { REQUIREMENT: 'blue', DEVELOP: 'gold', OPERATE: 'green' }
  return m[stage || ''] || 'default'
}

/** 下一阶段；OPERATE 之后无（返回 null） */
export function nextStageOf(stage: string): string | null {
  const i = STAGE_ORDER.indexOf(stage)
  return i >= 0 && i < STAGE_ORDER.length - 1 ? STAGE_ORDER[i + 1] : null
}

/** 阶段状态 → 中文 + 颜色（生命周期总览用） */
export function stageStatusView(status?: string | null): { text: string; color: string } {
  const s = status || 'NOT_STARTED'
  const m: Record<string, { text: string; color: string }> = {
    DONE: { text: '已完成', color: 'success' },
    IN_PROGRESS: { text: '进行中', color: 'processing' },
    BLOCKED: { text: '受阻', color: 'error' },
    NOT_STARTED: { text: '未开始', color: 'default' },
  }
  return m[s] || { text: s, color: 'default' }
}

/** 阶段视图（生命周期总览）：status 为后端 project_stage.status，iterations 为运维阶段迭代轮次列表 */
export interface StageView {
  stage: string
  label: string
  status: string
  resident?: boolean
  iterations: string[]
}

/**
 * 推进按钮可用性 + 禁用原因。
 *
 * <p>判定必须与后端推进门<b>同源</b>：V27 起完成度的真相是「批准规划编译出来的那条 run」，
 * 而 {@code project_work_node} 台账既不再被规划写入、也没有 run 回写通道（实测 V27 之后新项目
 * 一个节点都没有，老项目的台账 status 永远停在 NOT_STARTED）。原先按台账节点判定的结果是
 * 前端按钮亮着、后端回 400，两边长期不一致，用户只看到一个没有原因的 Bad Request。</p>
 */
export function advanceCheck(view: StageView, exec?: StageExecution | null): { enabled: boolean; reason: string } {
  if (view.resident) {
    return { enabled: false, reason: `「${view.label}」是常驻阶段，不推进；新需求 / Bug 走新一轮迭代` }
  }
  if (!exec) {
    return { enabled: false, reason: '还没取到本阶段执行状态，稍候会自动刷新' }
  }
  switch (exec.state) {
    case 'NO_PLAN':
      return { enabled: false, reason: `「${view.label}」还没有规划，先让 PM 出规划草稿` }
    case 'PENDING_APPROVAL':
      return { enabled: false, reason: '规划就绪——在右侧「阶段执行」提示卡里批准，或直接在左侧对话里说一句话启动' }
    case 'NOT_STARTED':
    case 'EXECUTING':
      return { enabled: false, reason: '本阶段还在执行中，等所有子任务跑完再推进' }
    case 'WAITING_DECISION':
      return { enabled: false, reason: '有决策门正在等审批——先在下方放行或驳回' }
    default:
      // FINISHED：run 已成功，剩下两道门（收口总结批准、未决项豁免）由后端把关
      return {
        enabled: true,
        reason: '本阶段执行已完成；推进前需产出并批准收口总结，若有未决项要显式豁免',
      }
  }
}
