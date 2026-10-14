package io.xuanji.server.project.orchestrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 信任链第一批语义（纯内存，无 DB）：
 * verified_by 默认 self（AI 自述）——tool/human 只能由平台判定器与人工路径写入；
 * withDeviation 申报偏差不改状态；状态迁移保持信任链三字段。
 */
class StageStepTrustChainTest {

    private StageStep planned() {
        return new StageStep(null, "ws1", "REQUIREMENT", 1, 1, "写PRD", StepType.TASK,
                "product_analyst", java.util.List.of(), StepStatus.PLANNED,
                "产出需求规格", null, null, null, null, null, null, null, null,
                null, "{\"acceptance\":true}", null);
    }

    @Test
    void verifiedByDefaultsToSelf() {
        assertEquals("self", planned().verifiedBy());
    }

    @Test
    void acceptanceSurvivesStatusTransitions() {
        StageStep done = planned().withStatus(StepStatus.EXECUTING)
                .withOutput("摘要", "全文", null);
        assertEquals(StepStatus.DONE, done.status());
        assertEquals("{\"acceptance\":true}", done.acceptanceJson());
        assertEquals("self", done.verifiedBy());
    }

    @Test
    void deviationDoesNotChangeStatus() {
        StageStep before = planned().withStatus(StepStatus.EXECUTING);
        String dev = "{\"reason\":\"依赖缺失\",\"fallback\":\"先做简化版\",\"declaredAt\":\"2026-10-14\"}";
        StageStep after = before.withDeviation(dev);
        assertEquals(before.status(), after.status());
        assertEquals(dev, after.deviationJson());
        assertNotEquals(before, after);
    }

    @Test
    void blankVerifiedByFallsBackToSelf() {
        StageStep s = new StageStep(null, "ws1", "REQUIREMENT", 1, 1, "x", StepType.TASK,
                "a", null, StepStatus.PLANNED, "i", null, null, null, null, null, null, null, null,
                "  ", null, null);
        assertEquals("self", s.verifiedBy());
        assertNull(s.acceptanceJson());
    }
}
