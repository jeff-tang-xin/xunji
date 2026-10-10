package io.xuanji.core.runtime;

/**
 * Agent 运行时适配层接口（docs/detail/04 §1 定稿）——平台与 AgentScope 的唯一边界。
 *
 * <p><b>铁律</b>：{@code io.agentscope} 的 import 只允许出现在 xuanji-agent-scope 模块；
 * 平台其余代码（server/plugins）只依赖本接口。AgentScope API 变动被物理隔离在适配层内。</p>
 *
 * <p>接口演进纪律：方法不满足需求时<b>只增不改</b>（新方法以 default 形式追加），
 * 保证插件与 server 无感升级。P0 落地阻塞形态（createAgent/call/close）；
 * 流式 {@code stream}（Flux&lt;AgentEvent&gt;）与权限回调 {@code setPermissionHandler}
 * 随 chat SSE 与 HITL 集成增补。</p>
 */
public interface AgentRuntime {

    /** 按 agent_config 构建智能体实例（模型/提示词/工具/权限全部来自配置） */
    AgentHandle createAgent(AgentSpec spec);

    /** 阻塞执行：单轮调用到最终结果（内部为流式聚合；异常统一 RuntimeException 语义） */
    AgentResult call(AgentHandle agent, AgentMessage msg);

    /**
     * 流式执行：事件经 sink 逐个推送（agent.start/agent.delta/agent.result/agent.end/error），
     * 方法返回时流已结束。default 实现退化为 {@link #call} 单事件（不破坏既有实现）；
     * 实现方覆写以提供真流式（AgentScope streamEvents → 平台事件映射）。
     */
    default void stream(AgentHandle agent, AgentMessage msg, java.util.function.Consumer<AgentStreamEvent> sink) {
        AgentResult r = call(agent, msg);
        sink.accept(AgentStreamEvent.result(r.text()));
    }

    /** run 结束释放实例（无状态 Agent 池化为 P3 评估项） */
    void close(AgentHandle agent);
}
