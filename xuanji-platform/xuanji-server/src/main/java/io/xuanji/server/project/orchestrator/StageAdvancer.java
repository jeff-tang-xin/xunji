package io.xuanji.server.project.orchestrator;

import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.project.ProjectService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 阶段收口推进器——complete_stage 的执行体。
 *
 * <p>复用既有收口链（产出收口总结 → 自动批准 → advance 推进下一阶段），
 * 与自动流转监听器（onRunFinished）同一套语义；区别是触发源：
 * 这里由主控 agent 在对话中显式调 complete_stage 触发。</p>
 *
 * <p>依赖说明：ProjectService → ChatService → StageOrchestrator → 本类 → ProjectService
 * 构成引用环，用 @Lazy 代理打断（运行时才解析，无实际循环初始化）。</p>
 */
@Component
public class StageAdvancer {

    private final ProjectService projects;

    public StageAdvancer(@Lazy ProjectService projects) {
        this.projects = projects;
    }

    /**
     * 收口并推进下一阶段。
     *
     * @param summary 主控给出的阶段收口总结（留档进收口记录）
     * @return 新阶段标识与规划 id
     */
    public Map<String, Object> complete(String wsId, String stage, String summary) {
        LoginUser system = new LoginUser(null, "system", "主控收口",
                null, true, false, "ALL", Set.of());
        Map<String, Object> drafted = projects.draftStageSummary(system, wsId);
        long summaryId = ((Number) drafted.get("summary_id")).longValue();
        projects.decideStageSummary(system, wsId, summaryId, Map.of("decision", "APPROVE"));
        Map<String, Object> advanced = projects.advanceStage(system, wsId,
                Map.of("waive_reason", summary == null || summary.isBlank()
                        ? "主控收口" : summary));
        return advanced;
    }
}
