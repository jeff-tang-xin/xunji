package io.xuanji.core.api;

import java.util.Map;

/**
 * 解析后的平台标准事件——外部平台事件进入引擎前的统一形态。
 *
 * @param eventType 事件类型，如 issue.updated
 * @param payload   标准化载荷（作为触发输入流入 run 上下文）
 * @param matchKeys 供触发绑定做匹配的键值（如 project=XX, status=Approved；
 *                  定义触发绑定 {connectorId, eventType, match} 命中判定依据）
 */
public record ParsedEvent(
        String eventType,
        Map<String, Object> payload,
        Map<String, Object> matchKeys
) {

    public ParsedEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        matchKeys = matchKeys == null ? Map.of() : Map.copyOf(matchKeys);
    }
}
