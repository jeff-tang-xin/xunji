package io.xuanji.core.api;

/**
 * 工具 SPI —— 平台「自定义能力」的可插拔入口（文档 detail/02 §2 工具维度）。
 *
 * <p><b>与 MCP / SKILL 的边界（决议）：</b></p>
 * <ul>
 *   <li><b>Tool = 我们自己定义的 SPI</b>：开发按本接口写一段 Java 实现（如 PDF 解析、
 *       文件提取、业务查询），标注 {@code @Component} 后由注册表自动扫描登记——
 *       无需改任何核心代码即可被画布与 Agent 使用。这是「内部协议」。</li>
 *   <li><b>MCP / SKILL = 外部标准协议</b>：接入的是别人按 Model Context Protocol /
 *       Anthropic Agent Skills 产出的东西，走协议适配器，不实现本接口。</li>
 * </ul>
 *
 * <p><b>一次实现、两处可用</b>：工具实现通过 {@code ToolNodeAdapter} 同时暴露为</p>
 * <ul>
 *   <li>画布 ATOMIC 节点（refId = {@code tool.{id}}，面板自动出现、可拖拽编排）；</li>
 *   <li>Agent 可调用函数（{@link ToolMetadata#name()} / {@link ToolMetadata#description()} /
 *       {@link ToolMetadata#parameters()} 即 LLM 可见的函数签名）。</li>
 * </ul>
 *
 * <p><b>实现纪律</b>（与 {@link WorkflowNode} 一致）：无状态——同一个 Tool bean 会被
 * 并发复用于多个 run；可变状态一律放 {@link ToolRequest}；失败抛
 * {@link NodeExecutionException} 给出 retryable 语义。</p>
 *
 * <p><b>入参约定</b>：{@link ToolRequest#input()} 为上游 edge.mapping 装配的数据，
 * {@link ToolRequest#params()} 为画布上配置的参数（符合 {@code parameters()} schema）。</p>
 */
public interface Tool {

    /** 工具静态元数据（每次调用返回等值对象；注册表启动时缓存） */
    ToolMetadata metadata();

    /**
     * 同步执行工具。
     *
     * @param request 执行请求（输入 + 节点参数）
     * @return 执行结果；其 output 将按 edge.mapping 流向下游
     * @throws NodeExecutionException retryable=true 时引擎按节点 retry 策略重试，否则直接失败
     */
    ToolResult invoke(ToolRequest request) throws NodeExecutionException;
}