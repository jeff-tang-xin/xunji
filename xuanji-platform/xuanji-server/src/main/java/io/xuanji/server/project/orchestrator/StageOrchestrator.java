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
    private final PeerConsultService peerConsult;
    private final StageDocStore docStore;
    private final StageChatEventBus eventBus;
    private final DecisionSettleService decisionSettler;
    private final ProjectEventBus projectEvents;
    private final TestCaseRepository testCaseRepo;
    private final BugRepository bugRepo;
    private final DeliverySettleService deliverySettler;

    /** SSE 会话执行池：虚拟线程（JDK21），阻塞 IO 型任务不占平台线程，并发对话不再互相排队。 */
    private final ExecutorService chatExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 系统唤醒防重闸：会话键 → 唤醒中。两个任务几乎同时落板时只放行一个唤醒（putIfAbsent 原子认领）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> wakeGuard =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** AutoPilot 防失控：会话键 → 已续跑轮数 / 上一轮黑板指纹（零进展熔断用）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> autoPilotRounds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> autoPilotLast =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** AutoPilot 单会话续跑上限：超过即停（防主控规划-失败-重规划死循环烧 token）。 */
    private static final int AUTOPILOT_MAX_ROUNDS = 8;

    public StageOrchestrator(AgentRuntime runtime, AgentNodeExecutor agentNodes,
                             StageStepRepository stepRepo, TaskRunner taskRunner,
                             DiscussionEngine discussionEngine,
                             io.xuanji.server.repository.ProjectRepository projectRepo,
                             WorkspaceRepository workspaceRepo, StageAdvancer advancer,
                             ObjectMapper objectMapper, RequirementRepository requirementRepo,
                             PeerConsultService peerConsult, StageDocStore docStore,
                             StageChatEventBus eventBus, DecisionSettleService decisionSettler,
                             ProjectEventBus projectEvents, TestCaseRepository testCaseRepo,
                             BugRepository bugRepo, DeliverySettleService deliverySettler) {
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
        this.peerConsult = peerConsult;
        this.docStore = docStore;
        this.eventBus = eventBus;
        this.decisionSettler = decisionSettler;
        this.projectEvents = projectEvents;
        this.testCaseRepo = testCaseRepo;
        this.bugRepo = bugRepo;
        this.deliverySettler = deliverySettler;
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

        // 同步执行互斥（2026-10-17）：该阶段有 EXECUTING 步骤 = 主控正阻塞在 discuss/execute_step
        // 工具里等结果——此时新 chat 请求不并发第二主控轮（会双主控互踩），用户消息走插话落库：
        // 讨论中 → 【用户】前缀进发言流（后续发言者与被 @ 者都能看到）；任务中 → 提示等待或终止。
        // 这就是「@ 要等当前发言结束」的结构性保证：主控拿到讨论结果后才处理 @。
        List<StageStep> executing = stepRepo.findByStage(wsId, stage, iteration).stream()
                .filter(s -> s.status() == StepStatus.EXECUTING).toList();
        if (!executing.isEmpty()) {
            StageStep cur = executing.get(0);
            SseEmitter echo = new SseEmitter(5_000L);
            if (cur.stepType() == StepType.DISCUSS) {
                discussionEngine.userSpeech(cur, query);
                send(echo, "speech.echo", Map.of("seq", cur.seq(), "message",
                        "你的发言已加入讨论流「" + cur.name() + "」，后续发言者会看到；"
                                + "需要某人专门回应就 @他（如 @architect xxx），主控收口时会安排。"));
            } else {
                send(echo, "speech.echo", Map.of("seq", cur.seq(), "message",
                        "任务「" + cur.name() + "」正在执行（同步无超时，做完为止）；"
                                + "不想等可以在追溯面板终止该步骤，主控会直接进入下一步。"));
            }
            send(echo, "stage.finish", Map.of("status", "ECHO"));
            echo.complete();
            return echo;
        }

        String masterAgent = masterAgentOf(wsId, stage);
        String sessionId = io.xuanji.server.project.StageChatKeys.stageSession(wsId, stage, iteration);
        String projectGoal = str(ws.get("description"));
        String stageGoal = stageGoalOf(stage);
        String guidance = guidance(stageGoal, wsId, stage, iteration);

        OrchestrationTools tools = new OrchestrationTools(user, wsId, stage, iteration,
                projectGoal, stageGoal, stepRepo, taskRunner, discussionEngine,
                projectRepo, advancer, agentNodes, objectMapper, requirementRepo, peerConsult,
                decisionSettler, projectEvents, testCaseRepo, bugRepo, deliverySettler,
                this::autoStartStage);

        AgentSpec base = agentNodes.buildSpecByAgentName(masterAgent, sessionId, guidance, wsId);
        AgentSpec spec = withTools(base, tools.toolDefs());

        // 30 分钟（2026-10-17 同步化）：主控工具调用内同步等讨论/任务做完（无超时），
        // 单请求可达 20 分钟+；900s 会把还在跑的对话掐断，前端只看到流静默断开
        SseEmitter emitter = new SseEmitter(1_800_000L);
        // 注册到项目级任务注册表：删除项目时 terminateAll 一键终止在跑主控轮
        // （interrupt 中断 LLM 调用，整棵同步调用树——执行任务/讨论/求助——随之终止）
        java.util.concurrent.CompletableFuture<Void> turnFuture = java.util.concurrent.CompletableFuture
                .runAsync(() -> {
                    try {
                        runMasterTurn(spec, wsId, stage, iteration, masterAgent, query, emitter, false, null);
                    } finally {
                        // AutoPilot（2026-10-19「派给开发任务做完了就做完了没后续」）：主控轮结束后
                        // 判定黑板——还有 PLANNED 步骤就自动续跑，流程不再靠用户一句句推
                        autoPilot(wsId, stage, iteration);
                    }
                }, chatExecutor);
        TaskRegistry.register(wsId, turnFuture);
        turnFuture.whenComplete((v, e) -> TaskRegistry.unregister(wsId, turnFuture));
        return emitter;
    }

    /**
     * 主控单轮执行核心（前端发起轮与系统唤醒轮共用）。
     *
     * @param direct     请求级 SSE（前端发起轮）；null=无头轮（系统唤醒）
     * @param systemWake 无头轮标志：事件发会话总线（前端常驻订阅实时回显），并先发 system.wake
     */
    private void runMasterTurn(AgentSpec spec, String wsId, String stage, int iteration, String masterAgent,
                               String query, SseEmitter direct, boolean systemWake, String wakeReason) {
        String busKey = StageChatEventBus.key(wsId, stage, iteration);
        // 过程留档累积：思考全文 / 工具调用序列 / 最终回复——流结束后落 docs/{stage}/iter{n}/，
        // 历史回放可查（agent_state 只存对话正文，思考与工具轨迹只在这里有）
        StringBuilder thinkingBuf = new StringBuilder();
        StringBuilder replyBuf = new StringBuilder();
        List<String[]> toolRows = new java.util.ArrayList<>(); // {tool, state}
        String[] replyFromResult = {null};
        try {
            if (systemWake) {
                eventBus.publish(busKey, "system.wake", Map.of("reason", String.valueOf(wakeReason)));
            }
            sendOrPublish(direct, busKey, systemWake, "agent.start", Map.of("agent", spec.name()));
            runtime.stream(runtime.createAgent(spec), new AgentMessage(query, null, null), evt -> {
                Map<String, Object> payload = payloadOf(evt);
                String type = evt.type();
                if ("thinking.delta".equals(type)) {
                    Object d = payload.get("delta");
                    if (d != null) {
                        thinkingBuf.append(d);
                    }
                } else if ("agent.delta".equals(type)) {
                    Object d = payload.get("delta");
                    if (d != null) {
                        replyBuf.append(d);
                    }
                } else if ("agent.result".equals(type)) {
                    Object txt = payload.get("text");
                    if (txt != null && !String.valueOf(txt).isBlank()) {
                        replyFromResult[0] = String.valueOf(txt);
                    }
                } else if ("tool.call".equals(type)) {
                    toolRows.add(new String[]{String.valueOf(payload.getOrDefault("tool", "?")), "执行中"});
                } else if ("tool.result".equals(type)) {
                    String tool = String.valueOf(payload.getOrDefault("tool", "?"));
                    String state = String.valueOf(payload.getOrDefault("state", "DONE"));
                    for (int i = toolRows.size() - 1; i >= 0; i--) {
                        if (toolRows.get(i)[0].equals(tool) && "执行中".equals(toolRows.get(i)[1])) {
                            toolRows.get(i)[1] = state;
                            break;
                        }
                    }
                }
                sendOrPublish(direct, busKey, systemWake, type, payload);
            });
            saveChatLog(wsId, stage, iteration, masterAgent, query, thinkingBuf.toString(),
                    toolRows, replyFromResult[0] != null ? replyFromResult[0] : replyBuf.toString());
            sendOrPublish(direct, busKey, systemWake, "stage.finish", Map.of("status", "SUCCESS"));
            if (direct != null) {
                direct.complete();
            }
            log.info("orchestrator chat finished: ws={} stage={} agent={} systemWake={}", wsId, stage, masterAgent, systemWake);
        } catch (Exception e) {
            log.warn("orchestrator chat failed: ws={} stage={} -> {}", wsId, stage, e.getMessage());
            saveChatLog(wsId, stage, iteration, masterAgent, query, thinkingBuf.toString(),
                    toolRows, replyFromResult[0] != null ? replyFromResult[0] : replyBuf.toString());
            try {
                sendOrPublish(direct, busKey, systemWake, "stage.finish",
                        Map.of("status", "FAILED", "error", String.valueOf(e.getMessage())));
                if (direct != null) {
                    direct.complete();
                }
            } catch (Exception ignore) {
                if (direct != null) {
                    direct.completeWithError(e);
                }
            }
        }
    }

    /** 事件出口：前端发起轮走请求级 SSE（不进总线，避免同轮事件双路重复渲染）；系统唤醒轮走总线。 */
    private void sendOrPublish(SseEmitter direct, String busKey, boolean systemWake, String event, Object payload) {
        if (direct != null) {
            send(direct, event, payload);
        } else if (systemWake) {
            eventBus.publish(busKey, event, payload);
        }
    }

    /**
     * 批次收敛唤醒：步骤落终态后由执行器（TaskRunner/DiscussionEngine）调用——
     * 该会话已无 EXECUTING 步骤时唤醒主控继续推进。后端是运行唯一真相：
     * 唤醒不依赖前端在线；回复落 agent_state，前端在线经总线实时回显，离线回放兜底。
     */
    public void wakeIfSettled(String wsId, String stage, int iteration, String reason) {
        try {
            boolean anyExecuting = stepRepo.findByStage(wsId, stage, iteration).stream()
                    .anyMatch(s -> s.status() == StepStatus.EXECUTING);
            if (anyExecuting) {
                log.info("唤醒跳过（仍有执行中步骤，等最后一个落板时唤醒）: ws={} {}#{} reason={}",
                        wsId, stage, iteration, reason);
                return;
            }
            wakeMaster(wsId, stage, iteration, reason);
        } catch (Exception e) {
            log.warn("系统唤醒检查失败: ws={} {}#{} -> {}", wsId, stage, iteration, e.getMessage());
        }
    }

    /**
     * 系统唤醒主控一轮（无头模式）：以项目创建人身份构造系统消息（[系统唤醒] 前缀，
     * agent_state 回放可识别、前端渲染成系统样式区别用户输入）；事件发总线实时回显。
     * wakeGuard 原子认领防双唤醒（两个任务几乎同时落板只放行一个）。
     */
    /**
     * AutoPilot 续跑判定（2026-10-19「一套完整的可自主完成所有流程的」）：主控轮结束后检查黑板——
     * 还有 PLANNED 步骤且无执行中/等待中步骤时，系统唤醒主控继续执行，直到阶段内无待办。
     *
     * <p>防失控三闸：①有 EXECUTING/WAITING 步不续（正在干/等用户决策）；②黑板指纹与上轮
     * 相同（零进展）熔断；③单会话续跑超 {@link #AUTOPILOT_MAX_ROUNDS} 轮熔断。
     * 阶段已推进（complete_stage 已执行）时不续——下一阶段由 autoStartStage 接管。</p>
     */
    private void autoPilot(String wsId, String stage, int iteration) {
        try {
            // 阶段已收口推进：下一阶段由 complete_stage 的 stageAutoStart 钩子接管，本阶段不再续
            if (!stage.equals(currentStageOf(wsId))) {
                autoPilotRounds.remove(StageChatEventBus.key(wsId, stage, iteration));
                autoPilotLast.remove(StageChatEventBus.key(wsId, stage, iteration));
                return;
            }
            List<StageStep> steps = stepRepo.findByStage(wsId, stage, iteration);
            boolean busy = steps.stream().anyMatch(s ->
                    s.status() == StepStatus.EXECUTING || s.status() == StepStatus.WAITING);
            if (busy) {
                return; // 正在执行或等决策——不抢跑
            }
            // 决策步（PLANNED=已发出等答复）不算待办：决策是等用户的明确停点，
            // 把它当「未执行」唤醒主控只会让主控空转探索、甚至重复发出决策（2026-10-20 实锤）
            List<StageStep> planned = steps.stream()
                    .filter(s -> s.status() == StepStatus.PLANNED)
                    .filter(s -> s.stepType() != StepType.DECISION)
                    .toList();
            if (planned.isEmpty()) {
                return; // 只剩决策步（或无待办）=等用户，不空转
            }
            boolean decisionPending = steps.stream().anyMatch(s ->
                    s.stepType() == StepType.DECISION
                            && (s.status() == StepStatus.PLANNED || s.status() == StepStatus.WAITING));
            String key = StageChatEventBus.key(wsId, stage, iteration);
            // 零进展熔断：本轮黑板指纹与上轮相同 = 主控没干任何实事，续跑只会原样空转
            String fingerprint = steps.stream()
                    .map(s -> s.seq() + ":" + s.status()).sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            String last = autoPilotLast.put(key, fingerprint);
            int rounds = autoPilotRounds.merge(key, 1, Integer::sum);
            if ((last != null && last.equals(fingerprint)) || rounds > AUTOPILOT_MAX_ROUNDS) {
                autoPilotRounds.remove(key);
                autoPilotLast.remove(key);
                log.warn("AutoPilot 熔断（{}）: ws={} {}#{}", last != null && last.equals(fingerprint) ? "零进展" : "超轮数上限",
                        wsId, stage, iteration);
                return;
            }
            String names = planned.stream()
                    .map(s -> "#" + s.seq() + "「" + s.name() + "」(" + s.stepType() + ")")
                    .collect(java.util.stream.Collectors.joining("、"));
            String decisionNote = decisionPending
                    ? "注意：已有决策正在等待用户答复——不要重复发出决策、不要反复探索目录空转；"
                      + "先执行与决策无关的步骤，与决策相关的步骤等用户答复后再做。"
                    : "";
            wakeMaster(wsId, stage, iteration,
                    "黑板还有 " + planned.size() + " 个未执行步骤：" + names
                            + "。请连续执行（discuss/execute_step 逐个发起，做完一个立刻下一个，不要中途停下汇报），"
                            + "全部完成后 complete_stage 收口。" + decisionNote);
        } catch (Exception e) {
            log.warn("AutoPilot 判定失败: ws={} {}#{} -> {}", wsId, stage, iteration, e.getMessage());
        }
    }

    /** 阶段收口自动启动下一阶段（complete_stage 钩子，函数接口解耦注入 OrchestrationTools）。 */
    public void autoStartStage(String wsId, String nextStage) {
        wakeMaster(wsId, nextStage, 1,
                "已自动进入本阶段（上一阶段已收口）。请开始本阶段工作：先 plan_steps 规划"
                        + "（带 intent_anchor 与可判定 acceptance 断言），再逐项 discuss/execute_step 执行，"
                        + "全部完成后 complete_stage 收口。");
    }

    private void wakeMaster(String wsId, String stage, int iteration, String reason) {
        String busKey = StageChatEventBus.key(wsId, stage, iteration);
        if (wakeGuard.putIfAbsent(busKey, Boolean.TRUE) != null) {
            log.info("唤醒去重（该会话已有唤醒在跑）: key={} reason={}", busKey, reason);
            return;
        }
        try {
            Map<String, Object> ws = workspaceRepo.findById(wsId).stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));
            String owner = ws.get("created_by") == null || String.valueOf(ws.get("created_by")).isBlank()
                    ? "admin" : String.valueOf(ws.get("created_by"));
            // 系统身份：OrchestrationTools 只消费 username（需求登记创建人），其余字段按无权限主体构造
            LoginUser system = new LoginUser(null, owner, owner, null, false, false, "ALL", java.util.Set.of());
            String masterAgent = masterAgentOf(wsId, stage);
            String sessionId = io.xuanji.server.project.StageChatKeys.stageSession(wsId, stage, iteration);
            String projectGoal = str(ws.get("description"));
            String stageGoal = stageGoalOf(stage);
            String guidance = guidance(stageGoal, wsId, stage, iteration);

            OrchestrationTools tools = new OrchestrationTools(system, wsId, stage, iteration,
                    projectGoal, stageGoal, stepRepo, taskRunner, discussionEngine,
                    projectRepo, advancer, agentNodes, objectMapper, requirementRepo, peerConsult,
                    decisionSettler, projectEvents, testCaseRepo, bugRepo, deliverySettler,
                    this::autoStartStage);
            AgentSpec base = agentNodes.buildSpecByAgentName(masterAgent, sessionId, guidance, wsId);
            AgentSpec spec = withTools(base, tools.toolDefs());
            String query = "[系统唤醒] " + reason + "\n请基于以上情况继续推进。";
            java.util.concurrent.CompletableFuture<Void> wakeFuture = java.util.concurrent.CompletableFuture
                    .runAsync(() -> {
                        try {
                            runMasterTurn(spec, wsId, stage, iteration, masterAgent, query, null, true, reason);
                        } finally {
                            wakeGuard.remove(busKey);
                            autoPilot(wsId, stage, iteration);
                        }
                    }, chatExecutor);
            TaskRegistry.register(wsId, wakeFuture);
            wakeFuture.whenComplete((v, e) -> TaskRegistry.unregister(wsId, wakeFuture));
            log.info("系统唤醒已提交: ws={} {}#{} agent={} reason={}", wsId, stage, iteration, masterAgent, reason);
        } catch (Exception e) {
            wakeGuard.remove(busKey);
            log.warn("系统唤醒组装失败: ws={} {}#{} -> {}", wsId, stage, iteration, e.getMessage());
        }
    }

    /**
     * 项目删除时的运行时清理（2026-10-20 实锤补课）：终止该项目所有在跑任务
     * （主控轮/人工重试后台——interrupt 中断 LLM 调用），并清掉唤醒守卫与续跑轮次计数，
     * 防止删除后 autoPilot/wakeMaster 再唤醒已删除项目的轮次。
     *
     * @return 终止的在跑任务数（日志与删除响应可见）
     */
    public int purgeRuntimeState(String wsId) {
        int killed = TaskRegistry.terminateAll(wsId);
        wakeGuard.keySet().removeIf(k -> k.startsWith(wsId + ":"));
        autoPilotRounds.keySet().removeIf(k -> k.startsWith(wsId + ":"));
        log.info("项目运行时清理: ws={} 终止在跑任务 {} 个", wsId, killed);
        return killed;
    }

    /**
     * 对话过程留档（双落：docs/{stage}/iter{n}/{seq}-CHATLOG-{title}.md + DOC 索引）。
     * 思考与工具轨迹只存在于事件流，不落盘则历史回放永远看不到——留档失败只记日志不阻塞对话。
     */
    private void saveChatLog(String wsId, String stage, int iteration, String masterAgent,
                             String query, String thinking, List<String[]> toolRows, String reply) {
        try {
            if ((query == null || query.isBlank())
                    && thinking.isBlank() && toolRows.isEmpty() && (reply == null || reply.isBlank())) {
                return;
            }
            String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
            StringBuilder md = new StringBuilder();
            md.append("# 对话过程留档 · ").append(stage).append(" 第 ").append(iteration).append(" 轮\n\n");
            md.append("- 时间：").append(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            md.append("- 主控：").append(masterAgent).append("\n");
            md.append("- 用户：").append(query == null ? "" : query.replace("\n", " ")).append("\n\n");
            if (!thinking.isBlank()) {
                md.append("## 思考过程\n\n").append(thinking.strip()).append("\n\n");
            }
            if (!toolRows.isEmpty()) {
                md.append("## 工具调用\n\n| # | 工具 | 结果 |\n|---|------|------|\n");
                for (int i = 0; i < toolRows.size(); i++) {
                    md.append("| ").append(i + 1).append(" | ").append(toolRows.get(i)[0])
                            .append(" | ").append(toolRows.get(i)[1]).append(" |\n");
                }
                md.append("\n");
            }
            if (reply != null && !reply.isBlank()) {
                md.append("## 最终回复\n\n").append(reply.strip()).append("\n");
            }
            docStore.write(wsId, stage, iteration, "CHATLOG", Integer.parseInt(ts), "对话记录", md.toString(), masterAgent);
            // 实时性（2026-10-19）：对话留档落档即推送——否则留档 tab 要等下一个步骤事件才刷新
            projectEvents.publish(wsId, "artifact.updated", Map.of("stage", stage, "kind", "CHATLOG"));
        } catch (Exception e) {
            log.warn("对话过程留档失败（不阻塞）: ws={} stage={} -> {}", wsId, stage, e.getMessage());
        }
    }

    /**
     * 用户在项目区域（右侧决策卡）直接答复决策：DECISION 步置 DONE + 答复留档。
     * 与对话内 record_answer 同一落库形态——主控下一轮从步骤台账看到答复，无需特殊通知。
     * 有未处理决策时前端锁定对话输入，答复是唯一的解锁通道。
     */
    public Map<String, Object> answerDecision(LoginUser user, String wsId, Map<String, Object> body) {
        workspaceRepo.findById(wsId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));
        long seq = body.get("seq") == null ? -1
                : (body.get("seq") instanceof Number n ? n.longValue()
                        : Long.parseLong(String.valueOf(body.get("seq")).trim()));
        String answer = body.get("answer") == null ? "" : String.valueOf(body.get("answer")).trim();
        if (seq <= 0 || answer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seq 与 answer 必填");
        }
        StageStep found = stepRepo.findByStage(wsId, currentStageOf(wsId), currentIteration(wsId, currentStageOf(wsId)))
                .stream()
                .filter(s -> s.seq() == seq && s.stepType() == StepType.DECISION)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "决策序号不存在: " + seq));
        if (found.status() == StepStatus.DONE || found.status() == StepStatus.SKIPPED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该决策已处理（" + found.status() + "），无需重复答复");
        }
        stepRepo.updateStatus(found.withAnswer(answer));
        // 决策答复闭环（确定性联动，与 record_answer 工具同一条）：关联需求自动置 CONFIRMED
        //（source=decision#seq）+ 决策记录双落档——用户答复即确认，不依赖主控自觉调 confirm
        List<String> confirmed = decisionSettler.settle(found.withAnswer(answer), answer,
                user == null ? "user" : user.username());
        // 决策答复即唤醒：答复内容直接进唤醒消息（不依赖主控主动查台账），主控立即继续推进。
        // 后端是运行唯一真相——唤醒不依赖前端在线，前端经总线实时回显。
        String stage = currentStageOf(wsId);
        wakeMaster(wsId, stage, currentIteration(wsId, stage),
                "决策 #" + seq + "「" + found.name() + "」已答复：" + answer
                        + (confirmed.isEmpty() ? "" : "（关联需求已自动确认: " + String.join("、", confirmed) + "）"));
        return Map.of("ok", true, "seq", seq,
                "message", "决策已答复并留档" + (confirmed.isEmpty() ? "" : "；关联需求已自动确认: " + String.join("、", confirmed)));
    }

    /**
     * 讨论进行中用户插话（用户可参与讨论）：发言以【用户】前缀追加进讨论流，
     * 后续轮发言者与汇总人从台账读到（汇总 prompt 已注明用户意见优先采纳）。
     * 仅 EXECUTING 的 DISCUSS/CONSULT 步可插话——讨论结束后走正常对话。
     */
    public Map<String, Object> discussSpeech(LoginUser user, String wsId, Map<String, Object> body) {
        workspaceRepo.findById(wsId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));
        long seq = body.get("seq") == null ? -1
                : (body.get("seq") instanceof Number n ? n.longValue()
                        : Long.parseLong(String.valueOf(body.get("seq")).trim()));
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text")).trim();
        if (seq <= 0 || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seq 与 text 必填");
        }
        if (text.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发言过长（上限 2000 字）");
        }
        StageStep found = stepRepo.findByStage(wsId, currentStageOf(wsId), currentIteration(wsId, currentStageOf(wsId)))
                .stream()
                .filter(s -> s.seq() == seq)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "步骤序号不存在: " + seq));
        if (found.stepType() != StepType.DISCUSS && found.stepType() != StepType.CONSULT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该步骤不是讨论/求助，无法插话");
        }
        if (found.status() != StepStatus.EXECUTING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "讨论已结束（" + found.status() + "），请直接在对话里说");
        }
        stepRepo.appendSpeech(found.id(), "【用户】" + text + "\n\n");
        return Map.of("ok", true, "seq", seq, "message", "发言已加入讨论");
    }

    /** 当前阶段（决策答复按当前阶段台账找 DECISION 步）。 */
    private String currentStageOf(String wsId) {
        Map<String, Object> ws = workspaceRepo.findById(wsId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));
        return String.valueOf(ws.get("current_stage"));
    }

    private int currentIteration(String wsId, String stage) {
        return stepRepo.findByWorkspace(wsId).stream()
                .filter(s -> stage.equals(s.stage()))
                .mapToInt(StageStep::iteration).max().orElse(1);
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
        sb.append("【你的角色：流程责任人，不是质量责任人】\n");
        sb.append("【专业交付纪律（告知全体执行角色）】你的团队是专业人士，按专业标准交付，不是敷衍交差：tester 交付的是真跑过的测试（shell 断言命令真实执行、结果真实回写），不是「应该能过」；developer 交付的是生产级代码（完整实现+自测通过），不是示例片段；architect 交付的是可实施的设计（照着能开工），不是概念罗列；devops 交付的是执行留档（命令+输出+结论），不是「已完成」三个字。验收链是专业流程的一部分：每步产出都会被验收人（机器断言/用户）检验，敷衍产出会被打回重做——打回浪费的是整个流程的时间，一次专业交付比三次返工快得多。\n");
        
        sb.append("你对流程与验收链的完整性负责：规划时给每步声明验收人（acceptance_owner）、执行后消化产出、验收链走完才收口。内容质量由验收人把关：tester 用 shell 断言跑测试验代码、developer 按架构设计开工（开不出工=设计不合格，打回 architect）、用户验收关键交付。你是第一道质量筛子：消化产出时发现敷衍（架构文档空洞、代码是示例、需求只有关键点没有用户故事与验收标准）——当场打回重做（execute_step 重拾，instruction 写明整改要求），禁止照单收口；收口前自查：本阶段关键交付是否都有非 self 验收（tool/human）？没有就补验收或发起阶段交付验收决策。\n");
        sb.append("你是本阶段的主控。用户只负责说话，流程由你决定：做什么、顺序、谁能跳。\n");
        String anchor = stepRepo.findAnchorText(wsId, stage, iteration);
        if (anchor != null && !anchor.isBlank()) {
            sb.append("【意图锚（规划时锁定，不可偏离）】").append(anchor).append("\n");
            sb.append("每一步的产出都必须对照意图锚自查：还在为同一个目标服务吗？发现偏离立即纠偏或申报。\n");
        }
        sb.append("【阶段目标】").append(stageGoal).append("\n");
        sb.append("【工作方式】\n");
        sb.append("1. 阶段开始或需求变化时，先用 plan_steps 规划小流程（TASK=产出型，DISCUSS=讨论型）；规划必须携带 intent_anchor（全局意图与总体验收口径），并为每个 TASK 步给出可判定的 acceptance 断言（artifact=产物存在性核对 / shell=真跑命令核对输出（测试/构建/健康检查）/ test / api，禁止「质量良好」这类不可判定描述），且必须声明 acceptance_owner（验收人）：\"tool\"=平台机器验收（产物断言/验证命令），\"human\"=用户验收（关键交付必用：PRD、架构设计、上线报告）——缺 acceptance_owner 的 TASK 步会被拒绝规划；\n");
        sb.append("   【规划质量纪律——任务卡质量=执行质量的上限】\n");
        sb.append("   规划实施类工作前必须先有设计依据（全阶段适用）：DEVELOP 阶段第一个任务应是「架构设计与技术拆解」（派 architect），产出写盘 docs/ 并登记交付物，内容必须覆盖：①整体架构（模块划分、前后端职责边界、目录结构）；②接口契约与数据结构（前后端交互的 API/存储格式定稿）；③逐需求技术方案——每个需求点怎么做：实现方式、技术难点与对策、拆分出的任务清单（这是 plan_steps 的直接依据）；REQUIREMENT 阶段的 PRD 任务要按需求点组织（每条需求：用户故事、验收标准、边界与例外）；OPERATE 阶段的部署/变更任务要有执行方案（步骤、回滚方式、风险点）。后续实现任务的 instruction 必须引用设计文档（如「按 docs/DEVELOP/architecture-design.md 中 REQ-003 的实现方式…」），禁止跳过设计直接散拆执行任务。\n");
        sb.append("   每个 TASK 的 instruction 是给执行者的完整任务卡，四要素缺一不可：①做什么（交付物清单，精确到文件路径）；②技术方案（怎么做：关键函数/接口签名、数据结构、用哪个既有模块）；③依赖与上下文（依赖哪些已有文件/前序产出，执行者应先读什么）；④验收口径（与 acceptance 一致）。禁止一句话任务——「创建 src/store.js」这种没有 how 的任务，执行者只能瞎写。\n");
        sb.append("   【机器验收】TASK 完成时平台按 acceptance 断言逐条核对：artifact=产物存在且非空（target 必须写真实产物相对路径，如 src/store.js，写了就一定会被核对）；shell=在项目根目录真跑 command 核对输出含 expect_contains（测试/构建/健康检查，超时 120s）——tester 验收代码就用 shell 断言跑测试命令。验收不过任务直接 FAILED、你会拿到失败明细。【用户验收】acceptance_owner=\"human\" 的步骤执行完停在 WAITING_ACCEPT，由用户在验收卡通过/打回——通过才 DONE（verified_by=human），打回会带原因回到你手里组织重做。关键交付（PRD/架构设计/上线报告）必须 human 验收，不要全用 tool（机器只查存在性，查不出内容好坏）。\n");
        sb.append("2. 需求先行：实施工作开始前必须先有确认的需求项——讨论沉淀的需求用 declare_requirements 落档（PROPOSED），再用 request_decision 请用户确认（CONFIRMED）；规划步骤时为每步声明 requirement_codes（为了哪个需求）——只挂该步直接服务的需求，禁止把全部需求挂到每个步骤：全局性步骤（架构设计、测试执行、发布、环境准备这类不专属某条需求的）留空不挂，专属步骤只挂直接相关的一两条；全挂会让追溯图每条需求链长一样，链路失去区分度；discuss 发起讨论与 ask_peer 求助同样必带 requirement_codes（本次讨论/澄清涉及哪些需求），需求看板据此留痕并统计每个需求被讨论/澄清的次数；被依赖需求未 DONE 前，不要为依赖它的需求排实施步骤（可以先排讨论型步骤）；运行中用户提出新需求，同样走 declare_requirements → 用户确认 → 并入规划；任何阶段（含 OPERATE 运维）都适用——写代码、改配置、执行变更、数据修复都是需求，先落项再动手，禁止「边聊边做」绕过需求台账；\n");
        sb.append("3. 澄清/共识类的事直接在对话里完成（秒级），不要做成任务；\n");
        sb.append("   【决策纪律】凡是需要用户明确答复的问题（征询同意、二选一、确认需求等），必须先调用 request_decision 工具留档，再在回复正文里展开问题内容；涉及需求口径确认的决策必带 requirement_codes（关联哪些需求项）——用户答复后系统自动把关联需求置 CONFIRMED 并生成决策记录，不需要你再调 declare_requirements confirm；禁止只在文本里说「已发出决策 #N」而不调用工具——没有工具调用就没有决策留痕，用户在阶段执行台账里看不到，事后也无法追溯。答复入口就是对话本身：告诉用户「直接回复即可」，不要让用户去找「右侧决策区」（不存在这个区域）；\n");
        sb.append("4. 讨论由你主持（主席模型）：discuss 发起（participants 按问题域拉人，串行发言——每人能看到前面所有人的观点并补充），发言全文直接回到你手里；然后你判断：观点够了就 discuss_close(你的主席总结：共识/分歧/待办/OPEN_QUESTION) 收口落档；不够就 discuss_more 追加（participants 传多人=再来一轮，传一人=@定向——用户 @某人提问或某发言者提出问题时，让能解答的角色结合讨论现场回答）；讨论未收口（EXECUTING）前不要推进其他步骤；用户在讨论中的插话会进入发言流，后续发言者自然看到；\n");
        sb.append("5. 有明确产出的工作用 execute_step 同步执行：阻塞到完成，产出直接回到你手里——当场消化再决定下一步；失败原因也直接返回，必须立即决策：重试（再调一次）、换路（declare_deviation）或跳过（skip_step），禁止停摆等用户；instruction 必须包含该步的 acceptance 验收口径，让执行者知道「什么算做完」；实现类任务必带 requirement_codes（本任务服务哪些需求项，只挂直接服务的，禁止全挂）——需求门禁会拒绝派发未确认（PROPOSED）需求的实现任务，被拦时先 request_decision 征询用户确认（答复后自动确认），确属无需确认的探索性工作才用 requirement_gate_skip=true + reason 显式豁免（留档用户可见）；执行中遇到需求/架构疑问，执行 agent 会通过 ask_peer 向对应角色求助（问答留档，你可见）——发现求助暴露需求矛盾时，用 request_decision 升级给用户；\n");
        sb.append("   【失败自主处置】同一任务重试最多 2 次，每次重试的 instruction 必须带上一次失败的原因与本轮调整点（原样重发=再失败一次）；2 次仍失败、或判断超出你能力范围（环境缺失/需求矛盾/需要用户拍板）才 request_decision 升级——升级时说清已试过什么、卡在哪、你建议怎么办。对 FAILED/SKIPPED 的同名步骤重新 execute_step 会原地重拾（seq 不变，追溯图节点原地转绿），不会堆新节点。\n");
        sb.append("   【连续执行纪律】你是流程的发动机，不是问答机：干完一个任务立刻消化产出并派下一个，禁止干完一步就停下来等用户说话——黑板还有 PLANNED 步骤就继续执行，全部完成就 complete_stage 收口（收口后系统自动启动下一阶段）。只有需要用户拍板（request_decision）或升级（需求矛盾/环境缺失）时才允许流程停在你这里。系统会在你停摆时自动唤醒你继续，但不要依赖兜底——主动连续推进是你的职责。\n");
        sb.append("6. 判断某个小流程不值得做时用 skip_step 跳过，必须写明理由；\n");
        sb.append("7. 执行中发现某步无法按计划完成（技术障碍/依赖缺失/成本超限），必须先用 declare_deviation 申报偏差（原因+替代方案），禁止静默换路、静默降级或悄悄缩小范围；降级影响交付口径时继续用 request_decision 征询用户；\n");
        sb.append("8. 需要用户拍板时用 request_decision；用户在对话里答复后用 record_answer 落档；\n");
        sb.append("9. 阶段职责完成（无执行中/等待中/待验收的小流程）时用 complete_stage 收口推进；收口前自查验收链：关键交付都已通过验收（tool/human），本阶段没有任何用户验收过的交付时系统会自动发起「阶段交付验收」决策、用户答复后才能收口；收口总结必须如实列出未达成/降级/跳过的项，禁止把未验证的说成已完成；\n");
        sb.append("10. 所有 agent 参数（agent/participants/summarizer）一律用英文名（agent_config.name，如 project_manager、product_analyst），禁止用中文显示名；\n");
        sb.append("11. plan_steps 只规划不执行——规划里登记的 DISCUSS/TASK 步骤由你后续逐个用 discuss/execute_step 发起，不要规划后又立刻重复发起同一件事。\n");
        sb.append("【团队分工与转介（职责外不硬答）】\n");
        sb.append("团队成员与职责边界：project_manager=整体规划/进度/风险协调（你是本阶段主控）；product_analyst=需求调研/澄清/PRD/验收标准；architect=系统架构/技术选型/接口契约/技术方案；developer=代码实现/单元测试/代码评审；tester=测试用例/测试执行/缺陷管理；devops=部署/发布/监控/环境/线上问题；dba=数据库设计/SQL/数据迁移/慢查询优化。\n");
        sb.append("转介纪律：职责外的问题不硬答、不代做——拉对角色。判断标准：谁对结果负专业责任就找谁——需求口径找 product_analyst，写代码找 developer，方案/接口找 architect，数据库找 dba，环境/部署/服务器找 devops，测试验收找 tester，整体计划与进度是你自己（project_manager）的事。\n");
        sb.append("转介通道：本阶段内多视角讨论用 discuss（participants 按问题域拉人，你主持并收口总结）；给明确产出的活用 execute_step 派给对应角色；跨阶段用 ask_stage（如 REQUIREMENT 请 devops 确认服务器现状、DEVELOP 请 product_analyst 澄清需求，每轮上限 5 次）。拉人时给足上下文（问题、背景、期望产出），不要甩一句「你看看」。派活时在 instruction 里写明专业标准与验收口径（什么算做完、按什么标准检验）——执行者按专业标准交付，验收人按同一口径检验。\n");
        sb.append("【测试与缺陷纪律（交付图数据源）】\n");
        sb.append("测试设计产出用例时必须调 declare_test_cases 落档（每条用例挂 requirement_code），禁止只在回复文本里列用例——不落档交付图上看不见，需求无法机器判定完成。\n");
        sb.append("测试执行后必须调 settle_test_cases 回写每条用例结果（PASSED/FAILED/BLOCKED）——需求下全部用例 PASSED 且无未了结 bug 时该需求自动判定 DONE；存在未通过用例时已 DONE 的需求会被拉回 IN_PROGRESS（假完成会被暴露）。\n");
        sb.append("发现缺陷必须调 report_bug 上报（挂 requirement_code 与 test_case_code，severity P0-P3），禁止只在回复文本里说发现 bug；修复流转用 settle_bugs（FIXING/FIXED/CLOSED/REJECTED）。\n");
        sb.append("需求拆分子需求时用 declare_requirements 的 parent_code 声明父子关系（原始需求→拆分点），交付图据此画层级。\n");
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
            // 未收口讨论显式提醒：主席职责——追加/@定向/总结收口由主控决定
            if (s.status() == StepStatus.EXECUTING && s.stepType() == StepType.DISCUSS) {
                sb.append("  ⚠ 讨论「").append(s.name())
                        .append("」进行中待收口：用 discuss_more 追加发言/@定向，或 discuss_close(主席总结) 收口落档。\n");
            }
        }
        return sb.length() == 0 ? "（空——本阶段还没有任何规划）" : sb.toString();
    }

    /** 重建 spec：把编排工具（BEAN 形态）追加进 resolvedTools。 */
    private AgentSpec withTools(AgentSpec base, List<ToolDef> tools) {
        List<ToolDef> merged = new ArrayList<>(base.resolvedTools() == null ? List.of() : base.resolvedTools());
        merged.addAll(tools);
        // 全参构造：透传批六新增的内置工具上下文（builtinTools/workspaceRoot/workspaceId），
        // 旧 10 参兼容构造器会把这三项置空，导致主控对话丢失内置工具
        return new AgentSpec(base.agentConfigId(), base.name(), base.systemPrompt(), base.model(),
                base.tools(), base.permission(), base.runtime(), base.sessionId(),
                merged, base.skills(), base.builtinTools(), base.workspaceRoot(), base.workspaceId());
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
