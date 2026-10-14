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

    /** SSE 会话执行池：虚拟线程（JDK21），阻塞 IO 型任务不占平台线程，并发对话不再互相排队。 */
    private final ExecutorService chatExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public StageOrchestrator(AgentRuntime runtime, AgentNodeExecutor agentNodes,
                             StageStepRepository stepRepo, TaskRunner taskRunner,
                             DiscussionEngine discussionEngine,
                             io.xuanji.server.repository.ProjectRepository projectRepo,
                             WorkspaceRepository workspaceRepo, StageAdvancer advancer,
                             ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.agentNodes = agentNodes;
        this.stepRepo = stepRepo;
        this.taskRunner = taskRunner;
        this.discussionEngine = discussionEngine;
        this.projectRepo = projectRepo;
        this.workspaceRepo = workspaceRepo;
        this.advancer = advancer;
        this.objectMapper = objectMapper;
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
                projectRepo, advancer, agentNodes);

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

    /** 主控 system prompt 的编排指导：角色基座 + 阶段目标 + 工具用法 + 黑板快照。 */
    private String guidance(String stageGoal, String wsId, String stage, int iteration) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 阶段编排指导（AI 驱动内核）\n");
        sb.append("你是本阶段的主控。用户只负责说话，流程由你决定：做什么、顺序、谁能跳。\n");
        sb.append("【阶段目标】").append(stageGoal).append("\n");
        sb.append("【工作方式】\n");
        sb.append("1. 阶段开始或需求变化时，先用 plan_steps 规划小流程（TASK=产出型，DISCUSS=讨论型）；\n");
        sb.append("2. 澄清/共识类的事直接在对话里完成（秒级），不要做成任务；\n");
        sb.append("3. 需要多角色视角时用 discuss 发起讨论；rounds 默认 1（并行独立观点）即可，互见轮仅在确需收敛分歧时用（每轮发言需数分钟，轮次越多越慢）；\n");
        sb.append("4. 有明确产出的工作用 execute_step 派给对应 agent（后台执行，3 分钟超时，任务必须小）；\n");
        sb.append("5. 判断某个小流程不值得做时用 skip_step 跳过，必须写明理由；\n");
        sb.append("6. 需要用户拍板时用 request_decision；用户在对话里答复后用 record_answer 落档；\n");
        sb.append("7. 阶段职责完成（无执行中/等待中的小流程）时用 complete_stage 收口推进。\n");
        sb.append("8. 所有 agent 参数（agent/participants/summarizer）一律用英文名（agent_config.name，如 project_manager、product_analyst），禁止用中文显示名；\n");
        sb.append("9. plan_steps 只规划不执行——规划里登记的 DISCUSS/TASK 步骤由你后续逐个用 discuss/execute_step 发起，不要规划后又立刻重复发起同一件事。\n");
        sb.append("【当前黑板】\n").append(stepDigest(wsId, stage, iteration));
        return sb.toString();
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
