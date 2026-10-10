package io.xuanji.core.definition;

import java.util.Map;

/**
 * 触发绑定——随工作流定义版本化（画布 JSON 的 triggers 数组项）。
 *
 * @param type   触发类型
 * @param config 类型相关配置：SCHEDULE={cron,timezone,misfire,concurrency,window}；
 *               WEBHOOK={connectorId,eventType,match}；CHAT_INPUT={workspaceId}；
 *               MANUAL/API 可空
 */
public record TriggerDef(TriggerType type, Map<String, Object> config) {

    public TriggerDef {
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
