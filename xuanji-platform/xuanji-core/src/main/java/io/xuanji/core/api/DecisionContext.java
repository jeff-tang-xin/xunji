package io.xuanji.core.api;

import java.util.Map;

/**
 * 决策门恢复注入——「决策后从该节点恢复」语义的数据载体。
 *
 * <p>交互式决策工作区模型（主文档 v0.8）：上游产物渲染给用户 → 实时批注 +
 * 在线修改（产物新版本）→ 决策。恢复执行时，引擎把决策结论与人的修订
 * 注入 {@link NodeContext#decision()}，节点据此调整行为（如按 adjustedParams
 * 重生成、把 annotations 传递给下游）。</p>
 *
 * @param present         是否为决策恢复（false = 首次执行）
 * @param decision        决策结论：APPROVED / REJECTED / ROLLBACK 等（平台枚举值字符串）
 * @param decidedBy       决策人
 * @param comment         批注意见摘要（完整批注历史在 wf_decision.payload_json）
 * @param adjustedParams  人在决策工作区在线修改后的节点配置（引擎已按 paramSchema 校验）
 * @param artifactUpdates 产物修订（版本号 → 内容/锚点），节点可读取并写回 run 上下文
 * @param annotations     区域锚点批注列表（原样透传，供产物审阅类节点消费）
 */
public record DecisionContext(
        boolean present,
        String decision,
        String decidedBy,
        String comment,
        Map<String, Object> adjustedParams,
        Map<String, Object> artifactUpdates,
        Map<String, Object> annotations
) {

    /** 首次执行（无决策注入）的空上下文 */
    public static DecisionContext empty() {
        return new DecisionContext(false, null, null, null, Map.of(), Map.of(), Map.of());
    }

    public DecisionContext {
        adjustedParams = adjustedParams == null ? Map.of() : Map.copyOf(adjustedParams);
        artifactUpdates = artifactUpdates == null ? Map.of() : Map.copyOf(artifactUpdates);
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
    }
}
