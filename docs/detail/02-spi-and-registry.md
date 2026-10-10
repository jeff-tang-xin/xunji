# 02 · SPI 与组件注册表规范

> 隶属《DevOps 全链路编排平台设计方案》v0.6 · 详细设计 02/06
> 本文是插件方与平台核心之间的全部契约，接口一经发布按兼容规则演进。

---

## 1. 接口总览

```
xuanji-core (io.xuanji.core.api)
├─ WorkflowNode        节点 SPI（atomic/agent/workflow 三形态统一）
├─ NodeMetadata        节点元数据（驱动画布面板与动态表单）
├─ NodeContext         执行上下文（输入/配置/变量区/决策注入/取消令牌）
├─ NodeOutput          执行结果
├─ WebhookParser       Webhook 事件解析 SPI（连接器实现）
├─ Connector           连接器 SPI（节点 + 解析器 + 凭据）
└─ AssetPublisher      资产发布 SPI（五类资产各一实现，core 内置）
```

## 2. WorkflowNode 完整定义

```java
public interface WorkflowNode {
    NodeMetadata metadata();
    /** 同步执行；长任务自行内部异步但必须遵守 cancellation */
    NodeOutput execute(NodeContext ctx) throws NodeExecutionException;
}

public enum NodeKind { ATOMIC, AGENT, WORKFLOW }

public record NodeMetadata(
        String id,                       // 全局唯一，{plugin}.{name}，如 jira.create-issue
        String name,                     // 画布显示名
        NodeKind kind,
        String category,                 // 面板分组：basic / llm / agent / connector.{platform}
        String icon,                     // 图标标识（前端图标库 key）
        String description,
        JsonSchema inputSchema,          // 上游输出 → 本节点输入的兼容性校验依据
        JsonSchema outputSchema,
        JsonSchema paramSchema,          // 配置表单 schema（前端动态渲染）
        Set<NodeCapability> capabilities // 如 SUPPORTS_DECISION_GATE、SUPPORTS_RETRY
) {}

public enum NodeCapability { SUPPORTS_DECISION_GATE, SUPPORTS_RETRY, SUPPORTS_TIMEOUT, IDEMPOTENT }
```

## 3. NodeContext / NodeOutput / 错误契约

```java
public record NodeContext(
        String runId,
        String nodeRunId,                // wf_node_run.id，日志与事件回传用
        Map<String, Object> input,       // 按 edge.mapping 装配后的输入（已通过 inputSchema 校验）
        Map<String, Object> params,      // 节点配置（paramSchema 实例，发布时已校验）
        WorkflowVars vars,               // run 级变量区（可读写，快照持久化的一部分）
        DecisionContext decision,        // 决策门恢复时注入：{decision, decidedBy, comment, adjustedParams, artifactUpdates, annotations}
        TraceContext trace,              // traceId/spanId，日志与链路
        CancellationToken cancellation   // run 取消/超时时置位，长任务必须轮询
) {}

public record NodeOutput(Map<String, Object> output, Map<String, Object> metrics) {}

public class NodeExecutionException extends RuntimeException {
    private final boolean retryable;     // true=引擎按 retry 策略重试；false=直接失败
    private final String errorCode;      // 分类错误码，如 CONN_TIMEOUT / AUTH_FAILED / SCHEMA_VIOLATION
}
```

**错误处理契约**

| 场景 | 行为 |
|---|---|
| 抛 `NodeExecutionException(retryable=true)` | 引擎按节点 retry 配置重试（默认 0 次），耗尽后 run FAILED |
| 抛 `NodeExecutionException(retryable=false)` | 直接失败，写 wf_node_run.error |
| 抛其他 RuntimeException | 视为不可重试失败（插件不应让受检异常逃逸） |
| 取消信号 | 节点应尽快返回 `NodeExecutionException(errorCode=CANCELLED)` |

## 4. 触发侧 SPI

MANUAL / API / SCHEDULE 三类触发由平台内置实现，连接器只实现 Webhook 解析：

```java
public interface WebhookParser {
    String connectorId();
    /** 解析外部请求 → 平台标准事件；返回 null 表示与本连接器无关 */
    ParsedEvent parse(WebhookRequest request);
}

public record ParsedEvent(
        String eventType,                // 如 issue.updated
        Map<String, Object> payload,     // 标准化载荷
        Map<String, Object> matchKeys    // 供定义触发绑定做匹配（如 project=XX, status=Approved）
) {}
```

**触发绑定**（随工作流定义版本化，存于画布 JSON 的 `triggers` 数组，见 03 文档）：

- `WEBHOOK` 绑定：`{connectorId, eventType, match}` —— 事件到达后按 match 过滤，命中则启动/恢复 run。
- `SCHEDULE` 绑定：`{cron, timezone, misfire, concurrency, window}`。
- `API` 绑定：可选输入 schema 校验；经应用层调用时鉴权走 AppKey（见主文档 4.9）。

## 5. Connector SPI

```java
public interface Connector {
    ConnectorMetadata metadata();            // id/name/icon/凭据字段 schema（前端动态渲染凭据表单）
    List<WorkflowNode> nodes();              // 平台操作 → 注册为画布节点
    List<WebhookParser> webhookParsers();    // 平台事件 → 注册为触发解析器
    void validateCredential(Credential c) throws ConnectorException;  // 保存前连通性校验
}
```

## 6. 组件注册表（ComponentRegistry）行为

| 行为 | 规格 |
|---|---|
| 扫描登记 | 启动时扫 Spring 容器中全部 `WorkflowNode`/`WebhookParser`/`Connector` bean，写 `component_registry`（upsert，含 plugin_module 来源） |
| 面板查询 | `GET /api/components` → 按 category 分组，仅返回 enabled；AGENT 形态节点由 `agent_config` 已发布记录动态生成 |
| 执行查找 | 按 nodeId O(1) 定位；未命中 → 定义发布时即失败（前置校验），运行期不发生 |
| 发布校验 | nodeId 存在且 enabled；节点 capabilities 覆盖定义中的配置（如配了决策门但节点不支持 → 校验失败） |
| 热插拔预留 | `register/unregister` 运行时接口本期不开放，接口先留 |

## 7. 插件开发最小示例（连接器节点）

```java
@Component
public class JiraConnector implements Connector {
    public ConnectorMetadata metadata() { /* id="jira", 凭据: siteUrl+email+apiToken */ }
    public List<WorkflowNode> nodes() { return List.of(new CreateIssueNode(), new TransitionIssueNode()); }
    public List<WebhookParser> webhookParsers() { return List.of(new JiraWebhookParser()); }
    public void validateCredential(Credential c) { /* 调 /rest/api/3/myself 验证 */ }
}

class CreateIssueNode implements WorkflowNode {
    public NodeMetadata metadata() {
        return new NodeMetadata("jira.create-issue", "创建 Issue", NodeKind.ATOMIC,
            "connector.jira", "jira", "在指定项目中创建 Issue",
            inputSchema, outputSchema,
            paramSchema /* projectKey, issueType, summary, description 字段定义 */,
            Set.of(SUPPORTS_RETRY, SUPPORTS_TIMEOUT, IDEMPOTENT));
    }
    public NodeOutput execute(NodeContext ctx) { /* 用 ctx.params + ctx.input 调 Jira API */ }
}
```

> 插件方只需依赖 `xuanji-core`，加一个 Maven 依赖 + `@Component` 即完成接入——这就是「编译期插拔」的全部成本。
