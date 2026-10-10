package io.xuanji.core.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 能力绑定（决议 Q12）：MCP 与 skill 同为 agent 可绑定的能力内容。
 *
 * <p><b>两级绑定 + 节点级优先</b>：</p>
 * <ul>
 *   <li>agent 级：agent_config 默认绑定（该 Agent 到处可用）</li>
 *   <li>节点级：工作流 agent 节点上绑定（{@code NodeDef.capabilityBinding}）</li>
 *   <li>优先级：<b>工作流 agent 节点 &gt; agent 本身</b>——合并时同 id 冲突以节点级为准，
 *       agent 级其余能力保留（节点级是「追加 + 覆盖」，不是完全替换）</li>
 * </ul>
 *
 * @param skills 绑定的 skill 资产（已发布版本钉住）
 * @param mcps   绑定的 MCP server 资产（已发布版本钉住；其工具自动发现为可用工具）
 */
public record AgentCapabilityBinding(List<AssetRef> skills, List<AssetRef> mcps) {

    public AgentCapabilityBinding {
        skills = skills == null ? List.of() : List.copyOf(skills);
        mcps = mcps == null ? List.of() : List.copyOf(mcps);
    }

    /**
     * 计算有效绑定：agent 级为基底，节点级追加并覆盖同 id 项。
     *
     * @param agentLevel agent_config 上的默认绑定
     * @param nodeLevel  工作流 agent 节点上的绑定（null = 未覆盖，直接用 agent 级）
     * @return 有效绑定（节点级优先）
     */
    public static AgentCapabilityBinding effective(AgentCapabilityBinding agentLevel,
                                                   AgentCapabilityBinding nodeLevel) {
        if (nodeLevel == null) {
            return agentLevel == null ? new AgentCapabilityBinding(List.of(), List.of()) : agentLevel;
        }
        if (agentLevel == null) {
            return nodeLevel;
        }
        return new AgentCapabilityBinding(
                mergeNodeWins(agentLevel.skills, nodeLevel.skills),
                mergeNodeWins(agentLevel.mcps, nodeLevel.mcps));
    }

    /** 合并规则：节点级全部保留；agent 级中与节点级同 id 的被覆盖，其余保留 */
    private static List<AssetRef> mergeNodeWins(List<AssetRef> agentLevel, List<AssetRef> nodeLevel) {
        Map<String, AssetRef> merged = new LinkedHashMap<>();
        for (AssetRef ref : agentLevel) {
            merged.put(ref.id(), ref);
        }
        for (AssetRef ref : nodeLevel) {
            merged.put(ref.id(), ref);   // 节点级覆盖同 id（含版本钉住升级）
        }
        return new ArrayList<>(merged.values());
    }
}
