package io.xuanji.server.project.orchestrator;

/**
 * 小流程状态机。
 *
 * <p>迁移路径：PLANNED → EXECUTING → DONE / FAILED；PLANNED → SKIPPED（主控跳过，留档理由）；
 * DECISION 步创建即 WAITING（等用户答复）→ DONE（withAnswer）；FAILED 的步骤由主控在下轮
 * 对话中自主决定重试或换路（AI 自愈，不依赖人工），同名同 agent 重试原地重拾（seq 不变）。</p>
 *
 * <p>active()（EXECUTING/WAITING）= 收口校验与 AutoPilot 抢跑判定的「未了结」口径；
 * PLANNED 在收口校验（hasActiveStep）中同样拦截——未执行未跳过的步骤不允许收口推进。</p>
 */
public enum StepStatus {
    PLANNED, EXECUTING, DONE, SKIPPED, FAILED, WAITING, WAITING_ACCEPT;

    /** 是否处于活跃态（执行中/等待用户/等待验收）——收口校验与状态推导用。 */
    public boolean active() {
        return this == EXECUTING || this == WAITING || this == WAITING_ACCEPT;
    }
}
