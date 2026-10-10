-- =====================================================================
-- 玄机万象（XuanJi）· V1 基线（docs/detail/05 §3 DDL + workspace/report 三表）
-- PostgreSQL 16+ · 禁止手工改库，schema 变更一律新增 V{n} 脚本
-- =====================================================================

-- ============ 工作流定义与执行 ============

CREATE TABLE wf_definition (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name            VARCHAR(128) NOT NULL,
  description     VARCHAR(512),
  canvas_json     JSONB,                                  -- 草稿工作区（发布时冻结进 version 表）
  current_version INT,
  status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT',   -- DRAFT/PUBLISHED/DEPRECATED/OFFLINE
  created_by      VARCHAR(64),
  created_at      TIMESTAMPTZ DEFAULT now(),
  updated_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_wf_definition_status ON wf_definition(status);

CREATE TABLE wf_definition_version (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  definition_id BIGINT NOT NULL,
  version       INT NOT NULL,
  canvas_json   JSONB NOT NULL,
  rule_content  TEXT,                                      -- 编译后的 LiteFlow EL
  status        VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
  published_by  VARCHAR(64),
  published_at  TIMESTAMPTZ DEFAULT now(),
  UNIQUE (definition_id, version)
);

CREATE TABLE wf_run (
  id               VARCHAR(32) PRIMARY KEY,               -- r_ 前缀
  definition_id    BIGINT NOT NULL,
  version          INT NOT NULL,
  trigger_type     VARCHAR(16) NOT NULL,                  -- CHAT_INPUT/MANUAL/API/SCHEDULE/WEBHOOK
  trigger_payload  JSONB,
  app_id           VARCHAR(32),
  parent_run_id    VARCHAR(32),                           -- 子流程 run 树
  root_run_id      VARCHAR(32),
  status           VARCHAR(16) NOT NULL,                  -- RUNNING/SUSPENDED/SUCCESS/FAILED/CANCELLED
  context_snapshot JSONB,                                 -- 决策门快照（vars+节点输出+位置）
  input_json       JSONB,
  output_json      JSONB,
  error            VARCHAR(1024),
  started_at       TIMESTAMPTZ,
  finished_at      TIMESTAMPTZ
);
CREATE INDEX idx_wf_run_def_status ON wf_run(definition_id, status);
CREATE INDEX idx_wf_run_parent     ON wf_run(parent_run_id);
CREATE INDEX idx_wf_run_app        ON wf_run(app_id);
CREATE INDEX idx_wf_run_suspended  ON wf_run(status, started_at);

CREATE TABLE wf_node_run (
  id           VARCHAR(40) PRIMARY KEY,
  run_id       VARCHAR(32) NOT NULL,
  node_id      VARCHAR(64) NOT NULL,
  node_name    VARCHAR(128),
  seq          INT,
  status       VARCHAR(16) NOT NULL,
  input_json   JSONB,
  output_json  JSONB,
  error        VARCHAR(1024),
  started_at   TIMESTAMPTZ,
  finished_at  TIMESTAMPTZ
);
CREATE INDEX idx_wf_node_run_run ON wf_node_run(run_id);

CREATE TABLE wf_decision (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id        VARCHAR(32) NOT NULL,
  node_id       VARCHAR(64) NOT NULL,
  decision_type VARCHAR(16) NOT NULL,                     -- NODE_GATE / TOOL_APPROVAL
  decision      VARCHAR(16) NOT NULL,                     -- APPROVE/REJECT/ADJUST
  decided_by    VARCHAR(64),
  remark        VARCHAR(512),                             -- PG 下避开 comment 关键字
  payload_json  JSONB,                                    -- 交互式决策：批注 + 产物修改摘要
  decided_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_wf_decision_run ON wf_decision(run_id);

-- ============ 组件与集成 ============

CREATE TABLE component_registry (
  node_id       VARCHAR(64) PRIMARY KEY,
  node_type     VARCHAR(16) NOT NULL,                     -- ATOMIC/AGENT/WORKFLOW
  plugin_module VARCHAR(64),
  metadata_json JSONB NOT NULL,
  enabled       BOOLEAN DEFAULT TRUE
);

CREATE TABLE connector_config (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  platform          VARCHAR(32) NOT NULL,
  name              VARCHAR(128),
  endpoint          VARCHAR(256),
  credential_cipher BYTEA,                                -- AES-GCM 加密
  extra_json        JSONB,
  status            VARCHAR(16) DEFAULT 'ENABLED',
  UNIQUE (platform, name)
);

CREATE TABLE model_provider_config (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  protocol          VARCHAR(16) NOT NULL,                 -- OPENAI_PROTOCOL / ANTHROPIC_PROTOCOL（Q5）
  base_url          VARCHAR(256) NOT NULL,
  model             VARCHAR(64) NOT NULL,
  credential_cipher BYTEA,                                -- api_key，AES-GCM 加密
  params_json       JSONB,
  status            VARCHAR(16) DEFAULT 'ENABLED'
);

CREATE TABLE mcp_config (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name                  VARCHAR(128) NOT NULL,
  endpoint              VARCHAR(256) NOT NULL,
  auth_cipher           BYTEA,
  discovered_tools_json JSONB,                            -- 工具自动发现结果（Q6：可上画布）
  version               INT NOT NULL DEFAULT 1,
  status                VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  updated_by            VARCHAR(64),
  updated_at            TIMESTAMPTZ DEFAULT now()
);

-- ============ 资产（统一生命周期 DRAFT→PUBLISHED→DEPRECATED→OFFLINE） ============

CREATE TABLE agent_config (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name              VARCHAR(128) NOT NULL,
  description       VARCHAR(512),
  model_provider_id BIGINT NOT NULL,
  system_prompt     TEXT,
  tools_json        JSONB,                                -- TOOL/MCP/AGENT 引用（钉住版本）
  permission_json   JSONB,
  params_json       JSONB,
  version           INT NOT NULL DEFAULT 1,
  status            VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  updated_by        VARCHAR(64),
  updated_at        TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_agent_config_status ON agent_config(status);

CREATE TABLE tool_registry (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name          VARCHAR(128) NOT NULL,
  description   VARCHAR(512),
  source_type   VARCHAR(16) NOT NULL,                     -- BEAN/OPENAPI/SCRIPT
  source_ref    VARCHAR(256),
  input_schema  JSONB,
  output_schema JSONB,
  version       INT NOT NULL DEFAULT 1,
  status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  updated_by    VARCHAR(64),
  updated_at    TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE skill_repo (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  content_uri VARCHAR(256) NOT NULL,
  version     INT NOT NULL DEFAULT 1,
  status      VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  updated_by  VARCHAR(64),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

-- ============ 应用层 ============

CREATE TABLE app (
  id                  VARCHAR(32) PRIMARY KEY,            -- app_ 前缀
  name                VARCHAR(128) NOT NULL,
  bound_asset_type    VARCHAR(16) NOT NULL,               -- WORKFLOW/AGENT
  bound_asset_id      BIGINT NOT NULL,
  bound_asset_version INT NOT NULL,
  callback_url        VARCHAR(256),
  status              VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_by          VARCHAR(64),
  created_at          TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE app_credential (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  app_id            VARCHAR(32) NOT NULL,
  key_hash          CHAR(64) NOT NULL,                    -- SHA-256(appkey+salt)，明文不落库（Q8）
  key_prefix        VARCHAR(12) NOT NULL,                 -- 展示用前缀 app-Xk29
  scopes_json       JSONB,
  rate_limit_json   JSONB,
  ip_allowlist_json JSONB,
  expires_at        TIMESTAMPTZ,
  status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at        TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_app_credential_app    ON app_credential(app_id);
CREATE INDEX idx_app_credential_prefix ON app_credential(key_prefix);

-- ============ 工作区与报表（主文档 4.6 / 1.5，Q11 口径） ============

CREATE TABLE workspace (
  id                  VARCHAR(32) PRIMARY KEY,            -- ws_ 前缀
  name                VARCHAR(128) NOT NULL,              -- 项目名（路径 = 根目录/项目名，动态生成）
  root_path           VARCHAR(256) NOT NULL,
  binding_type        VARCHAR(16) NOT NULL,               -- AGENT / WORKFLOW（Q11：二选一）
  bound_asset_id      BIGINT,
  bound_asset_version INT,
  authorization_json  JSONB,                              -- Agent 决策授权（绑定时一次性配置）
  created_by          VARCHAR(64),
  created_at          TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE report_def (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name            VARCHAR(128) NOT NULL,
  workflow_ref    JSONB NOT NULL,                          -- {definitionId, version}（须含页面生成节点）
  schedule_json   JSONB,                                   -- 定时执行计划
  datasource_ref  JSONB,                                   -- 数据源连接器引用
  status          VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_by      VARCHAR(64),
  created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE report_run (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  report_def_id  BIGINT NOT NULL,
  run_id         VARCHAR(32),                              -- 关联 wf_run
  snapshot_json  JSONB,                                    -- 固化看板快照（生成时点数据）
  status         VARCHAR(16) NOT NULL,
  generated_at   TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_report_run_def ON report_run(report_def_id);
