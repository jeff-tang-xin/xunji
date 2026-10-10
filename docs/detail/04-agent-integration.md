# 04 · AgentScope 集成规范

> 隶属《DevOps 全链路编排平台设计方案》v0.6 · 详细设计 04/06
> 铁律：`io.agentscope` 只允许出现在 `xuanji-agent-scope` 模块；平台其余代码只依赖 `AgentRuntime` 接口。

---

## 1. AgentRuntime 适配层完整接口

```java
public interface AgentRuntime {
    /** 按 agent_config 构建智能体实例（模型/提示词/工具/权限全部来自配置） */
    AgentHandle createAgent(AgentSpec spec);

    /** 流式执行：返回 Agent 事件流（平台事件模型，非 AgentScope 原生类型） */
    Flux<AgentEvent> stream(AgentHandle agent, AgentMessage msg, RunContext ctx);

    /** 阻塞便捷方法（内部基于 stream 聚合），供非流式节点使用 */
    AgentResult call(AgentHandle agent, AgentMessage msg, RunContext ctx);

    /** 动态注册工具（tool_registry / MCP / agent.invoke 三来源） */
    void registerTools(AgentHandle agent, List<ToolSpec> tools);

    /** 注册权限回调：AgentScope 权限系统判定「需审批」时回调平台审批中心 */
    void setPermissionHandler(AgentHandle agent, PermissionHandler handler);

    void close(AgentHandle agent);   // run 结束释放（无状态 Agent 可池化，P3 评估）
}

public record AgentSpec(
        String agentConfigId,            // agent_config.id@version
        String name,
        String systemPrompt,
        ModelConfig model,               // providerConfigId + 参数
        List<ToolRef> tools,             // TOOL / MCP / AGENT 三类引用（含版本钉住）
        PermissionPolicy permission,     // default: ALLOW / requireApproval: 模式列表 / deny
        Map<String, Object> runtime      // maxIterations 等
) {}
```

- 默认实现 `AgentScopeRuntime`：内部用 `ReActAgent`（轻量）或 `HarnessAgent`（需 workspace/记忆/技能时，按 agent_config 开关选择）。
- 版本升级预案：AgentScope API 变动只改本模块；接口方法不满足时**只增不改**（默认方法），保证插件与 server 无感。

## 2. agent_config JSON 结构（存 agent_config 表 params_json 之外的约定列）

```json
{
  "model":    { "providerConfigId": 1, "params": { "temperature": 0.3, "maxTokens": 4096 } },
  "systemPrompt": "你是测试用例设计专家……",
  "tools": [
    { "type": "TOOL",  "ref": "tool:zentao.create-case@1" },
    { "type": "MCP",   "ref": "mcp:company-wiki@1" },
    { "type": "AGENT", "ref": "agent:code-reviewer@3" }
  ],
  "permission": { "default": "ALLOW", "requireApproval": ["ops.*", "connector.jira.delete*"], "deny": [] },
  "runtime": { "maxIterations": 10, "useHarness": false, "workspace": false }
}
```

- 引用一律**钉住已发布版本**（`@version`），与主文档 4.8 引用钉住一致；升级引用是显式动作。
- `type=AGENT` 的工具 = 平台内置 `agent.invoke` 能力：运行时经 AgentRuntime 递归调用另一 Agent（对等互调，受双方权限策略治理）——**不使用 AgentScope 的 agent_spawn/agent_send**（主文档 4.4 对等协作原则）。

## 3. 模型接入（协议双轨，Q5 决议）

- `model_provider_config`（DB）按**协议**而非厂商接入：
  - `OPENAI_PROTOCOL`：OpenAI 官方及一切兼容端点（DeepSeek、通义兼容模式、Moonshot、vLLM/Ollama 自建推理等）——base_url + api_key + model；
  - `ANTHROPIC_PROTOCOL`：Anthropic 官方及兼容端点——base_url + api_key + model。
- 适配层经 AgentScope 扩展模块 `model-openai` / `model-anthropic` 动态构建实例；**新增供应商 = 新增一条配置，无需开发**。
- API Key 存 `credential_cipher`（AES-GCM），构建实例时解密注入，**不落日志**。
- 连通性测试：`POST /api/model-providers/{id}/test` → 发一条最小 prompt 验证。

## 4. 工具 / MCP / 技能绑定

| 来源 | 注册方式 |
|---|---|
| tool_registry（Java Bean / OpenAPI 导入） | 反射/规格生成 `ToolSpec`（name/description/JSON Schema/execute 回调）→ `registerTools` |
| mcp_config | AgentScope MCP 扩展连接外部 server，发现的工具按 `mcp:{id}.{toolName}` 命名注册 |
| skill_repo | AgentScope 技能格式（Markdown 指令 + 资源），经 HarnessAgent 技能仓库加载（`useHarness=true` 时） |
| agent.invoke | 平台内置工具，运行时递归 `AgentRuntime.call`，深度计入子流程深度上限 |

## 5. 事件映射（SSE 透传）

已验证存在的事件类型（README）：`TEXT_BLOCK_DELTA`、`TOOL_CALL_START`、`AgentResultEvent`、`CustomEvent`、`HintBlockEvent` 等（共 31 种）。

| AgentScope 事件 | 平台 SSE 事件 | 消费方 |
|---|---|---|
| TEXT_BLOCK_DELTA | `chat.delta` | Chat 窗口流式渲染 |
| TOOL_CALL_START / FINISH | `node.tool.start` / `node.tool.finish` | 画布节点角标、运行监控 |
| 结果/终止类 | `node.finish`（含 result 摘要） | 画布、run 记录 |
| 权限审批类 | `node.approval.required` | 审批中心待办 + 画布挂起态 |

> ⚠️ 31 种事件的**完整映射表**在 P0 spike 时对照官方文档产出（04 附录），本文只锁定消费端契约（上表右两列），保证 AgentScope 侧变化不波及前端。

## 6. 权限与 HITL 集成

```
AgentScope 权限系统判定 requireApproval
→ 适配层 PermissionHandler 回调（含工具名/参数/agentConfigId/runId）
→ 平台写审批待办 + wf_run=SUSPENDED（若为节点级）或 Agent 内挂起（工具粒度）
→ 决策（通过/驳回/调参）→ 回调返回 → AgentScope 继续推理循环
```

- 工具粒度审批不切断节点：Agent 节点保持 RUNNING，仅该工具调用等待；节点级决策门才置 SUSPENDED（主文档 4.3/4.4 分工）。

## 7. 会话与记忆

- Chat 入口（内部 + 应用层）以 `sessionId` 维持多轮：`RuntimeContext.sessionId/userId` 透传。
- 分布式后端：Redis（session）+ PostgreSQL（memory 持久化），配置于 server 的 AgentScope 装配（01 文档 §6 多副本预留）。
- 分层记忆 / 技能仓库 / workspace 沙箱：按 agent_config 开关启用，P3 先关（YAGNI），P5 评估开启。
