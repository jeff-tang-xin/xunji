package io.xuanji.core.definition;

/**
 * 决策门配置（画布 JSON nodes[].decisionGate，docs/detail/03 §1 字段约束）。
 *
 * @param enabled     是否启用决策门
 * @param interaction 交互形态：WORKSPACE（默认，产物审阅+批注+修改）/ FORM（简单表单）
 * @param artifactRef 审阅产物（缺省 = 上游节点输出）
 * @param onReject    拒绝策略：REDO（Agent 按批注重生成）/ TERMINATE / ROLLBACK
 * @param timeout     决策超时（如 24h）
 * @param onTimeout   超时策略：ESCALATE / APPROVE / REJECT
 */
public record DecisionGateDef(
        boolean enabled,
        Interaction interaction,
        String artifactRef,
        RejectPolicy onReject,
        String timeout,
        TimeoutPolicy onTimeout
) {

    /** 交互形态 */
    public enum Interaction { WORKSPACE, FORM }

    /** 拒绝策略 */
    public enum RejectPolicy { REDO, TERMINATE, ROLLBACK }

    /** 超时策略 */
    public enum TimeoutPolicy { ESCALATE, APPROVE, REJECT }

    /** 未启用决策门的默认配置 */
    public static DecisionGateDef disabled() {
        return new DecisionGateDef(false, Interaction.WORKSPACE, null, RejectPolicy.REDO, null, TimeoutPolicy.ESCALATE);
    }
}
