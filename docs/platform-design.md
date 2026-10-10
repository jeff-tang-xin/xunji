# 玄机万象（XuanJi）· 全流程编排与自定义 Agent 平台 · 设计方案

> 版本 v0.9（草案） · 2026-10-08 ｜ v0.2：Agent 全员可配置、统一节点模型 ｜ v0.3：统一资产模型与发布中心 ｜ v0.4：应用层统一 API 与 AppKey ｜ v0.5：执行语义（周期触发、决策门） ｜ v0.6：一切皆节点 + 旗舰场景 SDLC ｜ v0.7：待决问题全部决议（见第 8 章） ｜ v0.8：工作区（执行交互容器与授权边界）+ 交互式决策循环 + 前端 TS + 报表业务数据口径 ｜ v0.9：工作区输入触发与随时接入 + 节点类型差异化（START/END 内置）+ 工作区绑定约束与附件 + Agent 能力两级绑定 + 管理台权限体系（Q9–Q13）
> 状态：待评审
> 说明：本文是平台建设总体方案，覆盖定位、架构、选型、核心设计、连接器、路线图与风险。选型数据均经 2026-10-08 官方渠道验证，标注「未验证」处为待专项确认项。

---

## 0. 一页纸总览

**平台名**：玄机万象（XuanJi）｜ **一句话**：Java 技术栈的「全流程编排 + 自定义 Agent」平台——画布上拖出业务全流程，智能环节由全员可配置的 Agent 承担，一切皆资产可发布，发布即可被第三方调用与嵌入。

**两大支柱**

| 支柱 | 内涵 |
|---|---|
| **全流程** | DevOps 全链路（需求→项目→开发→测试→运维，对接 Jira/禅道/云效）的确定性编排：顺序/分支/并行/子流程递归 + 周期性执行 + 节点级人工决策门（挂起/恢复/超时升级；**交互式决策工作区**——产物审阅 + 实时批注 + 在线修改，修改后产物流向下游节点，决策只是下一步的前提） |
| **自定义 Workflow Agent** | Agent 全员可配置、不分主子（agent_config 统一承载）；节点三形态（atomic/agent/workflow）递归组合；SOP 复合模式（确定性框架 + Agent 自主填充） |

**九条设计原则**：可插拔（三层 SPI）｜定义即数据｜节点引用已注册组件｜确定性/自主性分界｜每期可用｜Agent 全员可配置不分主子｜统一节点模型｜一切皆资产皆可发布｜发布即可被消费

**技术底座**：Spring Boot（JDK 21）· LiteFlow（确定性编排）· AgentScope Java 2.0（Agent 运行时）· LogicFlow（画布）· PostgreSQL + Flyway + Redis

**管理台权限体系**（Q13）：RBAC（用户→角色→菜单，每人菜单与权限不同）+ 组织树 + 数据权限四档（`all`/`under_org`/`org`/`only_create`，与菜单正交）+ 平台管理员 bypass（不受组织与数据权限控制）；用户仅由 admin 创建、密码随机生成（admin 初始化密码随机、仅控制台输出一次）——见 4.10。

**六期交付**：P0 骨架 → P1 编排闭环 → P2 模型管理 → P3 Agent 能力 + 执行语义 → P4 连接器 → P5 资产发布 + 应用层

**旗舰场景**：SDLC 全流程编排——需求设计评审 / 原型设计评审 / 架构选型评审 / 测试用例 / 详设概设评审 / 开发与功能测试 / 运维部署，七个阶段节点，每个节点都是 workflow + agent 的复合资产，可递归下钻（见 1.4）。

**场景路线**：SDLC（首个旗舰）→ 工作报表与看板生成 → 数据治理——新场景 = 新的连接器/节点插件组合，平台核心零改动（见 1.5）。

**文档地图**：本篇为总体方案（架构与决策）；**可实施级细节见 `docs/detail/`**——01 工程结构与实施基线 ｜ 02 SPI 与组件注册表规范 ｜ 03 工作流定义与执行引擎规范（画布 JSON Schema / LiteFlow 映射 / 发布·执行·挂起恢复时序）｜ 04 AgentScope 集成规范 ｜ 05 REST API 与数据模型（完整 DDL）｜ 06 前端、测试与 P0 任务分解。

---

## 1. 项目概述

### 1.1 背景与定位

平台名**玄机万象**（英文名 XuanJi）。定位为**全流程编排与自定义 Agent 平台**：以可视化工作流编排为核心，将各业务领域平台能力以「连接器插件」形式接入，串联「业务步骤编排 + AI Agent」的标准工作流程。

首个旗舰领域为 **DevOps 全链路**：需求管理、项目管理、开发管理、测试管理、运维管理五个领域（基于 **Jira、禅道、云效**）以连接器接入；后续场景路线见 1.5。

一句话概括：**画布上拖出流程，流程驱动各平台协同，Agent 负责其中需要智能的环节。**

### 1.2 设计原则

1. **一切可插拔**：节点、触发器、连接器均为插件（Maven 模块 + Spring Boot 自动装配，编译期插拔），核心引擎稳定不动。
2. **定义即数据**：工作流 = JSON 定义（画布产出），引擎校验后注册执行。借鉴 Mastra Dynamic Workflows 的「definition-as-data」模式。
3. **节点引用已注册组件**：画布上能放什么由后端注册表决定，画布不承载任意代码——这是平台的安全边界。
4. **确定性 / 自主性分界**：LiteFlow 管确定性流程（顺序/分支/并行/数据映射），AgentScope 管 Agent 自主推理循环。Agent 节点在编排引擎眼里只是普通组件。
5. **每期可用**：P0–P5 每期结束都交付可运行、可演示的增量，不做大爆炸式交付。
6. **Agent 全员可配置，不分主子**：所有 Agent 均是配置记录（模型/提示词/工具/权限），进同一注册表、用同一套配置界面；不存在代码写死的特权 Agent，组合关系外置于画布而非隐藏在代码里。
7. **统一节点模型**：画布节点三形态（原子操作 / Agent / 子工作流）共用同一套配置与执行契约，支持递归组合；Agent 节点可绑定子工作流作为执行程序（SOP 模式：确定性框架 + Agent 自主填充）。
8. **一切皆资产，皆可发布**：workflow / agent / skill / mcp / tool 五类资产遵循统一生命周期（草稿→发布→弃用→下线），发布即版本固化、不可变；一切跨资产引用钉住已发布版本（见 4.8）。
9. **发布即可被消费**：已发布的 workflow / agent 可发布为**应用（App）**，第三方经统一 API 入口 + AppKey 调用，或经嵌入组件（Chat Widget / iframe）上页面；AppKey 哈希落库、可轮换、全量审计（见 4.9）。
10. **一切皆节点**：宏观阶段、微观步骤、Agent、工具操作，全部以统一节点形态出现在画布上；画布是唯一组合面，宏观流程与微观步骤同构、可递归下钻（深度上限内无限组合，见 1.4 旗舰场景）。

### 1.3 目标与非目标

**目标（本期范围）**

| 能力 | 说明 |
|---|---|
| 可视化编排 | LogicFlow 画布：拖拽、连线、节点参数配置，产出工作流定义 |
| 执行引擎 | LiteFlow 规则存 DB + 热刷新；执行记录与画布状态回显 |
| Agent 能力 | AgentScope Java 运行时：LLM 节点、Agent 节点、人工审批（HITL） |
| 执行入口 | 手动（UI）/ API / Webhook / 定时，四类触发器 |
| 连接器 | Jira、禅道、云效三平台连接器框架与实现 |
| 模型管理 | 多供应商配置，运行时动态构建模型实例 |

**非目标（明确不做，防止范围蔓延）**

- 不做 OSGi 式热插拔（编译期插拔已满足需求，注册表设计保留热插升级路径）
- 不做 BPMN 审批流引擎（流程类型为业务步骤编排；人工介入走 AgentScope 权限系统）
- 不做自研 RAG 全套（后续仅做检索节点，向量库用现成产品）
- 不做多租户 SaaS 化（P5 再评估）

### 1.4 旗舰场景：SDLC 全流程编排（一切皆节点）

平台的旗舰场景是一条**软件研发全生命周期（SDLC）的宏观工作流**——它本身是画布上的一条流程，由多个阶段节点组成，每个阶段节点内部又是一个「workflow + agent」的复合子流程（多场景），可继续下钻：

```
[需求设计和评审] → [原型设计和评审] → [架构与选型设计和评审] → [测试用例设计]
    → [开发详设/概设与评审] → [开发与功能测试] → [运维与部署]
```

每个阶段节点 = 一个**已发布的 workflow 资产**（引用钉住）。以「架构与选型设计和评审」下钻为例：

```
[Agent] 候选技术调研与对比（检索 + 评测维度打分）
  → [Agent] 选型方案文档初稿生成
  → [决策门] 架构师评审（挂起等人：通过 / 驳回 / 调整参数）
  → [Agent] 按评审意见修订
  → [连接器] 归档至 Jira / 云效 Wiki
```

要点：

- **一切皆节点**：宏观阶段、微观步骤、Agent、连接器操作，全部以统一节点形态出现在画布上；宏观流程与微观步骤同构，可递归下钻（深度上限内）。
- **阶段即资产**：每个阶段节点引用的是已发布的 workflow 资产（版本钉住）——阶段模板可复用、可独立演进、可跨团队共享（发布中心 + bundle 导入导出）。
- **多场景 Agent**：各阶段由不同 agent_config 驱动（调研 Agent、文档 Agent、测试用例 Agent、部署 Agent……），全员可配置、对等协作。
- **评审机制贯穿**：需求评审、原型评审、架构评审、用例评审、详设评审均以决策门节点承载（挂起等人 → 决策 → 恢复），配合周期触发（如每周需求盘点）构成完整的研发执行语义。
- 三平台连接器在其中的落点见 5.5 典型工作流。

### 1.5 场景路线（多场景泛化）

| 阶段 | 场景 | 形态（全部由既有节点类型组合，核心零改动） |
|---|---|---|
| 首个旗舰 | **SDLC 全流程编排**（1.4） | 七阶段 workflow 资产 + 多场景 Agent + 决策门 + 三平台连接器 |
| 后续 | **工作报表（看板生成）** | Schedule 周期触发 → **业务数据源连接器**（外部业务库/业务 API，**真实业务数据**，非平台自身数据）→ 数据加工 → Agent 分析与摘要 → 看板渲染/导出节点 → 分发连接器（邮件/IM） |
| 后续 | **数据治理** | 数据源探查（连接器）→ Agent 质量诊断 → 治理方案决策门（人工审批）→ 修复执行（连接器/Agent）→ 治理报告归档 |

架构含义：新场景**不引入新核心机制**——都是「连接器节点 + Agent 节点 + 决策门 + 周期触发」的重组，验证「一切皆节点」的泛化能力；成熟场景以**资产包（bundle）**沉淀，跨环境复用（4.8）。

---

## 2. 总体架构

### 2.1 架构图

```
┌────────────────────────── 前端（React）──────────────────────────┐
│  LogicFlow 编排画布        Chat 执行入口          管理台            │
│  · 节点面板(注册表驱动)     · SSE 流式对话         · 模型供应商       │
│  · 拖拽/连线/参数表单       · 审批交互(HITL)       · 连接器凭据       │
│  · 运行状态实时高亮                                · 执行日志/发布中心 │
└──────────────┬───────────────────────────────────────────────────┘
               │ REST + SSE/WebSocket
┌──────────────▼──────────────── Java 后端（Spring Boot 3.5.x）────┐
│  接入层   应用网关(AppKey鉴权/限流) · 执行入口 · SSE网关 · Webhook  │
│  ─────────────────────────────────────────────────────────────  │
│  编排引擎层   LiteFlow（规则存 DB + 热刷新）                       │
│              画布 JSON ⇄ 规则转换器（参考 liteflow-el-builder）    │
│  Agent 运行时 AgentScope Java 2.0（经 AgentRuntime 适配层隔离）    │
│              · 事件流 → SSE（聊天窗 + 画布高亮双消费）              │
│              · 权限系统 → 审批节点（HITL）                         │
│              · 分布式 session/memory（Redis/PostgreSQL）           │
│  ─────────────────────────────────────────────────────────────  │
│  组件注册表 ComponentRegistry ←—— 唯一插拔点（三层 SPI）           │
│  ┌─────────────── 插件（Maven 模块 + 自动装配）───────────────┐   │
│  │ node-basic(业务Bean) node-llm node-agent                  │   │
│  │ connector-jira   connector-zentao(禅道)   connector-yunxiao│   │
│  └──────────────────────────────────────────────────────────┘   │
│  存储   PostgreSQL（定义/run/注册表/凭据密文） + Redis             │
└──────────────┬───────────────────────────────────────────────────┘
               │ REST API / Webhook
┌──────────────▼──────────────────────────────────────────────────┐
│  外部平台：Jira（需求/项目） · 禅道（需求/测试） · 云效（开发/运维） │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 分层职责

| 层 | 职责 | 关键约束 |
|---|---|---|
| 前端 | 画布编辑、执行观测、管理台 | 节点面板与参数表单完全由注册表元数据驱动，新增节点前端零改动 |
| 接入层 | 四类触发入口、SSE 事件分发、凭据加密存取 | 凭据密文落库，密钥不进代码库 |
| 编排引擎层 | 工作流定义校验/注册/执行/热刷，run 生命周期 | 新 run 用新规则，进行中 run 按原规则跑完 |
| Agent 运行时层 | Agent 推理循环、工具调用、事件流、权限 | 平台代码不直接 import `io.agentscope`，只经适配层 |
| 插件层 | 节点/触发器/连接器的具体实现 | 只依赖 SPI 接口，不依赖核心内部实现 |
| 存储 | 定义版本化、执行记录、注册表、配置 | 定义变更全量留版本，可回滚 |

---

## 3. 技术选型

### 3.1 选型总表

| 层 | 选型 | 版本基线 | 验证状态 | 选择理由 |
|---|---|---|---|---|
| 运行时 | JDK | 21 | ✅ 已确认（Q1） | 虚拟线程利好 SSE 网关 |
| 应用框架 | Spring Boot | 3.5.x | ✅ 已确认（Q1 连带） | 生态成熟，LiteFlow/AgentScope 均有良好集成 |
| 编排引擎 | LiteFlow | 2.16+（spring-boot-starter） | ✅ 已验证（GitHub 3.9k⭐，活跃） | 组件即 Spring Bean；规则存 DB + 热刷；v2.16+ AI Agent 一等公民 |
| Agent 运行时 | AgentScope Java | 2.0.3 | ✅ 已验证（v2.0.0 GA 2026-07，5.9k⭐） | 31 种事件流/权限系统/分布式会话/沙箱开箱即得 |
| 前端画布 | LogicFlow | 2.x | ✅ 已验证（11.7k⭐，活跃） | 业务自定义节点；官方数据转换范式；中文社区 |
| 前端框架 | React 18 + TypeScript + Vite + AntD 5 | — | ✅ 已确认（Q3，TS 补充确认） | 企业中后台组件密度高，画布+管理台匹配；TS 保证画布 JSON/资产模型类型安全 |
| 数据库 | PostgreSQL 16 + Flyway | — | ✅ 已确认（Q2） | Flyway 管理 schema 版本演进 |
| 缓存/会话 | Redis | 7.x | ✅ 已确认 | AgentScope 分布式 session 载体 + 调度分布式锁 |
| 部署形态 | 单体模块化（Maven 多模块） | — | ✅ 已确认（Q4） | 接口按微服务边界设计，后续可拆 |

### 3.2 关键取舍说明

**① 为什么 AgentScope Java，而不是 Spring AI / LangChain4j？**

- 平台核心诉求是「**运行 Agent**」而非「调模型」。事件流（同时喂聊天窗流式输出与画布节点高亮）、权限系统（HITL 审批）、分布式 session/记忆、工具沙箱——这些是 Dify 类平台的核心功能，用「调模型的库」意味着全部自研。
- AgentScope 模型层自研（`provider:model` 字符串 + 扩展模块 dashscope/openai/anthropic/gemini/ollama），再引入 Spring AI/LangChain4j 会造成**双模型抽象层**，复杂度翻倍。
- 风险对冲：v2.0.0 GA 仅 3 个月，API 可能变动 → 平台与 AgentScope 之间建**薄适配层**（`AgentRuntime` 接口，见 4.4），并锁定版本 2.0.3；API 变动只改适配层。

**② 为什么 LiteFlow，而不是 Flowable / Camunda（BPMN）？**

- 流程类型已确认为**业务步骤编排**（A 类），无长事务人工等待；人工介入由 AgentScope 权限系统承担，不需要 BPMN 的任务/待办模型。
- LiteFlow 轻量、组件即 Spring Bean、规则存 DB + 热部署平滑刷新，与「画布改流程不重启」的目标天然契合。

**③ 为什么 LogicFlow，而不是 React Flow / bpmn.io？**

- 连接器节点需要定制外观与交互，LogicFlow 的业务自定义节点能力最强。
- 官方提供画布数据与后端引擎数据结构互转范式，「画布 JSON → 引擎规则」有现成参考。
- bpmn.io 深度绑定 BPMN 语义，与我们的自定义定义模型不匹配。
- 注意：**只用 LogicFlow 的画布能力，不用其自带执行引擎**（执行归 LiteFlow）。

**④ 借鉴自 Mastra 调研的设计模式**（详见附录 A）

definition-as-JSON、节点引用已注册组件、提交时校验+同 id 替换、定义持久化与版本化、mapping 数据流转、热生效——六条模式全部落在本方案的数据模型与注册表设计中。

---

## 4. 核心设计

### 4.1 三层 SPI（平台核心对插件方的全部契约）

```java
/** 节点 SPI —— 画布上一切节点的统一契约（业务/LLM/Agent/连接器节点） */
public interface WorkflowNode {
    /** 元数据：驱动画布面板展示与参数表单动态渲染 */
    NodeMetadata metadata();
    /** 执行：ctx 携带输入数据、节点配置参数、run 上下文（traceId、变量区） */
    NodeOutput execute(NodeContext ctx) throws Exception;
}

/** 触发器 SPI —— 工作流的四类启动/恢复入口 */
public interface Trigger {
    TriggerMetadata metadata();
    /** webhook 型：解析外部平台请求 → 匹配工作流 → 启动新 run 或恢复挂起 run */
    TriggerResult onWebhook(WebhookRequest request);
}

/** 连接器 SPI —— 一个外部平台一个实现（Jira/禅道/云效） */
public interface Connector {
    ConnectorMetadata metadata();            // 平台标识、凭据字段 schema、端点
    List<WorkflowNode> nodes();              // 该平台的操作 → 注册为画布节点
    List<Trigger> triggers();                // 该平台的事件 → 注册为触发器
    void validateCredential(Credential c);   // 凭据连通性校验（保存前调用）
}
```

设计要点：

- **参数表单 schema 是画布体验的关键**：每个节点声明 `paramSchema`，前端据此动态渲染配置表单——新增一种节点，前端零改动。
- **inputSchema/outputSchema 用于连线校验**：画布连线时校验上下游数据结构兼容性，不匹配时提示配置 mapping（数据映射），对应 Mastra 的 mapping entry 模式。
- 插件只依赖这三个接口 + 少量 context/record 类型，**不依赖核心内部实现**，保证核心可演进。

### 4.2 组件注册表（ComponentRegistry）

- 启动时扫描所有 Spring 容器中的 SPI 实现，登记进注册表（含插件模块来源、启用状态）。
- **节点三形态统一登记**：`atomic`（连接器工具/业务 Bean）、`agent`（引用 `agent_config` 的智能单元）、`workflow`（引用另一工作流定义的子流程）——三种形态共用 `WorkflowNode` SPI 与元数据契约（`NodeMetadata.kind` 区分），画布任意位置可放任意形态，实现递归组合。
- 对外提供两类查询：**面板清单**（前端画布左侧节点面板的数据源）、**执行查找**（引擎按 nodeId 定位组件）。
- 定义发布时校验：画布 JSON 中引用的每个 nodeId 必须存在于注册表且 enabled——对应 Mastra「提交时校验」模式。
- 保留运行时注册接口（`register/unregister`），为将来热插拔留升级路径（本期不做）。

### 4.3 编排引擎层（LiteFlow 集成）

**转换器：画布 JSON → LiteFlow 规则**

- 节点映射：所有节点（业务/LLM/Agent/连接器）→ `component(nodeId)`；LiteFlow 组件实现为通用分发组件，按 nodeId 路由到注册表对应 `WorkflowNode`。
- 边映射：顺序边 → `THEN(...)`；条件边（边上配表达式）→ `SWITCH/IF`；并行分支 → `WHEN(...)`。
- 示例（4.1 节点拼出的标准流程编译结果）：

```
THEN(
  req.checkApproved,
  jira.createIssue,
  dev.createBranch,
  agent.genTestCases,
  test.createPlan,
  approval.hitl,
  ops.grayRelease
)
```

**规则存 DB + 热刷新**

- 规则内容随定义版本落库（`wf_definition_version.rule_content`），发布时推送给 LiteFlow 热加载，**改流程不重启**。
- 语义对齐：同 id 定义重新发布 = 替换；**新 run 用新规则，进行中 run 按原规则跑完**（P0 需实测验证 LiteFlow 版本机制满足此语义）。
- 本期转换器**只做单向**（画布 → 规则）；「导入已有规则反向生成画布」后置（YAGNI）。
- P1 限定支持的拓扑：顺序 / 条件分支 / 并行；**子流程节点（递归组合）P3 落地**。执行语义：`workflow` 形态节点执行 = 引擎启动子 run（`wf_run.parent_run_id` 指向父 run，形成 run 树，画布可下钻追踪）；子工作流按**版本钉住**引用（发布时锁定，不随子流程升级自动漂移）；递归深度上限（默认 5）+ 发布时环检测，防止无限嵌套。

**节点级挂起与恢复（决策门，v0.5 增补）**

- 任意节点（atomic / agent / workflow 三形态通用）可配置「执行前需人工决策」：引擎推进到该节点时**挂起**——持久化上下文快照（已完成节点输出 + 变量区），run 状态置为 `SUSPENDED`，画布与审批中心同步呈现待办。
- 决策（通过 / 驳回 / 调整参数）后**恢复**：从快照重建执行上下文，注入决策元数据，**从该节点继续执行**；全过程留 `wf_decision` 审计。
- 实现方案：**检查点分段执行**——挂起点把流程切分为段，每段是一次 LiteFlow 执行；恢复即启动下一段。（LiteFlow 无原生挂起/恢复，此为平台层状态机方案，P3 spike 验证。）
- 超时与升级策略：挂起可配超时（如 24h），到点自动升级（通知上级）/ 自动通过 / 自动驳回，策略可配。
- 与 Agent 工具审批的分工：**节点决策门**管「节点边界」（任意节点类型通用）；**AgentScope 权限系统**管「Agent 工具调用粒度」（见 4.4）。两者共用同一审批中心与恢复机制。

**run 状态机**：`RUNNING → SUSPENDED（等待决策）→ RUNNING → SUCCESS / FAILED / CANCELLED`；SUSPENDED 的 run 不占用执行线程，进程重启后可从快照恢复继续。

### 4.4 Agent 运行时层（AgentScope 集成）

**适配层（隔离 API 变动风险的关键）**

```java
/** 平台代码只依赖此接口，不直接 import io.agentscope */
public interface AgentRuntime {
    AgentHandle createAgent(AgentSpec spec);            // 模型/提示词/工具集/权限策略
    Flux<AgentEvent> stream(AgentHandle agent, AgentMessage msg, RunContext ctx);
    void registerTools(AgentHandle agent, List<ToolSpec> tools);
}
```

- `AgentRuntime` 默认实现 `AgentScopeRuntime` 包装 AgentScope 的 `ReActAgent`/`HarnessAgent`；未来若更换运行时，只新增一个实现。

**模型接入**

- 模型接入采用**协议双轨**（Q5 决议）：`OPENAI_PROTOCOL`（OpenAI 兼容端点：OpenAI 官方 / DeepSeek / 通义兼容模式 / vLLM 等自建推理）与 `ANTHROPIC_PROTOCOL`（Anthropic 官方及兼容端点）。`model_provider_config` 记录 协议类型 + base_url + key + model，执行时经适配层动态构建实例（AgentScope 扩展模块 model-openai / model-anthropic）。
- 换模型 = 改配置，不改代码不重启（借鉴 Mastra model router 思想）；**新增供应商 = 新增一条协议配置，无需开发**。

**事件流双消费（本方案的核心亮点）**

- AgentScope 的 31 种类型化事件（`streamEvents()`）经 SSE 网关分发：
  - `TEXT_BLOCK_DELTA` 等 → Chat 窗口流式渲染；
  - `TOOL_CALL_START/FINISH`、节点级事件 → **画布节点实时高亮**。
- 一个事件源喂两个前端场景，无需自研双通道。

**人工审批（HITL）**

- 两个粒度：**节点决策门**（任意节点执行前挂起等决策，平台层实现，见 4.3）与 **Agent 工具审批**（AgentScope 权限系统：工具调用 允许/用户审批/拒绝，运行时原生）。
- 两者共用同一审批中心（待办列表）与恢复机制；审批交互在画布与 Chat 入口均可完成。
- 与 Webhook Trigger 共同构成「等待外部事件再继续」的能力。

**会话与记忆**

- 分布式 session/memory 交由 AgentScope（Redis/PostgreSQL 后端），平台侧只管 run 记录与业务数据，不重复建设会话存储。

**Agent 配置化（Agent-as-Configuration）**

- Agent 不是代码里的类，而是 `agent_config` 表中的一条配置：模型、系统提示词、工具清单、权限策略、迭代上限全部可配置，管理台统一创建/编辑/版本化。
- 运行时由 `AgentRuntime.createAgent(spec)` 按配置动态构建实例——平台**不预置任何代码级 Agent**，「测试用例生成」与「发布助手」在机制上完全对等。
- AgentScope 的 HarnessAgent/ReActAgent 作为构建目标，其 workspace/记忆/技能能力按配置启用。
- **SOP 复合模式**：Agent 节点可绑定一个子工作流作为执行程序——确定性框架（步骤骨架）+ Agent 自主填充（每步的智能执行），即「每个节点 = workflow + agent」的直接实现。

**对等协作（不分主子）**

- **组合外置**：谁调用谁，画布上可见可改——工作流是唯一的组合层，消灭藏在代码里的主子层级。
- **对等互调**：Agent 需要协作时，通过「调用其他 Agent」工具（`agent.invoke:<agentId>`，本身是可配置的工具项）或 A2A 协议；被调用方对调用方无感知，双方地位对等，且同样受权限系统治理。
- **不暴露主子式生成**：AgentScope 的 `agent_spawn`/`agent_send`（父 Agent 生成子 Agent）**不作为平台能力开放**，避免隐式层级与不可见编排。

### 4.5 前端画布（LogicFlow）

| 能力 | 实现方式 |
|---|---|
| 节点面板 | 调 `GET /api/components`（注册表面板清单），按 category 分组展示；新增插件自动出现 |
| 节点外观 | LogicFlow 自定义节点：按 category 区分样式（业务/LLM/Agent/连接器），连接器节点带平台图标 |
| 参数表单 | 选中节点 → 按 `paramSchema` 动态渲染配置抽屉（文本/下拉/键值对/凭据引用） |
| 连线校验 | 拖线时校验 `outputSchema → inputSchema` 兼容性；不兼容时提示配置 mapping |
| 状态回显 | run 进行中，订阅 SSE 节点级事件，实时高亮当前节点与已走路径 |
| 保存/发布 | 画布 JSON 提交后端 → 校验（nodeId 存在性 + schema 连贯性）→ 存版本 → 编译规则 → 热刷生效 |

### 4.6 执行入口与事件流

| 触发器 | 场景 | 说明 |
|---|---|---|
| **CHAT_INPUT** | **工作区用户输入内容**（Q9：只有输入才触发流程） | 用户在工作区输入文本/图片（Ctrl+V 粘贴）/md 附件 → 创建 run，输入内容 = 开始节点流程输入；运行中再输入经 `RunInputPort` 注入，执行节点经 `UserInputFeed` 协作式拉取采纳 |
| Manual | 画布/管理台点「运行」 | 调试与人工发起 |
| API | 外部系统调用 `POST /api/runs/{workflowId}` | 平台对外集成入口（BaaS 雏形） |
| **Webhook** | Jira/禅道/云效事件回调 | `POST /api/webhooks/{connector}/{event}` → 连接器解析 → 启动新 run 或恢复挂起 run |
| Schedule | 周期性执行 | Cron 表达式 + 时区；错峰策略（错过触发点：补火一次 / 全部补火 / 跳过）；**并发策略**（上一轮 run 未结束：跳过 / 并行 / 排队）；生效窗口（起止日期）；绑定随定义版本化。典型场景：每日站会摘要、每周需求盘点 |

- SSE 网关统一分发：run 级事件（状态变迁）+ 节点级事件（高亮）+ Agent 事件（流式文本）。
- 所有 run 落 `wf_run` / `wf_node_run`，支持事后回放（画布上重放执行路径）。

**工作区（Workspace）**：执行的交互容器与授权边界——`工作区 = 项目名 + 绑定（agent 或 workflow 二选一）+ Agent 决策授权`：

- **路径动态生成**：平台在系统设置中配置**工作区根目录**（如 `D:\xuanji\workspaces`），创建工作区只需填**项目名字**，工作区路径 = `根目录/项目名` 自动生成（用户不手填路径）；
- **绑定二选一（Q11）**：仅能绑定 **agent**（工作区即与该 Agent 的对话执行容器）或 **workflow**（阶段化流程执行容器），绑定已发布版本（版本钉住）；
- **输入触发与随时接入（Q9）**：只有用户输入内容才触发流程（CHAT_INPUT）；运行中用户随时接入——追加指令、上传附件（图片 Ctrl+V 粘贴直传 / md 文档），经 `RunInputPort` 注入 run，当前执行节点经 `UserInputFeed` 采纳，SUSPENDED 时进入决策工作区上下文；
- **阶段化 SSE 对话交互**：run 在工作区内执行，每个流程阶段以标准 SSE 事件流（`stage.start / agent.output / tool.call / decision.request / stage.finish`）推送到**对话式页面**，用户在对话流中交互（追加指令、批注、决策）；
- **Agent 决策授权前置**：绑定工作区时一次性配置各 Agent 的授权策略（工具自动放行规则 / 需人工决策项 / 决策门交互形态）；运行时决策请求按此策略路由——命中自动放行规则的直接执行，需人工的以决策卡片出现在对话流中（产物审阅类可进入决策工作区完整批注/修改，见 4.3）。

### 4.7 数据模型（核心表）

```sql
-- 工作流定义（当前版本指针）
wf_definition(id, name, description, current_version, status, created_by, created_at, updated_at)

-- 定义版本（画布 JSON + 编译后规则，全量留痕可回滚）
wf_definition_version(id, definition_id, version, canvas_json, rule_content, published_at, published_by)

-- 执行记录（parent/root 构成子流程 run 树；app_id 追踪第三方应用调用来源；
--          status: RUNNING / SUSPENDED(等待决策) / SUCCESS / FAILED / CANCELLED）
wf_run(id, definition_id, version, trigger_type, trigger_payload, app_id,
       parent_run_id, root_run_id,
       status, context_snapshot, input_json, output_json, error, started_at, finished_at)

-- 节点执行记录（画布回显与回放的数据源）
wf_node_run(id, run_id, node_id, node_name, seq, status,
            input_json, output_json, error, started_at, finished_at)

-- 决策记录（节点决策门 / Agent 工具审批的审计）
wf_decision(id, run_id, node_id, decision_type, decision,
            decided_by, comment, decided_at)

-- 组件注册表（SPI 实现启动时自动登记；node_type: atomic/agent/workflow）
component_registry(node_id, node_type, plugin_module, metadata_json, enabled)

-- 连接器配置（凭据密文落库）
connector_config(id, platform, name, endpoint, credential_cipher, extra_json, status)

-- 模型供应商配置
model_provider_config(id, provider, model, credential_cipher, params_json, enabled)

-- Agent 配置（Agent 全员可配置的载体，管理台维护，版本化）
agent_config(id, name, description, model_provider_id, system_prompt,
             tools_json, permission_json, params_json,
             version, status, updated_by, updated_at)

-- 工具注册（可发布资产：Java Bean 反射 / OpenAPI 导入 / 脚本）
tool_registry(id, name, description, source_type, source_ref,
              input_schema, output_schema, version, status, updated_by, updated_at)

-- 技能仓库（可发布资产：Markdown 指令 + 资源，兼容 AgentScope 技能格式）
skill_repo(id, name, description, content_uri, version, status, updated_by, updated_at)

-- MCP 服务配置（可发布资产：连接配置 + 发现的工具清单）
mcp_config(id, name, endpoint, auth_cipher, discovered_tools_json,
           version, status, updated_by, updated_at)

-- 应用（面向第三方的发布单元，绑定已发布资产的具体版本）
app(id, name, bound_asset_type, bound_asset_id, bound_asset_version,
    callback_url, status, created_by, created_at)

-- 应用凭据（AppKey：哈希落库，明文仅创建时展示一次；scope/限流/白名单）
app_credential(id, app_id, key_hash, scopes_json, rate_limit_json,
               ip_allowlist_json, expires_at, status, created_at)

-- 工作区（执行的交互容器与授权边界；绑定 agent 或 workflow 二选一，版本钉住）
workspace(id, name, root_path, binding_type,       -- AGENT / WORKFLOW
          bound_asset_id, bound_asset_version, authorization_json, created_by, created_at)

-- 报表定义（定时任务实体：绑定含页面生成节点的 workflow + 定时计划 + 数据源）
report_def(id, name, workflow_ref, schedule_json, datasource_ref, created_by, created_at)

-- 报表执行记录（每次定时执行一条；看板快照固化生成时点数据）
report_run(id, report_def_id, run_id, snapshot_json, status, generated_at)

-- 管理台权限体系（Q13，P1 落地 DDL 细化见 05 文档）
sys_user(id, username, password_hash, org_id, status, must_change_password, created_by, created_at)
sys_org(id, name, parent_id, org_path, sort)                      -- 组织树
sys_role(id, name, data_scope,                                    -- ALL/UNDER_ORG/ORG/ONLY_CREATE
         builtin, description)
sys_menu(id, name, parent_id, route, perm_code, sort, type)       -- 目录/菜单/按钮
sys_user_role(user_id, role_id)
sys_role_menu(role_id, menu_id)

-- 说明：wf_definition / agent_config / tool_registry / skill_repo / mcp_config
-- 均携带 version + status，统一遵循 4.8 的资产生命周期与发布语义。
-- 说明：sys_* 六表 + workspace + report_def/report_run 的完整 DDL 见 05 文档（P1 扩展）。
```

凭据安全：AES-GCM 加密落库，加密密钥走环境变量/KMS，**不进代码库、不进日志**。

### 4.8 统一资产模型与发布中心（v0.3 增补）

**一切皆资产**：workflow、agent、skill、mcp、tool 五类资产遵循统一生命周期与发布语义，管理台提供统一的**发布中心**。

**生命周期**：`DRAFT（草稿）→ PUBLISHED（发布，版本固化不可变）→ DEPRECATED（弃用，存量引用不受影响）→ OFFLINE（下线，禁止新引用）`

**各类型发布语义**：

| 资产 | 发布前校验 | 发布后生效 |
|---|---|---|
| workflow | nodeId 存在性、schema 连贯性、环检测、引用资产均为已发布版本 | 编译 LiteFlow 规则 + 热刷生效；自动获得执行 API（API 触发器可用）；可被子流程引用 |
| agent | 模型配置可用；引用的工具/MCP/技能存在且已发布；权限策略合法 | 进入 Agent 注册表；可上画布、可被 `agent.invoke` 引用 |
| tool | 输入输出 schema 完整；连通性测试通过 | 进入工具注册表；可被 Agent 绑定、可作为 atomic 节点 |
| skill | 内容格式校验（兼容 AgentScope 技能格式） | 入技能仓库；可被 Agent 启用 |
| mcp | 连通性验证；工具清单发现 | 注册 MCP 服务；其发现的工具可被 Agent 绑定 |

**引用钉住**：任何资产对其他资产的引用都钉住到**具体已发布版本**（子流程引用 `workflow@v3`、Agent 绑定 `tool@v2`）；上游发布新版本不影响存量引用，升级引用是显式动作。与 4.3 子流程版本钉住同一语义，扩展到全部资产类型。

**导入 / 导出（跨环境分发）**：资产可导出为 bundle（含依赖闭包，按依赖序自动排列），导入时整体校验、原子注册（任一校验失败则全部不注册）——设计参考 Mastra `addDynamicWorkflows` 的 bundle 语义。支撑 dev → test → prod 的资产晋升路径。

**发布中心（管理台）**：五类资产统一视图——检索、版本历史、状态流转、**依赖关系图（谁引用了我）**、发布/弃用/下线操作、导出/导入。

### 4.9 应用层：统一 API 入口与 AppKey（v0.4 增补）

**两层发布的边界（与 4.8 的关系，勿混淆）**：平台只有**一次真正的发布**——4.8 发布中心的**资产发布**（草稿 → 不可变已发布版本），它是对内+对外全部消费的唯一事实源。已发布版本有三条消费路径：① **工作流引用**（对内·组合：agent/skill/mcp/tool 被画布节点引用）；② **工作区绑定**（对内·执行：内部用户使用，身份 = 平台账号 + 决策授权，**无需 AppKey**）；③ **App 绑定**（对外·消费：本节，身份 = AppKey）。**App 发布的不是资产本身，而是「对外消费合同」**——绑定哪个已发布版本 + 谁能调（AppKey/scope）+ 怎么调（统一 API/嵌入）+ 调用治理（限流/白名单）。工作区与 App 是两条独立路径：内部协作走工作区，第三方集成走 App。

**应用（App）= 面向第三方的发布单元**：已发布的 workflow / agent 可「发布为应用」——App 绑定该资产的**具体已发布版本**（引用钉住），对外提供稳定调用契约；资产升级绑定版本是 App 上的显式动作，AppKey 不变。一个资产可发布为多个 App（不同消费方、不同配额）。

**统一 API 入口**（所有应用同一套端点模式）：

| 端点 | 用途 | 模式 |
|---|---|---|
| `POST /api/v1/apps/{appId}/runs` | 触发 workflow / agent | 同步（短流程）或异步（返回 runId） |
| `POST /api/v1/apps/{appId}/chat-messages` | 对话式调用（Agent） | SSE 流式 |
| `GET /api/v1/apps/{appId}/runs/{runId}` | 查询执行状态与结果 | 轮询 |
| `POST /api/v1/apps/{appId}/runs/{runId}/resume` | 恢复挂起的 run（HITL 对外） | 授权方审批 |

- 鉴权：`Authorization: Bearer <appkey>`，网关统一校验 `appkey → app → 绑定资产 → scope`。
- 异步回调：App 可配置完成回调 URL（webhook），run 终态主动通知第三方。

**AppKey 生命周期与安全**：

- 生成：创建 App 时签发（`app-` 前缀随机串）；**明文仅创建时展示一次，库中只存哈希**。
- 属性：scope（可调用端点集）、限流（QPS / 日配额）、IP 白名单（可选）、有效期。
- 操作：启用 / 禁用 / **轮换**（新旧 key 并存过渡期）/ 注销，全部操作留审计。
- 每次调用记录 `appkey → app → run` 链路，支撑用量统计与配额。

**页面嵌入**：

- **Chat Widget**：`<script src="{host}/embed/v1/chat.js" data-app-id="..." data-app-key="...">` → 浮窗 / 内嵌聊天窗，SSE 流式，支持 HITL 审批交互。
- **iframe**：`{host}/embed/{appId}?key=...` 整页嵌入。
- 安全：嵌入域名白名单（防 key 盗用）；嵌入与 API 同源同治理，scope 区分端点权限。

**HITL 对外语义**：第三方触发的 run 若挂起审批，默认在平台管理台完成审批；如需第三方自行审批，授予 `resume` scope 走 resume 端点（动作留审计）。

### 4.10 管理台权限体系：用户 / 菜单 / 数据权限（v0.9 增补，Q13 决议）

**模型**：RBAC + 组织树 + 数据范围，三件事各管一层——

| 层 | 模型 | 语义 |
|---|---|---|
| 功能权限 | 用户 → 角色 → 菜单 | **每个人的菜单和权限不一样**：菜单（页面路由 + 按钮动作）按角色分配，角色可叠加 |
| 组织归属 | 用户 → 组织（树形） | 组织树是数据权限的裁剪基准；用户归属唯一组织 |
| 数据权限 | 角色 → 数据范围 | 四档：`ALL`（全部）/ `UNDER_ORG`（本组织及下级）/ `ORG`（仅本组织）/ `ONLY_CREATE`（仅本人创建） |

**关键规则**：

- 菜单可见性与数据范围**正交**：同一角色可分别配「看哪些菜单」与「看哪些数据」；不同角色的菜单集与数据范围都不同。
- **平台管理员不受组织与数据权限控制**（bypass）：全量可见可管，不经组织树裁剪；其操作全量留审计。
- 数据范围作用面 = 一切按归属查询的数据：资产列表（工作流/Agent/连接器/发布中心）、run 列表、报表、工作区、审批中心。过滤在查询层统一实现（归属字段 `org_id` + `created_by`），不散落各业务查询。
- 数据归属判定：资产/run/报表等记录创建时落 `org_id`（创建人所属组织）与 `created_by`；组织树变更不回写历史归属。
- 数据模型（05 文档 DDL 扩展，P1 落地）：`sys_user` / `sys_org`（树形，parent_id + path）/ `sys_role`（含 `data_scope`）/ `sys_menu`（树形，路由+动作权限点）/ `sys_user_role` / `sys_role_menu`；管理台所有列表查询经统一数据范围过滤器。
- 与工作区的关系：工作区成员授权（Agent 决策授权）是**执行域**授权，本节是**管理域**授权——两套独立，工作区绑定时的决策授权不因管理台角色变化而失效。

---

## 5. 连接器设计（Jira / 禅道 / 云效）

### 5.1 连接器通用结构

每个连接器 = 一个 Maven 模块（`connector-xxx`），内含：

```
connector-xxx/
├─ XxxConnector          implements Connector   // 入口：登记节点/触发器/凭据 schema
├─ nodes/                // 操作节点：创建/变更/查询...
├─ trigger/              // webhook 事件解析器
├─ client/               // 平台 API 客户端（鉴权、限流、重试）
└─ resources/META-INF/spring/...AutoConfiguration.imports  // 自动装配
```

统一约定：节点语义对画布统一（如「变更状态」），平台差异封装在 client 层；API 细节以各平台官方文档为准，**P4 第一周做连通性 spike 专项验证**。

**接入路径双轨（Q6 决议）**：① **专用连接器**——深度适配（原生 API + webhook 验签 + 字段映射）；② **通用 MCP 接入**——管理台自定义添加 MCP server（`mcp_config`），工具自动发现并注册为画布节点，**无需开发**。Jira/禅道/云效均可经任一路径接入：有现成 MCP server 的直接添加，深度需求走专用连接器。

**数据源连接器（查询形态）**：连接器除「操作类」节点（写外部系统）外，还包含**数据源类**节点——连接外部业务数据库（JDBC）或业务 API，执行查询并输出结构化结果集（JSON），作为节点输出供下游 mapping 与 Agent 消费。**工作报表场景（1.5）的数据来源即此类连接器**——报表数据是真实业务数据（ERP/WMS/财务/客服等外部系统），不是平台自身运行数据；报表产物固化**生成时点的数据快照**，支持历史回看与审计。

### 5.2 Jira 连接器（需求/项目管理）

| 项 | 内容 |
|---|---|
| 鉴权 | API Token（Basic）或 OAuth2（以目标 Jira 版本为准） |
| 节点 | 创建 Issue / 变更 Issue 状态 / 添加评论 / JQL 查询 |
| Webhook | issue created / updated → 解析为平台事件 |
| 备注 | REST API v3；webhook 需在 Jira 管理端配置回调地址 |

### 5.3 禅道连接器（需求/测试管理）

| 项 | 内容 |
|---|---|
| 鉴权 | Token（禅道 API；开源版/企业版能力有差异） |
| 节点 | 创建需求/Bug/测试用例、变更状态、查询 |
| Webhook | 禅道钩子机制（**未验证**：目标版本的 API 范围与 webhook 支持度，P4 spike 确认） |

### 5.4 云效连接器（开发/运维管理）

| 项 | 内容 |
|---|---|
| 鉴权 | 阿里云 AccessKey / 个人访问令牌（以云效 OpenAPI 文档为准） |
| 节点 | 创建/变更工作项、触发流水线、查询流水线状态 |
| Webhook | 流水线状态变更、项目协作事件（**未验证**：事件类型覆盖度，P4 spike 确认） |

### 5.5 典型工作流（三平台串联示例）

```
[Webhook] Jira: 需求评审通过
   → [节点] 云效: 创建开发任务 + 建分支
   → [节点] 云效: 触发 CI 流水线
   → [Agent] 生成测试用例初稿（AgentScope，产出可人工修订）
   → [节点] 禅道: 创建测试计划与用例
   → [审批] 测试负责人确认（AgentScope 权限系统，HITL 挂起）
   → [节点] 云效: 灰度发布
   → [节点] Jira: 变更 Issue 状态为 Released
   → [通知] 群机器人
```

五个管理域的能力经由三个平台连接器 + Agent 节点在一条流程中闭环——这就是平台的核心价值演示场景。

---

## 6. 分期路线图

| 期 | 交付物 | 验收标准 |
|---|---|---|
| **P0** 核心骨架 | Spring Boot 多模块工程 + 三层 SPI 接口定义 + LiteFlow 标准流程（校验→Agent 总结→通知）跑通，AgentScope 经适配层接入 | 单元测试断言执行顺序；控制台可见 AgentScope 事件流；**实测热刷后新/旧 run 规则隔离语义** |
| **P1** 编排闭环 | LogicFlow 画布 + 注册表面板 → 画布 JSON → 规则编译 → 存版本 → 热刷 → 执行 + 状态回显 | 画布拼出 P0 同款流程并跑通，结果一致；改流程不重启生效 |
| **P2** 模型管理 | 模型供应商配置 UI → 动态构建模型实例；LLM 节点可选模型与 Prompt 模板变量 | 画布上切换模型后流程行为随之变化，无需重启 |
| **P3** Agent 能力 | **Agent 配置化管理台（agent_config，全员可配置）**、Agent 节点（tool-calling）、**子工作流节点（递归组合 + run 树）**、**工具注册与发布**（Java Bean / OpenAPI 导入 → tool_registry）、Chat 执行入口（SSE）、**节点决策门（挂起/恢复/超时升级）**、**周期触发（Schedule：cron/错峰/并发策略）** | 管理台创建并**发布**的 Agent/工具可直接上画布；Chat 流式输出与画布高亮同步；**节点挂起→决策→恢复闭环（含进程重启后从快照恢复）**；周期触发按并发策略正确执行；子流程 run 树可下钻追踪 |
| **P4** 连接器框架 | Connector SPI + Webhook Trigger + 凭据管理 + **1 个试点连接器（建议云效）** + **通用 MCP 接入（自定义添加，工具自动发现）** + 连通性 spike（专用连接器 + MCP 双轨） | 外部平台事件经 Webhook 触发流程跑通；凭据加密存取；MCP 工具自动发现并可上画布、可被 Agent 绑定 |
| **P5** 全量连接器 + 平台化 | Jira/禅道连接器补齐；**skill 发布（技能仓库）**；**发布中心统一视图（五类资产 + 依赖关系图）**；**资产导入导出（bundle，跨环境晋升）**；**应用层：发布为应用 + 统一 API 入口 + AppKey 管理 + 页面嵌入组件（Chat Widget / iframe）**；执行日志与观测（评估复用 AgentScope Service）；多租户评估 | **1.4 旗舰场景（SDLC 全流程编排）端到端演示跑通**（含 5.5 三平台串联）；资产可导出导入并在新环境原子注册；第三方经 AppKey 调用应用跑通；嵌入组件可上第三方页面 |

依赖关系：P0→P1→P2→P3 串行；P4 依赖 P1（画布）与 P3（审批），可与 P2/P3 部分并行。

## 7. 风险与缓解

| # | 风险 | 等级 | 缓解措施 |
|---|---|---|---|
| 1 | AgentScope Java GA 仅 3 个月，API 可能破坏性变更 | 高 | 锁定 v2.0.3；`AgentRuntime` 适配层隔离，平台代码不直接 import 其 API；升级只改适配层 |
| 2 | 禅道/云效的 API 范围与 webhook 能力未验证 | 中 | P4 第一周连通性 spike；连接器 client 层隔离平台差异，节点语义保持统一 |
| 3 | 画布→规则转换器对复杂拓扑的支持边界 | 中 | P1 限定顺序/分支/并行三种拓扑；嵌套子流程后置；转换器单测覆盖每种拓扑 |
| 4 | 热刷与进行中 run 的规则隔离不满足预期 | 中 | P0 实测；不满足则退化为「发布排期 + run 排水」策略 |
| 5 | 凭据泄露 | 高 | AES-GCM 密文落库；密钥走环境变量/KMS；日志脱敏；凭据操作审计 |
| 6 | AgentScope 社区规模较小（5.9k⭐） | 低 | 阿里背书、迭代快、中文社区；适配层同时降低更换成本 |
| 7 | 递归组合（子流程嵌套）导致 run 树过深或环引用 | 低 | 深度上限（默认 5）+ 发布时环检测；子流程版本钉住；run 树追踪便于定位 |
| 8 | LiteFlow 无原生挂起/恢复，节点决策门依赖平台层「检查点分段」方案 | 中 | P3 spike 验证上下文快照与分段续跑的正确性（含进程重启恢复）；若不满足，退化为仅对含决策门的流程启用分段模式 |

## 8. 待决问题决议记录（2026-10-08 全部决议）

| # | 问题 | 决议 | 落点 |
|---|---|---|---|
| 1 | JDK 版本 | **JDK 21** | 01 文档版本基线 |
| 2 | 数据库 | **PostgreSQL + Flyway**（schema 版本管理） | 05 文档 DDL（PG 版）、01 文档构建 |
| 3 | 前端栈 | **React 18 + Vite + AntD 5** | 06 文档 |
| 4 | 部署形态 | **单体模块化**（Maven 多模块，微服务边界预留） | 01 文档 |
| 5 | 模型接入 | **协议双轨：OpenAI 协议 + Anthropic 协议**（base_url 可配，覆盖官方/兼容端点/自建推理） | 04 文档 §3、05 文档 model_provider_config |
| 6 | 外部平台接入 | **专用连接器 + 通用 MCP 接入双轨**（管理台自定义添加 MCP server，工具自动发现成节点） | 5.1、4.8 |
| 7 | 资产发布审批 | **不需要**（状态流转保留，审批位不建） | 4.8 |
| 8 | AppKey 形态 | **纯 key**（Bearer + HTTPS + 限流；协议预留签名升级） | 4.9 |
| 9 | 工作区触发与接入 | **只有用户输入内容才触发流程**（CHAT_INPUT 触发，输入内容=开始节点流程输入）；**运行中用户随时接入**——输入经 RunInputPort 注入 run，当前执行节点经 UserInputFeed 协作式拉取采纳（与取消令牌同构），SUSPENDED 时进入决策工作区上下文 | core RunInputPort/UserInputFeed/TriggerType.CHAT_INPUT、03 文档触发 |
| 10 | 节点类型差异化 | **职能不可一概而论**：START/END 平台内置（开始节点声明流程级输入 schema、结束节点声明流程级输出 schema，插件不可提供）；ATOMIC（工具）/AGENT/WORKFLOW 职能分派。**每个节点都有输入与输出**，下游经 edge.mapping（JSONPath）引入上游输出作为输入，发布期+执行期双重 schema 校验 | core NodeKind/definition 包（WorkflowDefinition/NodeDef/EdgeDef/MappingRule）、03 文档画布 JSON v1 扩展 |
| 11 | 工作区绑定与输入附件 | **工作区仅能绑定 agent 或 workflow**（SKILL/MCP/TOOL 是 Agent 能力内容，不作绑定目标）；输入支持**图片（Ctrl+V 粘贴直传）与 md 文档附件**，先上传工作区目录再引用相对路径 | core WorkspaceBindingType/Attachment、00 原型绑定抽屉与输入区 |
| 12 | Agent 能力绑定 | **MCP 与 skill 同为 agent 可绑定能力**；**两级绑定**：agent 本身（agent_config 默认）+ 工作流 agent 节点（节点级），**优先级：工作流 agent 节点 > agent 本身**（同 id 冲突节点级覆盖，agent 级其余保留——追加+覆盖，非完全替换） | core AgentCapabilityBinding、NodeDef.capabilityBinding、04 文档 agent_config |
| 13 | 管理台权限体系 | **RBAC + 组织树 + 数据范围**：用户→角色→菜单（每人菜单/权限不一样）；数据权限四档 `ALL`/`UNDER_ORG`/`ORG`/`ONLY_CREATE`（菜单与数据范围随角色不同而不同，两者正交）；**平台管理员不受组织与数据权限控制**（bypass 全量，留审计）；过滤在查询层统一实现（org_id + created_by） | 4.10、core DataScope、05 文档 DDL 扩展（sys_user/sys_org/sys_role/sys_menu 及关联表，P1） |

## 9. 附录

### A. Mastra 借鉴点映射

| Mastra 设计模式 | 本方案落点 |
|---|---|
| 工作流定义 = JSON 数据（Dynamic Workflows） | `wf_definition_version.canvas_json`，画布为产出方 |
| 节点引用已注册组件（toolId/agentId） | 组件注册表 + nodeId 引用，画布不承载代码 |
| 提交时校验 + 同 id 替换 | 定义发布校验 + 版本替换，进行中 run 不受影响 |
| 定义持久化与版本化 | `wf_definition_version` 全量留痕 |
| mapping entry 数据流转 | 连线 mapping 配置（schema 不兼容时显式映射） |
| model router（provider/model 字符串） | `model_provider_config` + 运行时动态构建 |
| Harness（多模式 Agent 运行时） | AgentScope HarnessAgent（经适配层） |

### B. 选型验证数据（2026-10-08 实时抓取）

- LiteFlow：dromara/liteflow，3.9k⭐，2721 commits，规则存 DB（liteflow-rule-db）+ 热部署，v2.16.0+ AI Agent 一等公民
- AgentScope Java：agentscope-ai/agentscope-java，5.9k⭐，v2.0.3，JDK17+，v2.0.0 GA 2026-07，AgentScope Service 2026-08
- LogicFlow：didi/LogicFlow，11.7k⭐，业务自定义流程图编辑框架，数据与后端引擎结构互转
- Spring AI（9.5k⭐）/ LangChain4j（13.2k⭐）：已验证但**不引入**（避免双模型抽象层），留档备查

### C. 参考资料

- Mastra Dynamic Workflows: https://mastra.ai/docs/workflows/dynamic-workflows
- AgentScope Java: https://github.com/agentscope-ai/agentscope-java （文档 java.agentscope.io）
- LiteFlow: https://github.com/dromara/liteflow
- LogicFlow: https://github.com/didi/LogicFlow （官网 logicflow.cn）

---

*本文档由调研讨论沉淀而成；「未验证」标注项在对应阶段以 spike 方式确认后回填本文。*
