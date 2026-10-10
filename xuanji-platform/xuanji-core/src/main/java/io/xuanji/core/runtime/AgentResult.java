package io.xuanji.core.runtime;

import java.util.Map;

/**
 * Agent 执行结果（阻塞聚合形态；流式事件映射随 chat SSE 增补）。
 *
 * @param text    最终文本输出（写入节点输出 output 字段）
 * @param usage   用量统计（tokens 等，可空）
 * @param meta    运行元信息（迭代次数/工具调用摘要等，可空）
 */
public record AgentResult(String text, Map<String, Object> usage, Map<String, Object> meta) {

    public AgentResult {
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static AgentResult of(String text) {
        return new AgentResult(text, Map.of(), Map.of());
    }
}
