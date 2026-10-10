package io.xuanji.core.api;

/**
 * 节点形态。画布上的一切节点最终都是 {@link WorkflowNode} 的多态实现——
 * 引擎按形态分派执行语义，这是「一切皆节点」原则在类型系统的落点。
 *
 * <p>形态即职能（不可一概而论）：</p>
 * <ul>
 *   <li>{@code START} —— 开始节点：<b>平台内置</b>，插件不可提供。声明流程级输入
 *       （inputSchema = run 启动输入契约；工作区场景即用户输入内容），无上游</li>
 *   <li>{@code END} —— 结束节点：<b>平台内置</b>，插件不可提供。声明流程级输出
 *       （outputSchema = run 最终产物契约），汇聚上游输出为 run 结果</li>
 *   <li>{@code ATOMIC} —— 工具节点：原子操作（插件/连接器提供，如 jira.create-issue）</li>
 *   <li>{@code AGENT} —— Agent 节点：由 agent_config 已发布记录动态生成，运行时委托 AgentRuntime</li>
 *   <li>{@code WORKFLOW} —— 子工作流节点：引用另一条已发布工作流定义，run 树递归</li>
 * </ul>
 *
 * <p>统一与差异的边界：所有形态共享同一执行契约（{@link WorkflowNode}、
 * input/output schema、统一错误契约），引擎以多态调度；执行语义差异
 * （START 输入透传、END 输出固化、AGENT 事件流）由引擎按 kind 分派，
 * 不散落在节点实现里。每个节点的输出经 edge.mapping 成为下游节点的输入。</p>
 */
public enum NodeKind {
    START, END, ATOMIC, AGENT, WORKFLOW
}
