package io.xuanji.core.api;

import java.util.Map;

/**
 * 节点执行结果。
 *
 * @param output  输出数据（将按 edge.mapping 流向下游节点；结构应符合 metadata().outputSchema()）
 * @param metrics 过程指标（耗时/重试次数/外部调用统计等，写 wf_node_run.metrics_json，供运行监控展示）
 */
public record NodeOutput(Map<String, Object> output, Map<String, Object> metrics) {

    public NodeOutput {
        output = output == null ? Map.of() : Map.copyOf(output);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    /** 仅输出的便捷工厂 */
    public static NodeOutput of(Map<String, Object> output) {
        return new NodeOutput(output, Map.of());
    }
}
