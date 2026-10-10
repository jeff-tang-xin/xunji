# 03 · 工作流定义与执行引擎规范

> 隶属《DevOps 全链路编排平台设计方案》v0.6 · 详细设计 03/06
> 本文是「前端画布 ⇄ 后端引擎」的核心契约与执行语义的完整规格。

---

## 1. 画布 JSON Schema（definition format v1）

```json
{
  "version": "1.0",
  "meta": { "name": "SDLC-架构选型评审", "description": "..." },
  "triggers": [
    { "type": "MANUAL" },
    { "type": "SCHEDULE", "config": {
        "cron": "0 0 9 ? * MON", "timezone": "Asia/Shanghai",
        "misfire": "FIRE_ONCE", "concurrency": "SKIP",
        "window": { "from": "2026-10-01", "to": null } } },
    { "type": "WEBHOOK", "connectorId": "jira", "eventType": "issue.updated",
      "match": { "fields": { "status": "Approved" } } }
  ],
  "nodes": [
    { "id": "n1", "refId": "agent.tech-research", "kind": "AGENT", "name": "技术调研",
      "params": { "topic": "流程引擎选型", "dimensions": ["成熟度","社区","许可"] },
      "decisionGate": { "enabled": false },
      "retry": { "maxAttempts": 2, "intervalMs": 2000 },
      "timeoutMs": 120000 },
    { "id": "n2", "refId": "agent.doc-writer", "kind": "AGENT", "name": "方案初稿",
      "params": {},
      "decisionGate": { "enabled": true, "interaction": "WORKSPACE", "onReject": "REDO", "timeout": "24h", "onTimeout": "ESCALATE" } },
    { "id": "n3", "refId": "jira.create-issue", "kind": "ATOMIC", "name": "归档" }
  ],
  "edges": [
    { "from": "n1", "to": "n2", "type": "SEQ",
      "mapping": [ { "from": "$.n1.output.report", "to": "$.input.report" } ] },
    { "from": "n2", "to": "n3", "type": "SEQ", "mapping": [] }
  ]
}
```

**字段约束**

| 字段 | 约束 |
|---|---|
| `nodes[].refId` | 必须存在于组件注册表且 enabled；AGENT 形态引用 `agent_config` 已发布版本 |
| `nodes[].decisionGate` | 仅当节点 capabilities 含 SUPPORTS_DECISION_GATE 可启用；`interaction` ∈ {WORKSPACE(默认，产物审阅+批注+修改), FORM(简单表单)}；`artifactRef` 指定审阅产物（缺省=上游节点输出）；`onReject` ∈ {REDO(Agent 按批注重生成), TERMINATE, ROLLBACK}；`onTimeout ∈ {ESCALATE, APPROVE, REJECT}` |
| `edges[].type` | `SEQ`（顺序）/ `COND`（条件，P1 支持）/ `PAR`（并行分支标记，P1 支持） |
| `edges[].mapping` | JSONPath 表达式：`from` 源（`$.{nodeId}.output.*` 或 `$.vars.*`），`to` 目标（`$.input.*` 或 `$.vars.*`） |
| 图结构 | 有向无环（发布时拓扑排序 + 环检测）；单入度校验（COND 节点可多出边） |

## 2. LiteFlow 映射细则

| 画布元素 | 编译产物 |
|---|---|
| 每个节点 | 一个 `NodeDispatchComponent` 实例，以 bean 名 `node_{nodeId}` 注册（Spring 动态注册，实例持有 nodeId，执行时按 refId 路由到注册表组件） |
| SEQ 链 | `THEN(node_n1, node_n2, node_n3)` |
| COND 出边 | `SWITCH(router_{nodeId}).to(node_a.tag("分支A"), node_b.tag("分支B"))`；router 组件按边上的 `expr`（SpEL，上下文含各节点 output 与 vars）求值返回目标 tag |
| PAR 并行 | fork 节点后的多分支编译为 `WHEN(THEN(...), THEN(...))`；**汇合语义 = 全部分支完成后继续**（AND-join） |
| mapping | 不进 EL——`NodeDispatchComponent` 在执行节点前按 edge.mapping 用 JSONPath 装配 `ctx.input`（源：上游 output / vars） |

> ⚠️ 两处 LiteFlow 行为需 P0 spike 实证（标注于主文档风险 #4/#8）：① 动态注册/注销组件 bean 与 `reloadRule` 的协同及旧 run 隔离；② SWITCH/WHEN 在分段执行（决策门切段）下的边界行为。EL 语法以 2.16 官方文档为准。

## 3. 发布流水线（POST /api/definitions/{id}/publish）

```
① 语法校验    画布 JSON Schema 校验
② 引用校验    refId 存在且 enabled；AGENT/WORKFLOW 引用的资产为 PUBLISHED 且钉住具体版本
③ 图校验      拓扑排序 + 环检测；决策门/重试/超时配置与节点 capabilities 匹配
④ 编译        画布 JSON → LiteFlow EL 字符串 + 动态组件注册清单
⑤ 持久化      wf_definition_version 新增记录(status=PUBLISHED, canvas_json, rule_content)
              wf_definition.current_version 指针更新（同事务）
⑥ 热刷        注册动态组件 → LiteFlow 加载新规则；新 run 用新版本，进行中 run 按其绑定版本继续
⑦ 事件        发布成功 → SSE 广播 → 前端刷新版本列表
任一步失败：整体失败，不产生新版本（原子性）
```

## 4. 执行时序（正常路径）

```
触发(MANUAL/API/SCHEDULE/WEBHOOK)
→ 创建 wf_run(绑定 definition_version, status=RUNNING, trigger_type, app_id?)
→ 分段执行器加载该版本规则 → LiteFlow 执行
→ 每个节点经 NodeDispatchComponent：
     ① 按 edge.mapping 装配 input（JSONPath）
     ② 决策门检查：enabled → 走挂起流程（见 §5），本段到此结束
     ③ 写 wf_node_run(RUNNING) → 发节点事件(SSE: node.start)
     ④ 执行（超时/重试策略生效）→ 写 wf_node_run(终态+output) → SSE: node.finish/node.error
     ⑤ output 写入 run 上下文（供下游 mapping）
→ 全部节点完成 → wf_run=SUCCESS → （App 触发时）回调通知
任一节点最终失败 → wf_run=FAILED（已完成的节点输出保留，支持画布回放与断点重跑——重跑 P5 评估）
```

## 5. 决策门：挂起与恢复时序

```
挂起：
  节点② 决策门 enabled → 引擎持久化 context_snapshot（vars + 已完成节点输出 + 当前节点 + 段位置）
  → wf_run=SUSPENDED → 写审批中心待办 → SSE: run.suspended
  → 本段 LiteFlow 执行正常返回（不占用线程）

恢复（决策到达：POST /api/decisions {runId, nodeId, decision, comment, adjustedParams?}）：
  → 写 wf_decision（审计）→ wf_run=RUNNING
  → 从 context_snapshot 重建上下文 → 注入 DecisionContext
  → 以「当前节点为起点」启动下一段 LiteFlow 执行（该节点本次真实执行）
  → 超时策略：调度器扫描 SUSPENDED 超时 run → 按 onTimeout 执行 ESCALATE(通知)/APPROVE/REJECT

进程重启恢复：
  启动时扫描 SUSPENDED run → 快照在 DB → 无需动作（仍在等决策）；决策到达后正常恢复
  RUNNING run 中断（崩溃）→ P5 评估断点续跑；P0-P4 记录为 FAILED 可手动重触发
```

**交互式决策工作区（产物审阅模式）**：决策门不只是「通过/驳回」表单——上游产物（UI 设计稿/需求文档/数据报表）在决策工作区渲染，用户可以：

- **实时批注**：批注锚定产物区域（锚点 + 评论），批量收集修改意见；
- **直接修改**：在线编辑产物内容，产生产物新版本（v1 → v2）；
- **决策**：通过/驳回只是**下一步的前提**——决策通过后，修改后的产物版本 + 批注写回 run 上下文（artifact 区），由 DecisionContext 携带，下游节点经 edge.mapping 引用修改后产物继续执行。

API 扩展：`POST /api/decisions` body 增加 `annotations`（区域锚点批注）与 `artifactUpdates`（产物新版本 + patches）；`wf_decision.payload_json` 存批注与修改摘要（审计可回放）。产物本体入 artifact 存储：v1 = 上游节点输出，v2+ = 人工修改版本。

## 6. 周期触发实现要点

- 调度器：DB 存绑定配置 + Spring `CronExpression` 计算 next fire time + 轮询触发（1s 精度足够）；多实例用 Redis 分布式锁（`SETNX lock:schedule:{bindId}:{fireTime}`）。
- **misfire**（错过触发点，如停机跨过 cron 点）：`FIRE_ONCE`（补一次）/ `FIRE_ALL`（逐次补）/ `SKIP`。
- **concurrency**（上一轮 run 未结束）：`SKIP`（默认）/ `PARALLEL`（允许并行）/ `QUEUE`（结束后补跑一次）。
- 与决策门交互：SUSPENDED 的 run 视为「未结束」，受 concurrency 策略约束。

## 7. SSE 事件通道

| 通道 | 事件 | 消费方 |
|---|---|---|
| `/api/runs/{runId}/events` | run.start / node.start / node.finish / node.error / run.suspended / run.resumed / run.finish | 画布高亮、运行监控 |
| `/api/runs/{runId}/chat` | Agent 事件透传（delta/tool.*，见 04 文档映射表） | Chat 窗口流式 |
| `/api/workspaces/{wsId}/stream` | **工作区对话流**：stage.start / stage.finish / agent.output / tool.call（含授权判定结果）/ decision.request（仅绑定授权策略中「需人工」项） | 工作区对话页（主交互面） |
| `/api/events/broadcast` | 发布成功、审批待办提醒等平台级事件 | 管理台刷新 |

事件信封：`{type, runId, nodeRunId?, ts, payload}`；SSE 网关无状态，跨副本经 Redis pub/sub 广播（01 文档 §6）。

**工作区对话流（主交互面）**：run 在工作区内执行时，阶段级事件、Agent 流式输出、工具调用（含授权判定）、决策请求统一推送至工作区 stream 通道，前端渲染为**对话消息流**；用户在对话内追加指令、批注、决策，产物审阅类决策可跳转决策工作区完整批注/修改（§5）。工作区模型见主文档 4.6：`工作区 = 项目名（路径 = 系统配置根目录/项目名，动态生成）+ 工作流绑定（版本钉住）+ Agent 决策授权（绑定时一次性配置）`。
