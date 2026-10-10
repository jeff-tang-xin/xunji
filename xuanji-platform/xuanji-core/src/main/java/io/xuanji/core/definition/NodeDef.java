package io.xuanji.core.definition;

import io.xuanji.core.api.AgentCapabilityBinding;
import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.NodeKind;

import java.util.Map;

/**
 * 画布节点定义（画布 JSON nodes[] 项）。
 *
 * <p>节点引用已注册组件（refId → ComponentRegistry），实例参数 params 按
 * 组件 paramSchema 校验。每个节点都有输入与输出（决议 Q10）：
 * 输入由上游 edge.mapping 装配，输出流向下游——类型契约在组件的
 * inputSchema/outputSchema 上；START/END 节点例外地在本定义上声明
 * 流程级 schema（见下）。</p>
 *
 * @param instanceId        画布实例 id（如 n1，图内唯一）
 * @param refId             引用的组件 id（{plugin}.{name}）；START/END 为平台内置标识
 *                          （system.start / system.end），不经注册表
 * @param kind              节点形态（职能差异化：START/END/ATOMIC/AGENT/WORKFLOW）
 * @param name              画布显示名
 * @param params            节点配置（组件 paramSchema 实例）
 * @param inputSchema       仅 START：流程级输入声明（run 启动输入契约；工作区场景 = 用户输入内容）
 * @param outputSchema      仅 END：流程级输出声明（run 最终产物契约）
 * @param capabilityBinding 仅 AGENT：节点级能力绑定（skills/mcps），优先级高于 agent 本身
 *                           的默认绑定（决议 Q12）；null = 用 agent_config 默认
 * @param decisionGate      决策门配置（仅 capabilities 含 SUPPORTS_DECISION_GATE 的节点可启用）
 * @param retry             重试策略
 * @param timeoutMs         超时毫秒（null = 不限；仅 SUPPORTS_TIMEOUT 生效）
 */
public record NodeDef(
        String instanceId,
        String refId,
        NodeKind kind,
        String name,
        Map<String, Object> params,
        JsonSchema inputSchema,
        JsonSchema outputSchema,
        AgentCapabilityBinding capabilityBinding,
        DecisionGateDef decisionGate,
        RetryPolicy retry,
        Long timeoutMs
) {

    public NodeDef {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if (refId == null || refId.isBlank()) {
            throw new IllegalArgumentException("refId must not be blank (instanceId=" + instanceId + ")");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
        decisionGate = decisionGate == null ? DecisionGateDef.disabled() : decisionGate;
        retry = retry == null ? RetryPolicy.none() : retry;
    }

    /** 平台内置开始节点 refId */
    public static final String START_REF = "system.start";
    /** 平台内置结束节点 refId */
    public static final String END_REF = "system.end";

    public boolean isStart() {
        return kind == NodeKind.START;
    }

    public boolean isEnd() {
        return kind == NodeKind.END;
    }
}
