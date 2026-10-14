package io.xuanji.server.project.orchestrator;

/**
 * 小流程状态机。
 *
 * <p>迁移路径：PLANNED → EXECUTING → DONE / FAILED；PLANNED → SKIPPED（主控跳过，留档理由）；
 * 任意态 → WAITING（主控 request_decision 向用户征询，用户答复后由主控继续推进）。
 * FAILED 的步骤由主控在下轮对话中自主决定重试或换路（AI 自愈，不依赖人工）。</p>
 */
public enum StepStatus {
    PLANNED, EXECUTING, DONE, SKIPPED, FAILED, WAITING;

    /** 是否处于活跃态（执行中或等待用户）——收口校验与状态推导用。 */
    public boolean active() {
        return this == EXECUTING || this == WAITING;
    }
}
