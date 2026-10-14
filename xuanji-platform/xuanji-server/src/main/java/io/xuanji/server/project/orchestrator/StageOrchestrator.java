package io.xuanji.server.project.orchestrator;

import io.xuanji.core.runtime.AgentMessage;
import io.xuanji.core.runtime.AgentRuntime;
import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.core.runtime.ToolDef;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.engine.AgentNodeExecutor;
import io.xuanji.server.project.ProjectChatService;
import io.xuanji.server.repository.WorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 阶段编排器——AI 驱动内核的对话入口。
 *
 * <p><b>职责</b>：把阶段对话交给主控 agent（阶段绑定 agents[0]，默认 project_manager），
 * 并为其装配编排工具（{@link OrchestrationTools}，BEAN 形态）。主控在 ReAct 循环中
 * 自主规划（plan_steps）、发起讨论（discuss）、派发任务（execute_step）、
 * 跳过（skip_step）、征询（request_decision）、收口（complete_stage）——
 * 用户只说话，流程由 AI 决定。</p>
 *
 * <p><b>无状态纪律</b>：本类不持有任何跨请求状态；会话上下文在
 * {@link OrchestrationTools} 实例内（会话级），编排状态全部在黑板（step 台账）。
 * 多项目并发安全。</p>
 *
 * <p><b>双轨</b>：阶段存在活跃 run（RUNNING/SUSPENDED，旧 LiteFlow 内核）时对话仍走
 * 旧链路（{@link ProjectChatService}），由调用方判断；本类只接新内核对话。</p>
 */
@Service
public class StageOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StageOrchestrator.class);

    private static final String DEFAULT_MASTER = "project_manager";

    private final AgentRuntime runtime;
    private final AgentNodeExecutor agentNodes;
    private final StageStepRepository stepRepo;
    private final TaskRunner taskRunner;
    private final DiscussionEngine discussionEngine;
    private final io.xuanji.server.repository.ProjectRepository projectRepo;
    private final WorkspaceRepository workspaceRepo;
    private final StageAdvancer advancer;
    private final ObjectMapper objectMapper;
    private final RequirementRepository requirementRepo;

    /** SSE 会话执行池：虚拟线程（JDK21），阻塞 IO 型任务不占平台线程，并发对话不再互相排队。 */
    private final ExecutorService chatExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public StageOrchestrator(AgentRuntime runtime, AgentNodeExecutor agentNodes,
                             StageStepRepository stepRepo, TaskRunner taskRunner,
                             DiscussionEngine discussionEngine,
                             io.xuanji.server.repository.ProjectRepository projectRepo,
                             WorkspaceRepository workspaceRepo, StageAdvancer advancer,
                             ObjectMapper objectMapper, RequirementRepository requirementRepo) {
        this.runtime = runtime;
        this.agentNodes = agentNodes;
        this.stepRepo = stepRepo;
        this.taskRunner = taskRunner;
        this.discussionEngine = discussionEngine;
        this.projectRepo = projectRepo;
        this.workspaceRepo = workspaceRepo;
        this.advancer = advancer;
        this.objectMapper = objectMapper;
        this.requirementRepo = requirementRepo;
    }

    /**
     * 主控对话（SSE）：组装主控 spec（阶段绑定 agents[0] + 编排工具 + 阶段上下文），
     * 流式对话，结束后发 stage.finish。
     */
    /**
     * 主控对话入口。可见性契约：本方法只校验项目存在，调用方（chatByStage）必须先做
     * requireWorkspaceVisible/requireOwner——新增调用方时不得绕过上游校验直接调这里。
     */
    public SseEmitter chat(LoginUser user, String wsId, String stage, int iteration, String query) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        Map<String, Object> ws = workspaceRepo.findById(wsId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));

        String masterAgent = masterAgentOf(wsId, stage);
        String sessionId = io.xuanji.server.project.StageChatKeys.stageSession(wsId, stage, iteration);
        String projectGoal = str(ws.get("description"));
        String stageGoal = stageGoalOf(stage);
        String guidance = guidance(stageGoal, wsId, stage, iteration);

        OrchestrationTools tools = new OrchestrationTools(user, wsId, stage, iteration,
                projectGoal, stageGoal, stepRepo, taskRunner, discussionEngine,
                projectRepo, advancer, agentNodes, objectMapper, requirementRepo);

        AgentSpec base = agentNodes.buildSpecByAgentName(masterAgent, sessionId, guidance);
        AgentSpec spec = withTools(base, tools.toolDefs());

        SseEmitter emitter = new SseEmitter(300_000L);
        chatExecutor.submit(() -> {
            try {
                send(emitter, "agent.start", Map.of("agent", spec.name()));
                runtime.stream(runtime.createAgent(spec), new AgentMessage(query, null, null), evt -> {
                    send(emitter, evt.type(), payloadOf(evt));
                });
                send(emitter, "stage.finish", Map.of("status", "SUCCESS"));
                emitter.complete();
                log.info("orchestrator chat finished: ws={} stage={} agent={}", wsId, stage, masterAgent);
            } catch (Exception e) {
                log.warn("orchestrator chat failed: ws={} stage={} -> {}", wsId, stage, e.getMessage());
                try {
                    send(emitter, "stage.finish", Map.of("status", "FAILED", "error", String.valueOf(e.getMessage())));
                    emitter.complete();
                } catch (Exception ignore) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    /** 主控 = 阶段绑定 agents[0]（可配置），缺省 project_manager。 */
    private String masterAgentOf(String wsId, String stage) {
        List<Map<String, Object>> caps = projectRepo.findStageCapability(wsId, stage);
        if (!caps.isEmpty()) {
            Object agents = caps.get(0).get("agents");
            if (agents instanceof List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
        }
        return DEFAULT_MASTER;
    }

    /** 阶段目标（V32 模板 deliverables/goal 落地前的内置基线）。 */
    private String stageGoalOf(String stage) {
        return switch (stage) {
            case "REQUIREMENT" -> "达成范围共识，产出可被测试直接执行的需求规格与验收标准";
            case "DEVELOP" -> "产出可运行、可验证的实现与配套测试，交付物可追溯";
            case "OPERATE" -> "保障系统稳定运行，承接新需求与缺陷以新一轮迭代处理";
            default -> "完成本阶段职责";
        };
    }

    /** 主控 system prompt 的编排指导：角色基座 + 意图锚 + 阶段目标 + 工具用法 + 黑板快照。 */
    private String guidance(String stageGoal, String wsId, String stage, int iteration) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 阶段编排指导（AI 驱动内核）\n");
        sb.append("你是本阶段的主控。用户只负责说话，流程由你决定：做什么、顺序、谁能跳。\n");
        String anchor = stepRepo.findAnchorText(wsId, stage, iteration);
        if (anchor != null && !anchor.isBlank()) {
            sb.append("【意图锚（规划时锁定，不可偏离）】").append(anchor).append("\n");
            sb.append("每一步的产出都必须对照意图锚自查：还在为同一个目标服务吗？发现偏离立即纠偏或申报。\n");
        }
        sb.append("【阶段目标】").append(stageGoal).append("\n");
        sb.append("【工作方式】\n");
        sb.append("1. 阶段开始或需求变化时，先用 plan_steps 规划小流程（TASK=产出型，DISCUSS=讨论型）；规划必须携带 intent_anchor（全局意图与总体验收口径），并为每个 TASK 步给出可判定的 acceptance 断言（test/artifact/api 三类，禁止「质量良好」这类不可判定描述）；\n");
        sb.append("2. 需求先行：实施工作开始前必须先有确认的需求项——讨论沉淀的需求用 declare_requirements 落档（PROPOSED），再用 request_decision 请用户确认（CONFIRMED）；规划步骤时为每步声明 requirement_codes（为了哪个需求）；被依赖需求未 DONE 前，不要为依赖它的需求排实施步骤（可以先排讨论型步骤）；运行中用户提出新需求，同样走 declare_requirements → 用户确认 → 并入规划；\n");
        sb.append("3. 澄清/共识类的事直接在对话里完成（秒级），不要做成任务；\n");
        sb.append("4. 需要多角色视角时用 discuss 发起讨论；讨论最少三方——你必须主持（自动加入发言与汇总），participants 填目的方等参与方（至少 2 个角色，按问题域拉能解答的人）；rounds 默认 0（并行独立观点）即可，互见轮仅在确需收敛分歧时用（每轮发言需数分钟，轮次越多越慢）；\n");
        sb.append("5. 有明确产出的工作用 execute_step 派给对应 agent（后台执行，8 分钟超时，任务必须小）；instruction 必须包含该步的 acceptance 验收口径，让执行者知道「什么算做完」；执行中遇到需求/架构疑问，执行 agent 会通过 ask_peer 向对应角色求助（问答留档，你下轮可见）——发现求助暴露需求矛盾时，用 request_decision 升级给用户；\n");
        sb.append("6. 判断某个小流程不值得做时用 skip_step 跳过，必须写明理由；\n");
        sb.append("7. 执行中发现某步无法按计划完成（技术障碍/依赖缺失/成本超限），必须先用 declare_deviation 申报偏差（原因+替代方案），禁止静默换路、静默降级或悄悄缩小范围；降级影响交付口径时继续用 request_decision 征询用户；\n");
        sb.append("8. 需要用户拍板时用 request_decision；用户在对话里答复后用 record_answer 落档；\n");
        sb.append("9. 阶段职责完成（无执行中/等待中的小流程）时用 complete_stage 收口推进；收口总结必须如实列出未达成/降级/跳过的项，禁止把未验证的说成已完成；\n");
        sb.append("10. 所有 agent 参数（agent/participants/summarizer）一律用英文名（agent_config.name，如 project_manager、product_analyst），禁止用中文显示名；\n");
        sb.append("11. plan_steps 只规划不执行——规划里登记的 DISCUSS/TASK 步骤由你后续逐个用 discuss/execute_step 发起，不要规划后又立刻重复发起同一件事。\n");
        appendTemplateHint(sb, wsId, stage);
        sb.append("【当前黑板】\n").append(stepDigest(wsId, stage, iteration));
        return sb.toString();
    }

    /** 模板预设建议（B 方案：模板降级为 PM 的默认建议，可采纳可推翻——不再是强制执行蓝图）。 */
    private void appendTemplateHint(StringBuilder sb, String wsId, String stage) {
        try {
            List<Map<String, Object>> caps = projectRepo.findStageCapability(wsId, stage);
            if (caps.isEmpty()) {
                return;
            }
            Object agents = caps.get(0).get("agents");
            if (agents instanceof List<?> list && !list.isEmpty()) {
                sb.append("【模板预设建议】模板对本阶段的建议编制：agents=").append(list)
                        .append("（这是默认建议：小项目可直接采纳省一轮斟酌，复杂项目按用户输入自行调整编制与步骤）\n");
            }
        } catch (Exception ignore) {
            // 模板建议只是参考信息，读取失败不影响编排
        }
    }

    private String stepDigest(String wsId, String stage, int iteration) {
        StringBuilder sb = new StringBuilder();
        for (StageStep s : stepRepo.findByStage(wsId, stage, iteration)) {
            sb.append("- [").append(s.seq()).append("] ").append(s.name())
                    .append("（").append(s.stepType()).append("/").append(s.status()).append("）");
            if (s.outputSummary() != null && !s.outputSummary().isBlank()) {
                String o = s.outputSummary();
                sb.append("：").append(o.length() > 120 ? o.substring(0, 120) + "…" : o);
            }
            sb.append("\n");
        }
        return sb.length() == 0 ? "（空——本阶段还没有任何规划）" : sb.toString();
    }

    /** 重建 spec：把编排工具（BEAN 形态）追加进 resolvedTools。 */
    private AgentSpec withTools(AgentSpec base, List<ToolDef> tools) {
        List<ToolDef> merged = new ArrayList<>(base.resolvedTools() == null ? List.of() : base.resolvedTools());
        merged.addAll(tools);
        return new AgentSpec(base.agentConfigId(), base.name(), base.systemPrompt(), base.model(),
                base.tools(), base.permission(), base.runtime(), base.sessionId(),
                merged, base.skills());
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(toJson(payload), MediaType.APPLICATION_JSON));
        } catch (Exception ignore) {
            // 客户端断开：静默（后台任务照常落黑板）
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> payloadOf(io.xuanji.core.runtime.AgentStreamEvent evt) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (evt.delta() != null) {
            payload.put("delta", evt.delta());
        }
        if (evt.text() != null) {
            payload.put("text", evt.text());
        }
        if (evt.meta() != null && !evt.meta().isEmpty()) {
            payload.putAll(evt.meta());
        }
        return payload;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
