package io.xuanji.core.api;

/**
 * 链路上下文——日志关联与分布式追踪的最小载体。
 *
 * @param traceId 全链路追踪 ID（一次 run 一个，贯穿引擎/节点/连接器日志）
 * @param spanId  当前跨度 ID（一次节点执行一个）
 */
public record TraceContext(String traceId, String spanId) {

    public static TraceContext of(String traceId, String spanId) {
        return new TraceContext(traceId, spanId);
    }
}
