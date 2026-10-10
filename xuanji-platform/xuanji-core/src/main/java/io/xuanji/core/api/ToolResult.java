package io.xuanji.core.api;

import java.util.Map;

/**
 * 工具执行结果 —— {@link Tool#invoke(ToolRequest)} 的返回值。
 *
 * @param output 输出数据（将按 edge.mapping 流向下游节点；结构应符合 metadata().outputSchema()）
 */
public record ToolResult(Map<String, Object> output) {

    public ToolResult {
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    /** 便捷工厂 */
    public static ToolResult of(Map<String, Object> output) {
        return new ToolResult(output);
    }
}