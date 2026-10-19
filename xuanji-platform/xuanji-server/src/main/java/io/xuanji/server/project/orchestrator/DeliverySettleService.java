package io.xuanji.server.project.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 交付结算服务（V36 需求交付树）：用例/bug 状态变化驱动的需求 DONE 门禁。
 *
 * <p>机器判定口径（信任链批二方向）：需求 DONE 不能只看「步骤产出」——
 * 有用例的需求必须全部用例 PASSED 且无未了结（OPEN/FIXING）bug；用例 FAILED/BLOCKED
 * 时把已 DONE 的需求拉回 IN_PROGRESS（假完成暴露出来，真相单一）。
 * 无用例的需求不受影响（向后兼容，小项目可以不落用例）。</p>
 *
 * <p>回写失败只记日志（看板信息不阻塞主流程，与 TaskRunner.settleRequirements 同口径）。</p>
 */
@Component
public class DeliverySettleService {

    private static final Logger log = LoggerFactory.getLogger(DeliverySettleService.class);

    private final TestCaseRepository testCaseRepo;
    private final BugRepository bugRepo;
    private final RequirementRepository requirementRepo;
    private final ProjectEventBus projectEvents;

    public DeliverySettleService(TestCaseRepository testCaseRepo, BugRepository bugRepo,
                                 RequirementRepository requirementRepo, ProjectEventBus projectEvents) {
        this.testCaseRepo = testCaseRepo;
        this.bugRepo = bugRepo;
        this.requirementRepo = requirementRepo;
        this.projectEvents = projectEvents;
    }

    /**
     * 用例/bug 结算后检查需求达成：有用例的需求——全部 PASSED 且无未了结 bug → DONE；
     * 存在 FAILED/BLOCKED 用例或未了结 bug → 已 DONE 的拉回 IN_PROGRESS。
     *
     * @return 联动说明（给主控的工具返回值用），无联动返回 null
     */
    public String settleRequirement(String wsId, String requirementCode) {
        try {
            List<TestCaseRepository.TestCase> cases = testCaseRepo.findByRequirement(wsId, requirementCode);
            List<BugRepository.Bug> bugs = bugRepo.findByRequirement(wsId, requirementCode);
            return requirementRepo.findByCode(wsId, requirementCode).map(r -> {
                // 无用例的需求：bug 维度仍要管——有未了结 bug 不自动 DONE（拉回）
                if (cases.isEmpty() && bugs.stream().noneMatch(BugRepository.Bug::open)) {
                    return null;
                }
                boolean allPassed = !cases.isEmpty() && cases.stream().allMatch(c -> "PASSED".equals(c.status()));
                boolean hasFailed = cases.stream().anyMatch(c -> "FAILED".equals(c.status()) || "BLOCKED".equals(c.status()));
                boolean hasOpenBug = bugs.stream().anyMatch(BugRepository.Bug::open);
                if (allPassed && !hasOpenBug) {
                    if (!"DONE".equals(r.status())) {
                        requirementRepo.updateStatus(wsId, requirementCode, "DONE");
                        publish(wsId, requirementCode, "DONE");
                        return requirementCode + " 机器判定 DONE（用例 " + cases.size() + " 条全通过、无未了结 bug）";
                    }
                    return null;
                }
                if ((hasFailed || hasOpenBug) && "DONE".equals(r.status())) {
                    requirementRepo.updateStatus(wsId, requirementCode, "IN_PROGRESS");
                    publish(wsId, requirementCode, "IN_PROGRESS");
                    return requirementCode + " 拉回 IN_PROGRESS（"
                            + (hasFailed ? "存在未通过用例" : "") + (hasFailed && hasOpenBug ? "、" : "")
                            + (hasOpenBug ? "存在未了结 bug" : "") + "）——假完成已暴露";
                }
                return null;
            }).orElse(null);
        } catch (Exception e) {
            log.warn("需求交付门禁检查失败（不阻塞）: ws={} req={} err={}", wsId, requirementCode, e.getMessage());
            return null;
        }
    }

    private void publish(String wsId, String code, String status) {
        try {
            projectEvents.publish(wsId, "requirement.updated", Map.of("code", code, "status", status));
        } catch (Exception e) {
            log.debug("requirement.updated 事件发布失败: {}", e.getMessage());
        }
    }
}
