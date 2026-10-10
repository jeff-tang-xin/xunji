/** 核心领域类型（对齐 docs/detail/05 API 与 03 画布 JSON v1） */

export interface DefinitionSummary {
  id: number
  name: string
  description?: string
  version?: number
  status?: string
  updatedAt?: string
}

export interface MappingRule {
  from: string
  to: string
}

export interface CanvasNode {
  id: string
  kind: 'START' | 'END' | 'AGENT' | 'ATOMIC' | 'WORKFLOW'
  refId: string
  name: string
  params?: Record<string, unknown>
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  gate?: boolean
  /** 重试策略（对齐后端 RetryPolicy：retry: {maxAttempts, intervalMs}） */
  retry?: { maxAttempts?: number; intervalMs?: number }
  timeoutMs?: number
}

export interface CanvasEdge {
  from: string
  to: string
  type?: 'SEQ' | 'COND' | 'PAR'
  mapping?: MappingRule[]
}

export interface CanvasJson {
  meta: { name: string; description?: string }
  triggers: { type: string }[]
  nodes: CanvasNode[]
  edges: CanvasEdge[]
}

export interface DefinitionDetail extends DefinitionSummary {
  canvas_json?: string | CanvasJson
  canvasJson?: string | CanvasJson
  current_version?: number
  ruleContent?: string
}

/** 资产（agent/tool/skill/mcp/model-provider 通用形态，字段 snake_case 对齐 AssetSpec） */
export interface AssetRow {
  id: number
  name: string
  description?: string
  status: string
  version?: number
  orgId?: number | null
  createdBy?: string
  updatedAt?: string
  [key: string]: unknown
}

/** 应用（App）行，字段对齐 AppService.list 返回（snake_case） */
export interface AppRow {
  id: string
  name?: string
  description?: string
  status: string
  bound_asset_type?: string
  bound_asset_id?: number
  bound_asset_version?: number
  callback_url?: string
  org_id?: number | null
  created_by?: string
  created_at?: string
}

export interface RunNodeRow {
  node_id: string
  status: string
  output_json?: { type: string; value: string; null: boolean }
  error?: string
}

export interface RunDetail {
  runId: string
  status: string
  output?: Record<string, unknown>
  output_json?: { type: string; value: string; null: boolean }
  error?: string
  nodes?: RunNodeRow[]
}
