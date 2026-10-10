package io.xuanji.core.api;

import java.util.Map;

/**
 * 节点执行上下文——引擎装配给节点的全部执行环境。
 *
 * <p>设计意图（封装）：节点实现只通过本 record 与引擎交互，
 * 看不到也无需看到 LiteFlow 编排、run 状态机、持久化等引擎内部。
 * 引擎演进（如更换编排实现）不波及任何插件——依赖倒置的落点。</p>
 *
 * @param runId        所属 run（wf_run.id）
 * @param nodeRunId    本次节点执行记录（wf_node_run.id），日志与事件回传用
 * @param input        按 edge.mapping 装配后的输入（已通过 inputSchema 校验）
 * @param params       节点配置（paramSchema 实例，定义发布时已校验）
 * @param vars         run 级变量区（可读写，是 SUSPENDED 快照持久化的一部分）
 * @param decision     决策门恢复时注入（首次执行为 empty）；含决策结论/批注/产物修订
 * @param trace        链路上下文（traceId/spanId）
 * @param cancellation 取消令牌（run 取消/超时时置位；长任务必须轮询）
 * @param userInputs   运行中用户输入订阅（工作区「随时接入」：用户追加的指令/附件，
 *                     长任务节点协作式拉取采纳；见 {@link UserInputFeed}）
 */
public record NodeContext(
        String runId,
        String nodeRunId,
        Map<String, Object> input,
        Map<String, Object> params,
        WorkflowVars vars,
        DecisionContext decision,
        TraceContext trace,
        CancellationToken cancellation,
        UserInputFeed userInputs
) {
    public NodeContext {
        input = input == null ? Map.of() : Map.copyOf(input);
        params = params == null ? Map.of() : Map.copyOf(params);
        userInputs = userInputs == null ? UserInputFeed.empty() : userInputs;
    }

    /** 首次执行（非决策门恢复）的便捷判断 */
    public boolean isResumedFromDecision() {
        return decision != null && decision.present();
    }
}
