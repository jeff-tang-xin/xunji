# 项目三阶段生命周期契约（V27）

> 本文件是后端与前端**唯一对齐依据**。任何一侧要改语义，先改本文件并在黑板登记，不要各自猜测。
>
> 取代 `stage-lifecycle-contract.md`（V26 四阶段契约，保留作审计轨迹）。
> 变更要点：阶段收敛为 3 个、去掉模板驱动、流程改由 PM 现场规划并**编译成画布 JSON 交给 LiteFlow 执行**
> （不再由 Java 状态机推进子任务）。

## 0. 模型要点（用户已确认）

1. **大阶段固定 3 个**，由平台定义，模板与 PM 均不得自定义阶段集合：

| stage | 中文 | 内容 |
|---|---|---|
| `REQUIREMENT` | 需求 | 项目说明与留档结构、需求分析、多方讨论定范围、原型 |
| `DEVELOP` | 开发 | 架构、详设、用例、开发、冒烟、提测、测试执行 |
| `OPERATE` | 运维 | 发布部署、DB 变更、后期 Bug 与新需求（**常驻，可迭代**） |

   原 `INITIATIVE` 立项阶段取消——"多方讨论定范围"是需求阶段的开头动作，由 PM 现场决定拉哪些角色参与，不单独占一个大阶段。

2. **单向推进、不可回退**：`REQUIREMENT → DEVELOP → OPERATE`，不提供回退接口。
   `OPERATE` 没有"完成"，运维阶段内的新需求/Bug 走**迭代**（`iteration` 递增）。

3. **去模板化**：`project_template` / `project_template_node` 不再被项目流程依赖（表与数据保留作规范溯源，不做 DROP）。
   模板里真正起作用的通用知识迁为 3 份阶段规范 Skill（`stage-requirement-spec` / `stage-develop-spec` / `stage-operate-spec`，
   Anthropic Agent Skills 形态：frontmatter `name` + `description` 写明"何时使用"），绑到对应 agent。

4. **PM 是全局协调者**，不属于任何单个阶段：阶段开始时读项目目标 + 可用 agent + 前阶段总结 + 阶段资源，产出执行计划；
   阶段结束时产出收口总结。PM 会话（`project_pm_session`）贯穿全程，**不受阶段锁定约束**。

5. **规划 → 画布 → LiteFlow 执行**（本轮核心变更）：PM 规划经人批准后，由 `StagePlanCompiler` 编译成画布 JSON，
   走 `DefinitionService.create/publish` 建为真工作流，再由 `RunService.createRunAsync` 起跑。
   节点落库、输入装配、决策门挂起、驳回重生成、超时升级、待决策列表全部由引擎承担，
   项目侧只记 `project_stage_plan.exec_definition_id / exec_run_id`。
   **推进门只认 run 状态**，`project_work_node` 退化为展示与留档台账。

6. **AI 决策、人审核**，两个正交维度：
   - `trigger_mode` = `AUTO`（上游完成自动起）/ `MANUAL_START`（人点才起）
   - `approval_mode` = `AUTO`（AI 决策后直接执行）/ `REVIEW`（给决策与依据，人确认或驳回）/ `FORCE`（必须人点头，不可关闭）
   - **红线**：有生产副作用的子任务（发布、部署、数据变更）一律 `FORCE` + `MANUAL_START`，模板与 PM 都不可降级。

7. **项目性质** `project_mode`：`NEW`（新项目，默认落 `REQUIREMENT`）/ `LEGACY`（老项目维护，直接落 `OPERATE`）。
   前端曾误用 `MAINTAIN`，**正确值只有 `NEW` / `LEGACY`**。

## 1. 数据库（V27）

### 1.1 workspace

- `current_stage VARCHAR(16) NOT NULL DEFAULT 'REQUIREMENT'`（存量 `INITIATIVE` 与非法值回填为 `REQUIREMENT`）
- `project_mode VARCHAR(16) NOT NULL DEFAULT 'NEW'`（存量已落 `OPERATE` 的项目回填 `LEGACY`）

### 1.2 阶段台账与产物

- `project_work_node`：`stage`、`iteration` + 新增 `name`、`trigger_mode`、`approval_mode`、`participants`、`plan_id`
  —— **仅作展示与留档**，执行真相在 run，不参与推进判定。
- `project_artifact.stage`：产物按阶段归集（左栏数据源）。

### 1.3 阶段工作区五表

| 表 | 作用 | 关键点 |
|---|---|---|
| `project_pm_session` | PM 全局会话 | 一项目一条，不受锁定约束 |
| `project_stage_session` | 阶段执行会话 | `locked_at` 非空即锁定：该阶段执行会话与产物只读，人不可再沟通 |
| `project_stage_plan` | PM 阶段规划 | `plan_json` + `deviation` + `status` + **`exec_definition_id` / `exec_run_id`**；同一 stage+iteration 可多条（驳回后重发留审计轨迹），**不建唯一索引** |
| `project_stage_summary` | 阶段收口总结 | `content_md` + `pending_items` + `decided_by`（批准人）/ `waived_by` + `waive_reason`（豁免留档）；同样允许多版本 |
| `project_stage_capability` | 阶段能力与资源绑定 | `capability_json` + `note`，唯一约束 `(workspace_id, stage)` |

### 1.4 执行体（复用引擎既有表，不新建）

`wf_definition` → `wf_definition_version`（发布时冻结 canvas + 编译后的 EL）→ `wf_run`（`trigger_type='STAGE_PLAN'`）
→ `wf_node_run`（节点序列，`status='SUSPENDED'` 即停在决策门）→ `wf_decision`（审批留痕）。

## 2. API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/projects` | 创建：`{name, project_mode, template?}`；只建 3 阶段骨架 + PM 会话 + 真实目录 + **自动产出首阶段规划草稿** |
| GET | `/api/projects` | 项目列表（仅 `binding_type='PROJECT'`，数据权限过滤） |
| GET | `/api/projects/{id}` | 详情：`current_stage`、`stages`、`project_mode`、`nodes`、`artifacts` |
| GET | `/api/projects/{id}/plan` | 当前阶段规划详情（`plan_json` 原文 + 状态） |
| POST | `/api/projects/{id}/plan/{planId}/decide` | 审核规划：`{"decision":"APPROVE"}` → **编译画布 + 发布 + 起跑**；`{"decision":"REJECT","reason":"..."}`（理由必填） |
| GET | `/api/projects/{id}/stage-execution` | **阶段执行视图**：规划摘要 + run 状态 + 节点序列 + 待决策门（前端中栏数据源） |
| POST | `/api/projects/{id}/gate/decide` | **审批决策门**：`{"node_id":"t2","decision":"APPROVE\|REJECT","comment":"..."}`，驳回必须带批注 |
| POST | `/api/projects/{id}/summary/draft` | PM 产出收口总结（`PENDING`，待人批准） |
| POST | `/api/projects/{id}/summary/{summaryId}/decide` | 批准/驳回总结 |
| POST | `/api/projects/{id}/stage/advance` | 推进阶段（门：run `SUCCESS` + 总结 `APPROVED` + 遗留项已豁免） |
| POST | `/api/projects/{id}/iterations` | 运维阶段发起新迭代 |
| GET / PUT | `/api/projects/{id}/stage/{stage}/capability` | 阶段能力与资源绑定读写 |
| POST | `/api/projects/{id}/stage/chat` | **阶段对话（SSE）**：`{"message":"...","task_key":"2"}`（`task_key` 可空＝整阶段）。事件名 `agent.start/agent.delta/agent.result/agent.end/stage.finish/error`，与节点内对话一致 |

> **对话为什么换了对象**：`POST /{id}/nodes/{nodeId}/chat` 依赖两样 V27 之后不存在的东西——
> ① `project_work_node` 台账节点（V27 起规划不再落台账，实测新项目 0 节点，前端连对话入口都点不出来）；
> ② `project_template` 的 seq 匹配（模板已停用）。结果 `matchAgent(owner_role)` 恒失败，硬拼 nodeId 调用只拿到
> 400「节点未匹配到内置 agent」。`stage/chat` 的 agent 取自生效规划的 `tasks[].agents[0]`（次选 `participants[0]`，
> 再兜底阶段默认：REQUIREMENT→`product_analyst`、DEVELOP→`architect`、OPERATE→`devops`）；
> 阶段通用知识**不在这里拼**，3 份 stage-spec skill 已绑到 agent 上、由 `resolveSkills` 展开进系统提示词，
> 本端点只注入「本项目本轮的任务边界」。会话 id 为 `proj-{wsId}-stage-{stage}-{iteration}`：
> 切子任务不丢上下文（同一阶段是同一段工作现场）。
> 实测（0 台账节点的 LEGACY 项目）：`agent.start{agent:devops}` → 63 帧 `agent.delta` → `stage.finish{SUCCESS}`。

### 2.1 `POST /plan/{planId}/decide`（APPROVE）响应

```json
{
  "plan_id": 12,
  "status": "APPROVED",
  "definition_id": 41,
  "definition_version": 1,
  "run_id": "r_3f1c0a...",
  "tasks": [
    {"instance_id": "t1", "name": "需求澄清与范围共识", "agent": "product_analyst",
     "participants": ["product_analyst", "architect"], "scope": "...", "approval_mode": "REVIEW"}
  ],
  "skipped": [
    {"name": "数据现状盘点", "agent": "dba",
     "reason": "agent 无已发布版本（画布 AGENT 节点发布期要求 status='PUBLISHED'）: dba"}
  ],
  "warning": "有子任务未进入流程（agent 无已发布版本），批准前请确认是否需要补发布后重发规划"
}
```

- **幂等**：同一规划重复批准返回 `{"reused": true, ...}` + 既有 run，不重复发布、不重复起跑。
- **失败回收**：发布或起跑异常时删除半成品定义，避免定义列表里留着一条永不执行的阶段流程。

### 2.2 `GET /stage-execution` 响应

```json
{
  "stage": "REQUIREMENT", "stage_label": "需求", "iteration": 1,
  "state": "EXECUTING",
  "plan": {"id": 12, "status": "APPROVED", "tasks": [], "exec_definition_id": 41, "exec_run_id": "r_3f1c0a..."},
  "run_id": "r_3f1c0a...", "run_status": "SUSPENDED", "definition_id": 41,
  "node_runs": [
    {"id": "nr_...", "node_id": "t2", "node_name": "原型设计", "seq": 3,
     "status": "SUSPENDED", "output_json": "{\"output\":\"...\"}"}
  ],
  "waiting_decisions": [
    {"node_run_id": "nr_...", "node_id": "t2", "node_name": "原型设计", "output": "{\"output\":\"...\"}"}
  ]
}
```

`state` 取值：`NO_PLAN` / `PENDING_APPROVAL` / `NOT_STARTED` / `EXECUTING` / `FINISHED`。

## 3. 画布 JSON（阶段规划的编译产物）

### 3.1 结构（definition format v1，由 `CanvasJsonParser` 消费）

```json
{
  "meta": {"name": "[阶段] 订单中心改造 · REQUIREMENT 第1轮", "description": "..."},
  "definitionVersion": 0,
  "triggers": [{"type": "MANUAL", "config": {}}],
  "nodes": [
    {"id": "start", "kind": "START", "refId": "system.start", "name": "阶段输入",
     "inputSchema": {"type": "object", "properties": {}, "required": ["project", "stage", "tasks"]},
     "params": {}},
    {"id": "t1", "kind": "AGENT", "refId": "agent:12@1", "name": "需求澄清与范围共识",
     "params": {"plan_task_key": "1", "scope": "..."},
     "capabilityBinding": {"skills": ["skill:5@1"], "mcps": ["mcp:3@1"]},
     "decisionGate": {"enabled": true, "interaction": "WORKSPACE", "onReject": "REDO", "onTimeout": "ESCALATE"}},
    {"id": "end", "kind": "END", "refId": "system.end", "name": "阶段交付",
     "outputSchema": {"type": "object", "properties": {"output": {"type": "string"}}}}
  ],
  "edges": [
    {"from": "start", "to": "t1", "type": "SEQ", "mapping": [
      {"from": "$.start.output.project", "to": "$.input.project"},
      {"from": "$.start.output.tasks.t1", "to": "$.input.current_task"}
    ]}
  ]
}
```

### 3.2 三条硬约束（违反即发布失败）

1. **只能串行**：`LiteFlowRuleCompiler` 的 P0 只支持 SEQ 线性链（每节点至多一条 SEQ 出边），
   `PAR(WHEN)` / `COND(SWITCH)` 出现即编译失败。PM 规划的子任务按 `depends` 拓扑**拉平成一条链**；
   成环或依赖缺失不报错，剩余节点按 `key` 补到链尾——宁可流程长一点，也不要整个阶段发不出去。
   → 一期不做多 agent 并行 fan-out：需要多视角时写进同一子任务的 `participants`。
2. **AGENT 节点必须引用已发布 agent**：`refId = agent:{agent_config.id}@{version}`，发布期 `validateRef` 只认 `status='PUBLISHED'`。
   查不到已发布版本的子任务会被**跳过并回 `skipped` 清单**（不静默丢子任务）。内置 7 个 agent 由 `SeedInitializer.publishBuiltinAgents()` 发布。
3. **capabilityBinding 引用必须带 `@version`**：`CanvasJsonParser.parseRefs` 对裸 id 抛异常。
   阶段绑定写 `skill:5` 时编译器自动补最新已发布版本；补不到则丢弃该引用并告警。

### 3.3 任务描述走 run 输入，不走节点 params

`AgentNodeExecutor.execute` 只读 `refId` 与 `capabilityBinding`，**params 不进 prompt**。
因此子任务描述放在 run 输入的 `tasks.{instanceId}`，由该节点入边 mapping 取回：

```json
{
  "project": {"id": "ws_...", "name": "...", "root_path": "...", "project_mode": "NEW"},
  "stage": "REQUIREMENT", "iteration": 1,
  "quality_gates": ["..."],
  "resources": [{"kind": "connector", "name": "生产库", "description": "..."}],
  "prior_summaries": [{"stage": "REQUIREMENT", "iteration": 1, "content_md": "..."}],
  "tasks": {"t1": {"name": "...", "scope": "...", "participants": [], "approval_mode": "REVIEW"}}
}
```

- `tasks` 的键由 `compiled.tasks().instance_id` 单一来源生成，画布 mapping 与 run 输入必须同源，否则节点输入为 `null`。
- `resources` 只放**脱敏后的可读清单**（名称/说明/非敏感配置）：`describeCapabilityRefs` 的 SQL 不查 `credential_cipher`、不查内网地址。
  agent 需要的是"有这个资源、干什么用"，不是连接串。凭证永不进 prompt、不明文入库。
- 上游 agent 的产出通过 `$.{prev}.output → $.input.upstream_output` 传给下游，这是阶段内信息传递的唯一通道。

### 3.4 决策门语义

`approval_mode != AUTO` → 节点带 `decisionGate{enabled, interaction:WORKSPACE, onReject:REDO, onTimeout:ESCALATE}`。
引擎对 **AGENT + REDO** 的处理是"先执行产出、再挂起等人审"（`NodeDispatchComponent`），正是"AI 决策、人审核"的形态。
人驳回并带批注 → 该节点按批注重生成（`_previousOutput` + `_revisionComment` 注入输入）。
一期**不设 `timeout`**（留空即不超时），避免超时自动放行造成生产副作用；`REVIEW` 与 `FORCE` 在引擎侧同为决策门，
差别只体现在给人看的标注上，`FORCE` 由后端 `FORCE_TYPES` 强制写入、不接受规划侧降级。

## 4. 前端契约（项目页三栏）

- **左栏**：阶段资源与产物（`GET /stage/{stage}/capability` + `artifacts` 按 `stage` 归集），点击查看内容。
- **中栏**：阶段会话 + 执行流程 + 子任务 + AI 输出 + 决策卡（数据源 `GET /stage-execution`；审批走 `POST /gate/decide`）。
- **右栏**：3 阶段进度（当前高亮、已锁定置灰）+ 项目信息（`project_mode`、根路径）。
- 创建项目弹窗只保留 `NEW` / `LEGACY`，**撤掉模板管理入口**。
- 已锁定阶段（`project_stage_session.locked_at` 非空）禁止输入，产物只读可查。

## 5. 已知限制与遗留

1. 阶段内子任务只能串行（引擎 P0 限制），并行 fan-out 待 `WHEN` 支持。
2. `project_work_node` 台账状态不回写 run，若界面展示节点状态会与 run 不一致——**一律以 `stage-execution` 为准**。
3. PM 规划目前是后端按阶段规范生成的保守草稿（内容全部来自库里事实），接 PM agent 后按项目内容动态重排。
4. 决策门无超时（`timeout` 留空），长期挂起的阶段需人工处理；`GateTimeoutScheduler` 只对配了 timeout 的门生效。
5. 前端**阶段口径已收敛为 V27**（3 阶段、去掉「立项」文案、模板入口以 `SHOW_TEMPLATE_ENTRY` 开关隐藏、`project_mode` 只发 `NEW`/`LEGACY`），但**执行视图与审批交互仍未接入**：界面上看不到 `stage-execution` 的节点进度与 `waiting_decisions`，也无法在页面放行决策门。
6. 400/409 的拒绝原因没有透出到响应体（Spring 默认 `server.error.include-message=never`，只返回 `path`）。生效配置是服务器上的外部 `application.yml`（含凭据），开全局开关或加 `@RestControllerAdvice` 都属未验证的全局改动，本轮未动——前端需要展示原因时先调 `stage-execution` 反推状态。
7. agent 节点无执行超时与取消：线上实测单个子任务节点 165s ~ 600s+ 仍在跑，慢但不失败。
8. 服务重启会把在跑的 run 留成孤儿 `RUNNING`（`wf_run`/`wf_node_run` 无回写与恢复通道）——引擎级既有缺陷，非 V27 引入。
9. V27 **已部署并完成线上 E2E**，闭环结果见 §7。

## 6. 本轮改动清单

| 文件 | 改动 |
|---|---|
| `db/migration/V27__stage_workspace.sql` | 阶段五表 + 节点新列 + `workspace.project_mode` + 规划表 `exec_definition_id` / `exec_run_id` |
| `project/StagePlanCompiler.java`（新） | 规划 → 画布 JSON：拓扑拉平、agent 发布校验、绑定补版本、跳过清单回报 |
| `project/ProjectService.java` | 3 阶段、去模板化创建、批准即编译+发布+起跑、`stageExecution`、`decideStageGate`、推进门改判 run 状态 |
| `project/ProjectController.java` | `GET /{id}/stage-execution`、`POST /{id}/gate/decide` |
| `repository/ProjectRepository.java` | 五表读写、`findPublishedAgent`、`latestPublishedVersion`、`describeCapabilityRefs`、`updatePlanExec`、`findApprovedSummaries` |
| `repository/WorkspaceRepository.java` | `project_mode` 读写、默认阶段改 `REQUIREMENT` |
| `config/SeedInitializer.java` | 阶段规范 Skill 从资源文件加载（frontmatter 解析）、内置 agent 置 `PUBLISHED` |
| `resources/skills/stage-{requirement,develop,operate}-spec.md`（新） | 模板通用知识迁为 Anthropic 形态 Skill |

## 7. 部署与线上 E2E（已完成）

**部署**：`mvn -pl xuanji-server -am package -DskipTests` → jar 72,828,210B，md5 `2d26fda15aac98d5c4b94906429d626f`；服务器 `/data/xuanji`，Flyway 已到 V27，端口 8321；旧 jar 备份 `xuanji-server-v26.jar.bak`。

**执行链**（项目 `ws_37daf4b338f949369276`，run `r_c8359590ae584313abc01c10`）：

| 步 | 结果 |
|---|---|
| 批准规划 | 编译发布 def=7，`skipped=[]`，EL=`THEN(start,t1,t2,t3,end)` |
| t1 产出→挂起 | `state=WAITING_DECISION`、`waiting_decisions=1`、`gate_node_id=t1` |
| `gate/decide APPROVE` | HTTP 200 立即返回 `{status:"RUNNING",accepted:true}` |
| t2 产出→挂起 | 第二次门**不再误判 FAILED**，`error=None` |
| t3 产出（AUTO 门不挂起）→ end | 5 节点全 SUCCESS，`wf_run=SUCCESS` |
| `summary/draft` | `pending_items=[]`、「子任务完成：3 / 3」 |
| `summary/2/decide APPROVE` | HTTP 200 |
| `stage/advance` | HTTP 200 `{current_stage:"DEVELOP",advanced:true,locked_stage:"REQUIREMENT",plan_id:5,plan_status:"PENDING"}` |
| LEGACY 项目 `ws_13bcd57fea294640b8ce` | 创建即 `mode=LEGACY / stage=OPERATE`；对其 advance 返回 400（常驻不可推进）；`stage-execution` 无 run 时 200、`state=PENDING_APPROVAL` |

**本轮为跑通闭环追加的修复**（在上表之外）：

| 文件 | 改动 |
|---|---|
| `engine/RunService.java` | 抽出统一的「产出后 finish vs suspend」收尾判定，修复链上第二个及后续决策门被误判 `FAILED`；新增 `resumeAsync` 使审批立即返回 |
| `engine/RunContext.java` | `applyTo` 跳过 null value，修复 mapping 取空导致 `Map.copyOf` 抛无 message NPE、整条 run FAILED 且日志无线索 |
| `project/StagePlanCompiler.java` | 不给 END 节点生成 `$.start.output.tasks.end` 这种必然取空的任务卡引用 |
| `project/ProjectService.java` | `draftStageSummary` 完成度判定与推进门同源（读 run 节点状态，`t\d+` 为子任务），无执行体时才退回台账 |
| `xuanji-core` `LiteFlowRuleCompiler` + `CanvasCompilerTest` | 缺 START 改抛 `IllegalArgumentException`（与 `@throws` 契约一致）；Kahn 增加回边检测（带环图不再静默编译成功）；过时的 `rejectMultipleOutgoing` 改为 `acceptDagMultipleOutgoing`（编译器有意支持 DAG） |
| `admin-web/src/pages/Project.tsx` | 阶段口径收敛为 3 阶段、去「立项」文案、模板入口以 `SHOW_TEMPLATE_ENTRY` 开关隐藏 |

验证：`mvn -pl xuanji-server -am test` → **BUILD SUCCESS**，`CanvasCompilerTest` 6/6 通过；前端 `npx tsc --noEmit` 零输出、`npm run build` → `✓ 3921 modules transformed`、`✓ built in 20.93s`。
