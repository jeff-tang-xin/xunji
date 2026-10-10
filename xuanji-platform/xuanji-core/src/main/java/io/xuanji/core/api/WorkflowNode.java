package io.xuanji.core.api;

/**
 * 工作流节点 SPI——平台与插件之间的核心契约。
 *
 * <p>一个节点 = 一个 Spring bean。插件方实现本接口并标注 {@code @Component}，
 * 平台启动时由组件注册表自动扫描登记，画布即可见、流程即可编排——
 * 核心代码零改动，这就是「一切可插拔」在节点维度的全部机制。</p>
 *
 * <p>三形态统一：ATOMIC（原子操作）、AGENT（委托 AgentRuntime）、WORKFLOW（子流程递归）
 * 都实现本接口，引擎以多态方式调度，不感知形态差异。</p>
 *
 * <p>实现纪律：</p>
 * <ul>
 *   <li>无状态——同一节点 bean 会被并发复用于多个 run，可变状态一律放 {@link NodeContext}</li>
 *   <li>长任务自行内部异步，但必须轮询 {@code ctx.cancellation()} 并尽快响应取消</li>
 *   <li>失败一律抛 {@link NodeExecutionException} 并给出 retryable 语义，不让受检异常逃逸</li>
 * </ul>
 */
public interface WorkflowNode {

    /** 节点静态元数据（每次调用返回等值对象；平台启动时缓存） */
    NodeMetadata metadata();

    /**
     * 同步执行。
     *
     * @param ctx 执行上下文（输入/配置/变量区/决策注入/取消令牌，由引擎装配）
     * @return 执行结果（输出 + 指标；输出将按 edge.mapping 流向下游）
     * @throws NodeExecutionException retryable=true 时引擎按 retry 策略重试，否则直接失败
     */
    NodeOutput execute(NodeContext ctx) throws NodeExecutionException;
}
