package io.xuanji.core.runtime;

import java.util.Map;

/**
 * 解析后的可执行工具定义（docs/detail/04 §1 工具集成）：AgentNodeExecutor 按
 * ToolRef 查 tool_registry（PUBLISHED 钉版本）装配，AgentRuntime 实现据此构建
 * 可调用工具——契约只携带定义，不携带平台存储细节。
 *
 * @param name        工具名（LLM 可见，tool_registry.name）
 * @param description 工具描述（LLM 可见）
 * @param parameters  入参 JSON Schema（tool_registry.input_schema）
 * @param sourceType  来源形态（tool_registry.source_type：BEAN/OPENAPI/SCRIPT，P0 仅 OPENAPI）
 * @param sourceRef   来源坐标（OPENAPI 形态约定 "METHOD URL"，如 "GET https://host/path"）
 */
public record ToolDef(
        String name,
        String description,
        Map<String, Object> parameters,
        String sourceType,
        String sourceRef,
        /** 扩展载荷：AGENT 形态工具携带被调 AgentSpec（server 侧解析，agent-scope 侧执行）；其余形态为 null */
        Object payload) {

    public ToolDef {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
