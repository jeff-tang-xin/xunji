# 06 · 前端、测试与 P0 任务分解

> 隶属《DevOps 全链路编排平台设计方案》v0.6 · 详细设计 06/06

---

## 1. 前端页面清单与关键交互（React + Vite + AntD + LogicFlow）

| 页面 | 关键交互 |
|---|---|
| 工作台 | 定义列表、最近 run、待办审批数 |
| **工作区** | 左侧工作区列表（项目名 + 动态生成路径 + 绑定工作流 + run 状态）；**对话式执行页**：阶段化 SSE 事件流渲染为消息流（stage / agent.output / tool.call / decision.request）；决策请求按绑定授权策略路由（自动放行 / 对话内决策卡片 / 跳转决策工作区完整审阅）；底部对话输入（向当前阶段 Agent 追加指令）；「绑定与授权」抽屉（系统根目录 + 项目名 → 路径自动生成、Agent 授权表） |
| **画布编辑器** | 左侧节点面板（`GET /api/components` 分组渲染）；拖拽创建节点；连线时 input/output schema 兼容性校验（不兼容提示配置 mapping）；节点属性抽屉（按 `paramSchema` 动态渲染表单）；决策门/重试/超时配置；保存草稿 / 发布（展示校验结果与 warnings）；workflow 节点双击下钻子画布（面包屑返回） |
| 运行监控 | run 列表（状态/触发方/耗时筛选）；详情 = 画布只读态 + 节点状态实时高亮（SSE）+ 节点输入输出查看 + SUSPENDED 节点「去审批」入口 |
| 决策工作区 | 待办列表；**产物审阅模式**：渲染上游产物（UI 设计稿/文档/数据表）→ 用户实时批注（区域锚点）+ 在线修改（产物新版本）→ 决策（通过/驳回）只是下一步前提，修改后产物写回 run 上下文供下游节点消费；简单场景保留表单决策 |
| 发布中心 | 五类资产统一列表（类型/状态/版本）；版本历史 diff；依赖关系图（谁引用了我）；发布/弃用/下线；导出 bundle / 导入 |
| Agent 管理台 | agent_config 表单（模型选择/提示词编辑器/工具勾选/权限策略）；发布；版本对比 |
| 工具/技能/MCP | 各自 CRUD + 发布；MCP 连接测试与工具发现结果展示 |
| 模型供应商 | 供应商/模型/密钥配置；连通性测试 |
| 连接器 | 已装连接器列表；凭据表单（按 ConnectorMetadata 凭据 schema 动态渲染）；测试 |
| 应用管理 | App 列表；绑定资产版本选择；AppKey 创建（**明文一次性弹窗**）/轮换/禁用；限流与白名单配置；嵌入代码生成（复制 script/iframe 片段） |
| 执行日志 | 按 run/appkey/时间检索；调用链路（appkey→app→run→nodes） |
| 报表中心 | 报表列表（来源工作流/数据源/触发/最近生成）；看板详情（KPI/趋势图/Agent 分析摘要/明细表，**生成时点数据快照**）；导出/分发 |

**前端约定**：**TypeScript 严格模式**（用户确认）；画布 JSON、资产模型、API 响应定义 TS 类型（与 03/05 文档 schema 一一对应）；所有动态表单由 schema 驱动（统一 JSON Schema → AntD 表单渲染器，自研薄层）；SSE 断线自动重连；画布 JSON 与后端 definition format v1（03 文档）逐字段对应。

## 2. 嵌入组件设计

- `chat.js`：零依赖自研（避免引入 React 运行时到第三方页面）；Shadow DOM 隔离样式；配置项 `data-app-id / data-app-key / data-mode(floating|inline) / data-theme`。
- 通信：直连 `/api/v1/apps/{appId}/chat-messages`（SSE）；HITL 审批交互内嵌完成（resume scope）。
- 安全：父页域名须在 App 白名单（网关校验 Origin）；key 为嵌入专用 scope。

## 3. 测试策略与 spike 清单

**分层**

| 层 | 范围 | 工具 |
|---|---|---|
| 单元 | 画布→EL 编译器（每种拓扑/边界）、mapping 求值、注册表校验、凭据加解密 | JUnit5 + Mockito，编译器覆盖率 ≥80% |
| 集成 | 发布流水线全链路、触发→执行→SSE、决策门挂起恢复（含重启恢复）、调度 misfire/并发策略 | Testcontainers（PostgreSQL+Redis） |
| 适配层 | AgentScope 事件映射、工具注册、权限回调（mock AgentScope 侧） | 仅在 agent-scope 模块内 |
| E2E | 旗舰场景（1.4）：SDLC 七阶段最小版端到端 | P5 |

**Spike 清单（每项有明确产出物与判定标准）**

| # | Spike | 产出 | 判定 |
|---|---|---|---|
| S1 | LiteFlow 动态组件注册 + reloadRule 与旧 run 隔离 | 可运行 demo + 结论记录 | 新 run 用新规则、旧 run 按原规则完成 |
| S2 | 检查点分段：挂起→快照→恢复续跑（含进程重启） | 分段执行器原型 + 测试 | 恢复后从挂起节点正确继续，上下文无损 |
| S3 | AgentScope 31 事件全量映射表 + streamEvents 稳定性 | 04 文档附录映射表 v1 | Chat 流式 + 画布角标双消费正常 |
| S4 | 三平台连通性 + 通用 MCP 接入（Q6 双轨：专用连接器 API 鉴权 + webhook 验签 + 事件样例；MCP server 自定义添加 → 工具发现 → 节点注册） | 连接器 client 骨架 + 事件样例库 + MCP 接入 demo | 专用连接器至少一条真实事件触发成功；MCP server 工具发现并可上画布 |

## 4. P0 任务分解（拿起来就能干）

| # | 任务 | 完成标准 | 预估 |
|---|---|---|---|
| 1 | 工程骨架：根 POM + BOM + core/agent-scope/server/plugins 模块 + docker-compose.dev | `mvn verify` 绿；server 可启动 | 0.5d |
| 2 | SPI 接口落地：02 文档全部接口与 record 进 core | 编译通过 + javadoc 完整 | 0.5d |
| 3 | 注册表：扫描/登记/查询 + `GET /api/components` | node-basic 两节点出现在面板接口返回中 | 0.5d |
| 4 | 引擎最小闭环：画布 JSON（手写）→ 编译 EL → LiteFlow 执行 → wf_run/wf_node_run 落库 | 集成测试：三节点顺序流程跑通，落库断言通过 | 1d |
| 5 | AgentScope 适配层：AgentRuntime 实现 + 模型接入 + Agent 节点 | 标准流程中间节点为 Agent（OpenAI 协议或 Anthropic 协议端点，Q5），事件流输出到日志 | 1d |
| 6 | S1/S2 spike（与 4/5 并行） | 结论回填 03 文档与主文档风险表 | 1d |
| 7 | 验收演示：校验→Agent 总结→通知 全流程 + 单测 | 主文档 P0 验收标准全绿 | 0.5d |

> P0 总计约 5 人日；完成后 P1（画布闭环）可直接按 03/06 文档开工，无设计空白。
