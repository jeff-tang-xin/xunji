package io.xuanji.core.runtime;

import java.util.Map;

/**
 * Agent 流式事件（chat SSE 的平台侧统一形态，detail/05 §2 工作区对话流事件子集）。
 *
 * <p>type 取值：</p>
 * <ul>
 *   <li>{@code agent.start} — Agent 开始执行（meta: sessionId/replyId/name）</li>
 *   <li>{@code agent.delta} — 文本增量（delta 字段）</li>
 *   <li>{@code agent.result} — 最终结果（text 字段）</li>
 *   <li>{@code agent.end} — 本轮结束</li>
 *   <li>{@code tool.call} — 工具调用开始（meta: tool/name，随工具注册阶段启用）</li>
 *   <li>{@code error} — 执行异常（text 字段为错误消息）</li>
 * </ul>
 */
public record AgentStreamEvent(String type, String delta, String text, Map<String, Object> meta) {

    public static AgentStreamEvent of(String type, Map<String, Object> meta) {
        return new AgentStreamEvent(type, null, null, meta == null ? Map.of() : meta);
    }

    public static AgentStreamEvent delta(String delta) {
        return new AgentStreamEvent("agent.delta", delta, null, Map.of());
    }

    public static AgentStreamEvent result(String text) {
        return new AgentStreamEvent("agent.result", null, text, Map.of());
    }

    public static AgentStreamEvent error(String message) {
        return new AgentStreamEvent("error", null, message, Map.of());
    }
}
