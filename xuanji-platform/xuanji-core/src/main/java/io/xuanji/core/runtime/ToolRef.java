package io.xuanji.core.runtime;

import java.util.List;

/**
 * 工具引用（agent_config.tools_json 项）：三类来源，引用一律钉住已发布版本
 * （主文档 4.8 引用钉住；type=AGENT 即平台内置 agent.invoke 对等互调，
 * 不使用 AgentScope 的 agent_spawn/agent_send）。
 *
 * @param type     TOOL / MCP / AGENT
 * @param ref      引用坐标（tool:{id}@{version} / mcp:{id}@{version} / agent:{id}@{version}）
 * @param override 节点级能力绑定覆盖（Q12：节点级 &gt; agent 本身，同 id 覆盖其余保留；可空）
 */
public record ToolRef(String type, String ref, MapOverride override) {

    /** 覆盖参数（如 MCP 工具的参数覆盖；可空） */
    public record MapOverride(String alias, List<String> allow, List<String> deny) {
    }

    public ToolRef {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("ToolRef.type must not be blank");
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ToolRef.ref must not be blank");
        }
    }
}
