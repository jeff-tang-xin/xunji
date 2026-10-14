-- ============================================================
-- V27: 阶段工作区模型（3 阶段 + PM 全局协调 + 阶段能力绑定）
--
--   与用户确认的最终形态：
--   ① 阶段固定 3 个：REQUIREMENT 需求 / DEVELOP 开发 / OPERATE 运维（常驻，可迭代）。
--      原 INITIATIVE 立项阶段取消 —— 它"多方讨论定范围"的语义并入需求阶段开头，
--      由 PM 现场决定拉哪些角色参与分析，而不是先单独开一轮立项会。
--   ② 去掉模板驱动：project_template / project_template_node 不再被项目流程依赖
--      （表与数据保留作规范溯源，不做 DROP，避免不可逆）。
--      模板里真正起作用的四栏通用知识（standards/requirements/methodology/deliverable）
--      迁为 3 份阶段规范 skill（见 SeedInitializer），绑到对应 agent。
--   ③ 流程由 PM 现场规划：创建项目只建 3 阶段骨架；进入某阶段时 PM 自动产出
--      「执行计划」（参与者 agent + 子任务拆分 + 各自 scope + 质量门），人审核后落库。
--   ④ 阶段绑定能力与资源，形态与画布 agent 节点的 capabilityBinding 同构：
--      tools / skills / mcps / connectors(服务器·DB) / artifacts(项目资料) / urls。
--      合并语义沿用既有约定：相同项以阶段绑定为准，其余保留 agent 自身配置。
--   ⑤ 阶段结束即锁定：project_stage_session.locked_at 非空 → 该阶段执行会话与产物
--      只读，人不可再沟通；PM 全局会话不受锁定约束，携带总结进下一阶段。
--   ⑥ 触发与放行是两个正交维度：
--        trigger_mode  = AUTO（上游完成自动起）/ MANUAL_START（人点才起）
--        approval_mode = AUTO / REVIEW / FORCE
--      FORCE 用于有生产副作用的子任务（发布、部署、数据变更），模板与 PM 均不可降级。
--
--   幂等：ADD COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS；回填带 WHERE 条件。
--   本迁移不含业务数据删除（存量测试项目由此前运维单独清理，避免破坏性 DML 被重放）。
-- ============================================================

-- ---------- ① 阶段收敛为 3 个：存量立项归入需求阶段 ----------
ALTER TABLE workspace ALTER COLUMN current_stage SET DEFAULT 'REQUIREMENT';

UPDATE workspace
SET current_stage = 'REQUIREMENT'
WHERE current_stage IS NULL OR current_stage = 'INITIATIVE' OR current_stage NOT IN
      ('REQUIREMENT', 'DEVELOP', 'OPERATE');

UPDATE project_work_node
SET stage = 'REQUIREMENT'
WHERE stage = 'INITIATIVE'
   OR (stage IS NULL AND UPPER(TRIM(type)) IN ('DISCUSS', 'INITIATION', 'PROJECT_DOC'));

-- ---------- ①b 项目性质：新项目 / 老项目维护 ----------
--   此前 project_mode 只活在请求体与响应里、库里没有这一列，导致阶段执行输入取到 null
--   （agent 不知道这是老项目维护，会按从零起的需求口吻规划）。补列并回填：
--   存量项目里已经落在 OPERATE 的，只可能是"老项目直接进运维"，按 LEGACY 认。
ALTER TABLE workspace ADD COLUMN IF NOT EXISTS project_mode VARCHAR(16) NOT NULL DEFAULT 'NEW';

UPDATE workspace SET project_mode = 'LEGACY'
WHERE binding_type = 'PROJECT' AND current_stage = 'OPERATE';

-- ---------- ② 子任务身份、触发与放行 ----------
--   name 是界面主标题（子任务自由命名），type 退化为机器分类：用于 agent 兜底映射与 prompt 上下文。
ALTER TABLE project_work_node ADD COLUMN IF NOT EXISTS name          VARCHAR(128);
ALTER TABLE project_work_node ADD COLUMN IF NOT EXISTS trigger_mode  VARCHAR(16) NOT NULL DEFAULT 'AUTO';
ALTER TABLE project_work_node ADD COLUMN IF NOT EXISTS approval_mode VARCHAR(16) NOT NULL DEFAULT 'REVIEW';
ALTER TABLE project_work_node ADD COLUMN IF NOT EXISTS participants  JSONB;   -- 该子任务实际参与的 agent
ALTER TABLE project_work_node ADD COLUMN IF NOT EXISTS plan_id       BIGINT;  -- 由哪次 PM 规划生成

ALTER TABLE project_artifact  ADD COLUMN IF NOT EXISTS stage         VARCHAR(16);

CREATE INDEX IF NOT EXISTS idx_pa_ws_stage  ON project_artifact(workspace_id, stage);
CREATE INDEX IF NOT EXISTS idx_pwn_ws_plan  ON project_work_node(workspace_id, plan_id);
CREATE INDEX IF NOT EXISTS idx_pwn_ws_stage ON project_work_node(workspace_id, stage, iteration);

-- ---------- ③ PM 全局会话（一项目一条，贯穿始终，不受阶段锁定约束） ----------
CREATE TABLE IF NOT EXISTS project_pm_session (
  workspace_id VARCHAR(32) PRIMARY KEY REFERENCES workspace(id) ON DELETE CASCADE,
  agent_name   VARCHAR(64) NOT NULL DEFAULT 'project_manager',
  session_key  VARCHAR(128),
  created_at   TIMESTAMPTZ DEFAULT now(),
  updated_at   TIMESTAMPTZ
);

-- ---------- ④ 阶段执行会话（locked_at 非空即"该阶段已结束，不可再沟通"） ----------
CREATE TABLE IF NOT EXISTS project_stage_session (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_id VARCHAR(32) NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
  stage        VARCHAR(16) NOT NULL,
  iteration    INT NOT NULL DEFAULT 1,
  agent_name   VARCHAR(64),
  session_key  VARCHAR(128),
  locked_at    TIMESTAMPTZ,
  created_at   TIMESTAMPTZ DEFAULT now(),
  updated_at   TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_pss_ws_stage_it
    ON project_stage_session(workspace_id, stage, iteration);

-- ---------- ⑤ PM 阶段执行计划（动态组队 + 子任务拆分，人审核后生效） ----------
CREATE TABLE IF NOT EXISTS project_stage_plan (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_id  VARCHAR(32) NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
  stage         VARCHAR(16) NOT NULL,
  iteration     INT NOT NULL DEFAULT 1,
  plan_json     JSONB,          -- {participants:[], tasks:[{key,name,type,agents[],participants[],scope,trigger_mode,approval_mode,depends[]}], quality_gates:[]}
  deviation     TEXT,           -- PM 相对常规做法的调整理由（人审核时看得见"为什么这么排"）
  status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/APPROVED/REJECTED
  decided_by    VARCHAR(64),
  decided_at    TIMESTAMPTZ,
  reject_reason TEXT,
  -- 批准后编译出的执行体：规划不再是 Java 状态机，而是一条真工作流（人审核时看的就是这个画布）
  exec_definition_id BIGINT,   -- → wf_definition.id（StagePlanCompiler 产物，已发布）
  exec_run_id        VARCHAR(32), -- → wf_run.id（批准后触发的那次执行）
  created_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_psp_ws_stage ON project_stage_plan(workspace_id, stage, iteration);

-- ---------- ⑥ PM 阶段收口总结（遗留项非空默认拒绝推进，可显式豁免并留档） ----------
--   同一 stage+iteration 允许多条：总结被驳回后 PM 要能再出一版，旧版本留作审计轨迹，
--   因此这里不能建唯一索引（与 ⑤ 规划表的多版本设计保持一致）。
CREATE TABLE IF NOT EXISTS project_stage_summary (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_id  VARCHAR(32) NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
  stage         VARCHAR(16) NOT NULL,
  iteration     INT NOT NULL DEFAULT 1,
  content_md    TEXT,
  pending_items JSONB,
  status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/APPROVED/REJECTED
  decided_by    VARCHAR(64),                             -- 批准/驳回该总结的人
  decided_at    TIMESTAMPTZ,
  waived_by     VARCHAR(64),                             -- 遗留项未清仍推进时的豁免人（与批准分开记）
  waive_reason  TEXT,
  created_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pssum_ws_stage
    ON project_stage_summary(workspace_id, stage, iteration);

-- ---------- ⑦ 阶段能力与资源绑定（与画布 agent 节点 capabilityBinding 同构） ----------
--   capability_json 形如：
--     {"tools":["tool:notify"], "skills":["skill:12@1"], "mcps":["mcp:3"],
--      "connectors":["connector:5"], "artifacts":["artifact:88"], "urls":["https://…"]}
--   服务器与数据库统一走 connector_config 引用（服务器为 platform='LINUX_HOST' 的新形态）；
--   凭证沿用 credential_cipher AES-GCM 加密与出参脱敏，永不进 prompt、永不落明文。
CREATE TABLE IF NOT EXISTS project_stage_capability (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_id    VARCHAR(32) NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
  stage           VARCHAR(16) NOT NULL,
  capability_json JSONB NOT NULL,
  note            VARCHAR(256),          -- 这批绑定"给 AI 用来干什么"的说明，随上下文一起注入
  created_by      VARCHAR(64),
  created_at      TIMESTAMPTZ DEFAULT now(),
  updated_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_psc_ws_stage ON project_stage_capability(workspace_id, stage);

-- ---------- ⑧ 有生产副作用的子任务：强制人工门，不可降级（代码层同样校验） ----------
UPDATE project_work_node
SET approval_mode = 'FORCE', trigger_mode = 'MANUAL_START'
WHERE UPPER(TRIM(type)) IN ('RELEASE', 'DEPLOY', 'DB', 'DATABASE');

-- 留档按阶段归集（左栏「本阶段资料」的数据来源）
UPDATE project_artifact a
SET stage = n.stage
FROM project_work_node n
WHERE a.node_id = n.id AND a.stage IS NULL;
