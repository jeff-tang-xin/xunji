package io.xuanji.core.api;

/**
 * 工具元数据 —— {@link Tool} 的自描述契约。
 *
 * <p>同一份元数据同时服务两个视角：</p>
 * <ul>
 *   <li><b>画布</b>：{@code name}/{@code description} 进节点面板，{@code parameters} 进
 *       属性抽屉动态表单（{@code paramSchema}）；</li>
 *   <li><b>Agent</b>：{@code name}/{@code description}/{@code parameters} 即 LLM 可见的
 *       函数签名（function-calling / tool_use 的 name / description / JSON Schema）。</li>
 * </ul>
 *
 * @param id           工具唯一标识（如 {@code pdf-parse}），最终画布 refId = {@code tool.{id}}
 * @param name         显示名（画布 & Agent 均展示，如「PDF 解析」）
 * @param description  描述（特化：用途、前提、副作用，LLM 据此决定是否调用）
 * @param parameters   入参 JSON Schema（供 Agent 的 function schema 与画布 paramSchema 共用）
 * @param outputSchema 输出 JSON Schema（可选；null 退化为任意对象）
 */
public record ToolMetadata(
        String id,
        String name,
        String description,
        JsonSchema parameters,
        JsonSchema outputSchema
) {

    public ToolMetadata {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ToolMetadata.id must not be blank");
        }
        name = (name == null || name.isBlank()) ? id : name;
        description = description == null ? "" : description;
        parameters = parameters == null ? JsonSchema.anyObject() : parameters;
        outputSchema = outputSchema == null ? JsonSchema.anyObject() : outputSchema;
    }

    /** 画布 refId（{@code tool.{id}}，与 {@link NodeMetadata#id()} 命名空间区分，避免与插件节点撞名） */
    public String refId() {
        return "tool." + id;
    }
}