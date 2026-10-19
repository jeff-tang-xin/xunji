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

    /** SSE 会话执行池：虚拟线程（JDK21），阻塞 IO 型任务不占平台线程，并发对话不再互相排队。 */
    private final ExecutorService chatExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 系统唤醒防重闸：会话键 → 唤醒中。两个任务几乎同时落板时只放行一个唤醒（putIfAbsent 原子认领）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> wakeGuard =
            new java.util.concurrent.ConcurrentHashMap<>();

    public StageOrchestrator(AgentRuntime runtime, AgentNodeExecutor agentNodes,
                             StageStepRepository stepRepo, TaskRunner taskRunner,
                             DiscussionEngine discussionEngine,
                             io.xuanji.server.repository.ProjectRepository projectRepo,
                             WorkspaceRepository workspaceRepo, StageAdvancer advancer,
                             ObjectMapper objectMapper, RequirementRepository requirementRepo,
                             PeerConsultService peerConsult, StageDocStore docStore,
                             StageChatEventBus eventBus, DecisionSettleService decisionSettler,
                             ProjectEventBus projectEvents) {
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
                decisionSettler, projectEvents);

        AgentSpec base = agentNodes.buildSpecByAgentName(masterAgent, sessionId, guidance, wsId);
        AgentSpec spec = withTools(base, tools.toolDefs());

        // 30 分钟（2026-10-17 同步化）：主控工具调用内同步等讨论/任务做完（无超时），
        // 单请求可达 20 分钟+；900s 会把还在跑的对话掐断，前端只看到流静默断开
        SseEmitter emitter = new SseEmitter(1_800_000L);
        chatExecutor.submit(() -> runMasterTurn(spec, wsId, stage, iteration, masterAgent, query, emitter, false, null));
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
                    decisionSettler, projectEvents);
            AgentSpec base = agentNodes.buildSpecByAgentName(masterAgent, sessionId, guidance, wsId);
            AgentSpec spec = withTools(base, tools.toolDefs());
            String query = "[系统唤醒] " + reason + "\n请基于以上情况继续推进。";
            chatExecutor.submit(() -> {
                try {
                    runMasterTurn(spec, wsId, stage, iteration, masterAgent, query, null, true, reason);
                } finally {
                    wakeGuard.remove(busKey);
                }
            });
            log.info("系统唤醒已提交: ws={} {}#{} agent={} reason={}", wsId, stage, iteration, masterAgent, reason);
        } catch (Exception e) {
            wakeGuard.remove(busKey);
            log.warn("系统唤醒组装失败: ws={} {}#{} -> {}", wsId, stage, iteration, e.getMessage());
        }
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
            docStore.write(wsId, stage, iteration, "CHATLOG", Integer.parseInt(ts), "对话" + ts, md.toString(), masterAgent);
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
        sb.append("你是本阶段的主控。用户只负责说话，流程由你决定：做什么、顺序、谁能跳。\n");
        String anchor = stepRepo.findAnchorText(wsId, stage, iteration);
        if (anchor != null && !anchor.isBlank()) {
            sb.append("【意图锚（规划时锁定，不可偏离）】").append(anchor).append("\n");
            sb.append("每一步的产出都必须对照意图锚自查：还在为同一个目标服务吗？发现偏离立即纠偏或申报。\n");
        }
        sb.append("【阶段目标】").append(stageGoal).append("\n");
        sb.append("【工作方式】\n");
        sb.append("1. 阶段开始或需求变化时，先用 plan_steps 规划小流程（TASK=产出型，DISCUSS=讨论型）；规划必须携带 intent_anchor（全局意图与总体验收口径），并为每个 TASK 步给出可判定的 acceptance 断言（test/artifact/api 三类，禁止「质量良好」这类不可判定描述）；\n");
        sb.append("2. 需求先行：实施工作开始前必须先有确认的需求项——讨论沉淀的需求用 declare_requirements 落档（PROPOSED），再用 request_decision 请用户确认（CONFIRMED）；规划步骤时为每步声明 requirement_codes（为了哪个需求）；discuss 发起讨论与 ask_peer 求助同样必带 requirement_codes（本次讨论/澄清涉及哪些需求），需求看板据此留痕并统计每个需求被讨论/澄清的次数；被依赖需求未 DONE 前，不要为依赖它的需求排实施步骤（可以先排讨论型步骤）；运行中用户提出新需求，同样走 declare_requirements → 用户确认 → 并入规划；任何阶段（含 OPERATE 运维）都适用——写代码、改配置、执行变更、数据修复都是需求，先落项再动手，禁止「边聊边做」绕过需求台账；\n");
        sb.append("3. 澄清/共识类的事直接在对话里完成（秒级），不要做成任务；\n");
        sb.append("   【决策纪律】凡是需要用户明确答复的问题（征询同意、二选一、确认需求等），必须先调用 request_decision 工具留档，再在回复正文里展开问题内容；涉及需求口径确认的决策必带 requirement_codes（关联哪些需求项）——用户答复后系统自动把关联需求置 CONFIRMED 并生成决策记录，不需要你再调 declare_requirements confirm；禁止只在文本里说「已发出决策 #N」而不调用工具——没有工具调用就没有决策留痕，用户在阶段执行台账里看不到，事后也无法追溯。答复入口就是对话本身：告诉用户「直接回复即可」，不要让用户去找「右侧决策区」（不存在这个区域）；\n");
        sb.append("4. 讨论由你主持（主席模型）：discuss 发起（participants 按问题域拉人，串行发言——每人能看到前面所有人的观点并补充），发言全文直接回到你手里；然后你判断：观点够了就 discuss_close(你的主席总结：共识/分歧/待办/OPEN_QUESTION) 收口落档；不够就 discuss_more 追加（participants 传多人=再来一轮，传一人=@定向——用户 @某人提问或某发言者提出问题时，让能解答的角色结合讨论现场回答）；讨论未收口（EXECUTING）前不要推进其他步骤；用户在讨论中的插话会进入发言流，后续发言者自然看到；\n");
        sb.append("5. 有明确产出的工作用 execute_step 同步执行：阻塞到完成，产出直接回到你手里——当场消化再决定下一步；失败原因也直接返回，必须立即决策：重试（再调一次）、换路（declare_deviation）或跳过（skip_step），禁止停摆等用户；instruction 必须包含该步的 acceptance 验收口径，让执行者知道「什么算做完」；实现类任务必带 requirement_codes（本任务服务哪些需求项）——需求门禁会拒绝派发未确认（PROPOSED）需求的实现任务，被拦时先 request_decision 征询用户确认（答复后自动确认），确属无需确认的探索性工作才用 requirement_gate_skip=true + reason 显式豁免（留档用户可见）；执行中遇到需求/架构疑问，执行 agent 会通过 ask_peer 向对应角色求助（问答留档，你可见）——发现求助暴露需求矛盾时，用 request_decision 升级给用户；\n");
        sb.append("6. 判断某个小流程不值得做时用 skip_step 跳过，必须写明理由；\n");
        sb.append("7. 执行中发现某步无法按计划完成（技术障碍/依赖缺失/成本超限），必须先用 declare_deviation 申报偏差（原因+替代方案），禁止静默换路、静默降级或悄悄缩小范围；降级影响交付口径时继续用 request_decision 征询用户；\n");
        sb.append("8. 需要用户拍板时用 request_decision；用户在对话里答复后用 record_answer 落档；\n");
        sb.append("9. 阶段职责完成（无执行中/等待中的小流程）时用 complete_stage 收口推进；收口总结必须如实列出未达成/降级/跳过的项，禁止把未验证的说成已完成；\n");
        sb.append("10. 所有 agent 参数（agent/participants/summarizer）一律用英文名（agent_config.name，如 project_manager、product_analyst），禁止用中文显示名；\n");
        sb.append("11. plan_steps 只规划不执行——规划里登记的 DISCUSS/TASK 步骤由你后续逐个用 discuss/execute_step 发起，不要规划后又立刻重复发起同一件事。\n");
        sb.append("【团队分工与转介（职责外不硬答）】\n");
        sb.append("团队成员与职责边界：project_manager=整体规划/进度/风险协调（你是本阶段主控）；product_analyst=需求调研/澄清/PRD/验收标准；architect=系统架构/技术选型/接口契约/技术方案；developer=代码实现/单元测试/代码评审；tester=测试用例/测试执行/缺陷管理；devops=部署/发布/监控/环境/线上问题；dba=数据库设计/SQL/数据迁移/慢查询优化。\n");
        sb.append("转介纪律：职责外的问题不硬答、不代做——拉对角色。判断标准：谁对结果负专业责任就找谁——需求口径找 product_analyst，写代码找 developer，方案/接口找 architect，数据库找 dba，环境/部署/服务器找 devops，测试验收找 tester，整体计划与进度是你自己（project_manager）的事。\n");
        sb.append("转介通道：本阶段内多视角讨论用 discuss（participants 按问题域拉人，你主持并收口总结）；给明确产出的活用 execute_step 派给对应角色；跨阶段用 ask_stage（如 REQUIREMENT 请 devops 确认服务器现状、DEVELOP 请 product_analyst 澄清需求，每轮上限 5 次）。拉人时给足上下文（问题、背景、期望产出），不要甩一句「你看看」。\n");
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
