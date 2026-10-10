# 05 · REST API 与数据模型

> 隶属《玄机万象 · 全流程编排与自定义 Agent 平台设计方案》v0.7 · 详细设计 05/06
> 命名：资源复数、动词用子路径（publish/cancel/resume）；统一响应 `{code, message, data}`；分页 `page/size`。
> 数据库：**PostgreSQL 16 + Flyway**（Q2 决议）——本节 DDL 即 `V1__init.sql` 的内容基线。

---

## 1. API 分组清单

| 组 | 端点 | 说明 |
|---|---|---|
| 定义 | `GET/POST /api/definitions`；`GET/PUT /api/definitions/{id}`；`POST /api/definitions/{id}/publish`；`GET /api/definitions/{id}/versions` | 画布 JSON 的存取与发布 |
| 运行 | `POST /api/runs`（内部触发）；`GET /api/runs/{id}`；`GET /api/runs/{id}/nodes`；`POST /api/runs/{id}/cancel`；`GET /api/runs/{id}/events`(SSE)；`GET /api/runs/{id}/chat`(SSE) | 执行与观测 |
| 决策 | `GET /api/decisions/pending`；`POST /api/decisions` | 审批中心 |
| 组件 | `GET /api/components` | 画布面板数据源 |
| Agent | `CRUD /api/agents`；`POST /api/agents/{id}/publish`；`GET /api/agents/{id}/versions` | agent_config 管理 |
| 工具/技能/MCP | `CRUD /api/tools` `/api/skills` `/api/mcp` + 各自 `/publish`；`POST /api/mcp/{id}/discover` | 三类资产 |
| 模型 | `CRUD /api/model-providers`；`POST /api/model-providers/{id}/test` | 模型供应商（协议双轨） |
| 连接器 | `GET /api/connectors`；`PUT /api/connectors/{id}/credential`；`POST /api/connectors/{id}/test` | 连接器配置 |
| 应用 | `CRUD /api/apps`；`POST /api/apps/{id}/publish`；`POST/DELETE /api/apps/{id}/credentials`；`POST /api/apps/{id}/credentials/{keyId}/rotate` | App 与 AppKey |
| **开放** | `POST /api/v1/apps/{appId}/runs`；`POST /api/v1/apps/{appId}/chat-messages`(SSE)；`GET /api/v1/apps/{appId}/runs/{runId}`；`POST /api/v1/apps/{appId}/runs/{runId}/resume` | AppKey 鉴权（Bearer，纯 key，Q8 决议） |
| Webhook | `POST /api/webhooks/{connectorId}/{eventType}` | 外部平台回调（连接器验签） |
| 嵌入 | `GET /embed/v1/chat.js`；`GET /embed/{appId}` | 页面嵌入 |

## 2. 关键请求/响应示例

**发布定义**
```http
POST /api/definitions/101/publish
→ 200 { "code": 0, "data": { "version": 7, "ruleContent": "THEN(node_n1,node_n2,node_n3)", "warnings": [] } }
→ 422 { "code": 42201, "message": "节点 n2 引用的 agent:doc-writer@9 不存在或未发布" }
```

**触发运行（异步）**
```http
POST /api/runs  { "definitionId": 101, "input": {...}, "async": true }
→ 200 { "data": { "runId": "r_9f2c", "status": "RUNNING" } }
```

**决策（恢复挂起节点）**
```http
POST /api/decisions  { "runId": "r_9f2c", "nodeId": "n2",
                       "decision": "APPROVE", "comment": "方案可行", "adjustedParams": null,
                       "annotations":     [ { "anchor": "region#筛选条件区", "text": "增加「下单渠道」筛选" } ],
                       "artifactUpdates": [ { "artifactId": "art_ui_1", "newVersion": 2, "patches": [ ... ] } ] }
→ 200 { "data": { "runId": "r_9f2c", "status": "RUNNING" } }

POST /api/workspaces   { "name": "order-sys",                          -- 只填项目名，路径 = 系统配置根目录/name 动态生成
                         "workflowRefs": [ "workflow:sdlc-standard@v3" ],
                         "authPolicy":  [ { "agentRef": "agent:requirement-writer@2",
                                            "autoApprove": [ "ops.read.*", "confluence.*" ],
                                            "requireApproval": [ "artifact" ] } ] }
  -- Agent 决策授权在绑定时一次性完成；运行时决策请求按 authPolicy 路由
GET  /api/workspaces/{id}/stream   → SSE：stage.start / agent.output / tool.call / decision.request / stage.finish
```

**第三方调用（AppKey）**
```http
POST /api/v1/apps/app_7a1/runs
Authorization: Bearer app-Xk29...   # 明文仅创建时返回一次
→ 202 { "data": { "runId": "r_b31d" } }   # 完成后回调 callback_url（POST，含 runId+status）
```

## 3. 完整 DDL（PostgreSQL 16 · Flyway V1__init.sql）

```sql
-- ============ 工作流定义与执行 ============

CREATE TABLE wf_definition (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name            VARCHAR(128) NOT NULL,
  description     VARCHAR(512),
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
  id               VARCHAR(32) PRIMARY KEY,               -- r_ 前缀 ULID
  definition_id    BIGINT NOT NULL,
  version          INT NOT NULL,
  trigger_type     VARCHAR(16) NOT NULL,                  -- MANUAL/API/SCHEDULE/WEBHOOK
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
  payload_json  JSONB,                                    -- 交互式决策：批注 + 产物修改摘要（03 文档 §5）
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
  key_hash          CHAR(64) NOT NULL,                    -- SHA-256(appkey+salt)，明文不落库（Q8：纯 key）
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
```

**Flyway 约定**：脚本位于 `xuanji-server/src/main/resources/db/migration/`，`V{n}__{desc}.sql` 严格递增；`updated_at` 维护用统一触发器（V2 脚本）或应用层审计填充；schema 变更只增不改历史脚本。
