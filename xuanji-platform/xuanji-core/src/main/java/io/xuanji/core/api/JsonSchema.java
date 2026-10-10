package io.xuanji.core.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON Schema 文档的值对象（不可变）。
 *
 * <p>P0 以 Jackson {@link JsonNode} 承载 schema 文档本身，校验执行由平台侧
 * 统一实现（如 networknt json-schema-validator，后续引入）；core 只定义契约类型，
 * 不绑定具体校验库——校验器本身也是可替换的实现细节。</p>
 *
 * @param document JSON Schema 文档（draft-07 及以上），不可为 null
 */
public record JsonSchema(JsonNode document) {

    public JsonSchema {
        if (document == null) {
            throw new IllegalArgumentException("schema document must not be null");
        }
    }

    /** 任意对象 schema（P0 内置节点的宽松默认值） */
    public static JsonSchema anyObject() {
        return new JsonSchema(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("type", "object"));
    }

    /** 从原始 JSON 文本解析（解析失败抛 IllegalArgumentException） */
    public static JsonSchema parse(String rawJson) {
        try {
            return new JsonSchema(new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawJson));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON schema: " + e.getMessage(), e);
        }
    }
}
