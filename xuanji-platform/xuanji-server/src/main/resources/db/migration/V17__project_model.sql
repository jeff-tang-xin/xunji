-- ============================================================
-- V17: 项目（workspace 即项目）数据模型
--   workspace 加 status 列（项目状态），保留现有绑定三列兼容前端
--   新建 project_work_node（工作节点：DAG 依赖 + 前置条件 + 留档引用）
--   新建 project_artifact（留档元数据：过程/交付两形态）
-- ============================================================

-- workspace 加项目状态列（ACTIVE 进行中 / ARCHIVED 已归档），默认 ACTIVE
ALTER TABLE workspace ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_workspace_status ON workspace(status);

-- ============================================================
-- 工作节点表：一个项目（workspace）下的阶段节点链
--   type: DISCUSS/REQUIREMENT/ARCHITECTURE/DESIGN/TEST_CASE/DEVELOP/SMOKE/SUBMIT/TEST/RELEASE
--   seq: 节点在链中的序号（展示顺序）
--   status: NOT_STARTED/BLOCKED/RUNNING/WAITING_APPROVAL/DONE/REJECTED
--   depends_on: JSONB 数组，依赖的上游节点 id（DAG 边）
--   preconditions: JSONB 数组，前置条件描述（如"冒烟 case 跑完"）
--   workflow_ref: JSONB {definitionId, version}，本节点绑定的阶段工作流
--   input_refs: JSONB 数组，输入留档引用（来自 depends_on 的交付留档）
--   output_refs: JSONB 数组，产出留档引用
--   owner_role: 节点职能角色（产品/BA/架构师/开发/测试/运维）
-- ============================================================
CREATE TABLE IF NOT EXISTS project_work_node (
  id             VARCHAR(32) PRIMARY KEY,          -- pwn_ 前缀
  workspace_id   VARCHAR(32) NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
  type           VARCHAR(24) NOT NULL,
  seq            INT NOT NULL,
  status         VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
  depends_on     JSONB,
  preconditions  JSONB,
  workflow_ref   JSONB,
  input_refs     JSONB,
  output_refs    JSONB,
  owner_role     VARCHAR(32),
  created_by     VARCHAR(64),
  created_at     TIMESTAMPTZ DEFAULT now(),
  updated_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_pwn_workspace ON project_work_node(workspace_id, seq);

-- ============================================================
-- 留档元数据表：阶段产出（过程/交付两形态）
--   kind: PROCESS（过程留档，本阶段可见）/ DELIVERABLE（交付留档，跨阶段传递）
--   type: DOC/CODE/REPORT/CONFIG
--   file_path: 相对项目 root_path 的物理路径
--   content_md: 文档正文（可检索）
--   version: 交付留档固化版本；过程留档 version=null
--   status: DRAFT（草稿）/ FINAL（最终版本）
-- ============================================================
CREATE TABLE IF NOT EXISTS project_artifact (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_id   VARCHAR(32) NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
  node_id        VARCHAR(32),
  kind           VARCHAR(16) NOT NULL DEFAULT 'DELIVERABLE',
  name           VARCHAR(128) NOT NULL,
  type           VARCHAR(16) NOT NULL DEFAULT 'DOC',
  file_path      VARCHAR(512),
  content_md     TEXT,
  version        INT,
  status         VARCHAR(16) NOT NULL DEFAULT 'FINAL',
  created_by     VARCHAR(64),
  created_at     TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pa_workspace ON project_artifact(workspace_id);
CREATE INDEX IF NOT EXISTS idx_pa_node ON project_artifact(node_id);
-- 同名留档同版本唯一（防重复登记累积 output_refs 重复项）
CREATE UNIQUE INDEX IF NOT EXISTS uq_pa_ws_node_name_ver ON project_artifact(workspace_id, node_id, name, version);