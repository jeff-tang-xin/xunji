package io.xuanji.core.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 连接器元数据。
 *
 * @param id               全局唯一，如 jira / zentao / yunxiao
 * @param name             显示名
 * @param icon             图标标识
 * @param description      说明
 * @param credentialSchema 凭据字段 schema（前端动态渲染凭据表单；字段值加密落库）
 */
public record ConnectorMetadata(
        String id,
        String name,
        String icon,
        String description,
        JsonSchema credentialSchema
) {
}
