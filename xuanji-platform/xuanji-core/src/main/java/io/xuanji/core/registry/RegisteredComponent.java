package io.xuanji.core.registry;

import io.xuanji.core.api.NodeMetadata;

import java.util.Set;

/**
 * 注册表条目——一个已登记组件（节点/解析器）的快照。
 *
 * @param nodeId       节点标识（{plugin}.{name}）
 * @param metadata     节点元数据
 * @param pluginModule 来源插件模块（artifactId，如 node-basic / connector-jira）——
 *                     热插拔与依赖溯源的依据；P0 由装配方传入
 * @param enabled      是否启用（面板查询仅返回 enabled）
 */
public record RegisteredComponent(
        String nodeId,
        NodeMetadata metadata,
        String pluginModule,
        boolean enabled
) {

    public RegisteredComponent {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
    }

    /** 该节点暴露的能力集（校验便捷方法） */
    public Set<io.xuanji.core.api.NodeCapability> capabilities() {
        return metadata.capabilities();
    }
}
