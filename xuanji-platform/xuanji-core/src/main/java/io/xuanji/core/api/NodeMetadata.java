package io.xuanji.core.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 节点元数据——驱动画布面板、动态表单与发布校验的全部静态信息。
 *
 * <p>设计意图：节点实现通过 {@link #inputSchema()}/{@link #outputSchema()}/{@link #paramSchema()}
 * 三个 JSON Schema 自描述，平台据此做到「核心零改动」接纳任意新节点：
 * 画布面板按 category 分组渲染、属性抽屉按 paramSchema 动态出表单、
 * 发布校验按 schema 做上下游兼容性与配置合法性检查。</p>
 *
 * @param id           全局唯一节点标识，格式 {plugin}.{name}，如 jira.create-issue
 * @param name         画布显示名
 * @param kind         节点形态（atomic/agent/workflow 三形态统一）
 * @param category     面板分组：basic / llm / agent / connector.{platform}
 * @param icon         图标标识（前端图标库 key）
 * @param description  说明文案
 * @param inputSchema  上游输出 → 本节点输入的兼容性校验依据
 * @param outputSchema 本节点输出声明（下游 inputSchema 校验依据）
 * @param paramSchema  配置表单 schema（前端动态渲染；发布时按其实例校验 params）
 * @param capabilities 能力声明（见 {@link NodeCapability}）
 */
public record NodeMetadata(
        String id,
        String name,
        NodeKind kind,
        String category,
        String icon,
        String description,
        JsonSchema inputSchema,
        JsonSchema outputSchema,
        JsonSchema paramSchema,
        Set<NodeCapability> capabilities
) {
    public NodeMetadata {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
