package io.xuanji.core.api;

import java.util.Map;

/**
 * 工具执行请求 —— {@link Tool#invoke(ToolRequest)} 的入参载体。
 *
 * @param input  按 edge.mapping 装配后的输入（上游节点输出流入，已通过 inputSchema 校验）
 * @param params 画布上配置的工具参数（paramSchema 实例，定义发布时已校验）
 */
public record ToolRequest(Map<String, Object> input, Map<String, Object> params) {

    public ToolRequest {
        input = input == null ? Map.of() : Map.copyOf(input);
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 便捷工厂 */
    public static ToolRequest of(Map<String, Object> input, Map<String, Object> params) {
        return new ToolRequest(input, params);
    }
}