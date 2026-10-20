package io.xuanji.server.project.orchestrator;

import io.xuanji.core.api.Tool;
import io.xuanji.core.api.ToolMetadata;
import io.xuanji.core.api.ToolRequest;
import io.xuanji.core.api.ToolResult;
import io.xuanji.core.runtime.ToolDef;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.repository.ProjectRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话级编排工具集——主控 agent 的「动词」，AI 驱动内核的执行原语。
 *
 * <p><b>生命周期</b>：每次主控对话构造一个实例（携带 user/ws/stage/iteration 上下文），
 * 会话结束即弃——工具实例有上下文但无跨请求状态，编排状态全部落黑板
 * （StageStepRepository），多项目并发安全（无状态纪律）。</p>
 *
 * <p><b>装配方式</b>：ToolDef sourceType=BEAN、payload=Tool 实例——
 * AgentScopeRuntime 按 BEAN 分支包装为 AgentTool，只挂给主控，不污染全局 Tool SPI。</p>
 *
 * <p>七个原语：plan_steps（规划）/ discuss（发起讨论）/ execute_step（派任务）/
 * skip_step（跳过留档）/ request_decision（向用户征询）/ record_answer（落用户答复）/
 * complete_stage（收口推进）。领域能力（查库/SSH 等）不经这里——走阶段资源绑定展开。</p>
 */
public class OrchestrationTools {

    /** 黑板条目留痕时间格式（谁记录的 · 什么时间 · 是否完结）。 */
    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final LoginUser user;
    private final String wsId;
    private final String stage;
    private final int iteration;
    private final String projectGoal;
    private final String stageGoal;

    private final StageStepRepository stepRepo;
    private final TaskRunner taskRunner;
    private final DiscussionEngine discussionEngine;
    private final ProjectRepository projectRepo;
    private final StageAdvancer advancer;
    private final io.xuanji.server.engine.AgentNodeExecutor agentNodes;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final RequirementRepository requirementRepo;
    private final PeerConsultService peerConsult;
    private final DecisionSettleService decisionSettler;
    private final ProjectEventBus projectEvents;
    private final TestCaseRepository testCaseRepo;
    private final BugRepository bugRepo;
    private final DeliverySettleService deliverySettler;
    /** 阶段收口自动续跑钩子（StageOrchestrator::autoStartStage，函数接口解耦避免循环依赖）：
     * complete_stage 成功后自动启动下一阶段首轮主控对话——流程不断链。 */
    private final java.util.function.BiConsumer<String, String> stageAutoStart;

    /** 需求编号形态（REQ-001）：requirement_codes 漏传时从决策/任务文本兜底提取。 */
    private static final Pattern REQ_CODE = Pattern.compile("\\b(REQ-\\d+)\\b");

    /** 阶段兜底智能体（与 ProjectChatService.STAGE_DEFAULT_AGENT 同口径）：跨阶段求助按目标阶段选人。 */
    private static final Map<String, String> STAGE_DEFAULT_AGENT = Map.of(
            "REQUIREMENT", "product_analyst",
            "DEVELOP", "architect",
            "OPERATE", "devops");

    /** 跨阶段求助每轮上限（防主控把支援当外包，自己的活不干）。 */
    private static final int MAX_ASK_STAGE_PER_ITERATION = 5;

    private static final List<String> STAGES = List.of("REQUIREMENT", "DEVELOP", "OPERATE");

    public OrchestrationTools(LoginUser user, String wsId, String stage, int iteration,
                              String projectGoal, String stageGoal,
                              StageStepRepository stepRepo, TaskRunner taskRunner,
                              DiscussionEngine discussionEngine, ProjectRepository projectRepo,
                              StageAdvancer advancer, io.xuanji.server.engine.AgentNodeExecutor agentNodes,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                              RequirementRepository requirementRepo, PeerConsultService peerConsult,
                              DecisionSettleService decisionSettler, ProjectEventBus projectEvents,
                              TestCaseRepository testCaseRepo, BugRepository bugRepo,
                              DeliverySettleService deliverySettler,
                              java.util.function.BiConsumer<String, String> stageAutoStart) {
        this.user = user;
        this.wsId = wsId;
        this.stage = stage;
        this.iteration = iteration;
        this.projectGoal = projectGoal;
        this.stageGoal = stageGoal;
        this.stepRepo = stepRepo;
        this.taskRunner = taskRunner;
        this.discussionEngine = discussionEngine;
        this.projectRepo = projectRepo;
        this.advancer = advancer;
        this.agentNodes = agentNodes;
        this.objectMapper = objectMapper;
        this.requirementRepo = requirementRepo;
        this.peerConsult = peerConsult;
        this.decisionSettler = decisionSettler;
        this.projectEvents = projectEvents;
        this.testCaseRepo = testCaseRepo;
        this.bugRepo = bugRepo;
        this.deliverySettler = deliverySettler;
        this.stageAutoStart = stageAutoStart;
    }

    /** 主控可见的全部编排工具（BEAN 形态 ToolDef）。 */
    public List<ToolDef> toolDefs() {
        List<ToolDef> defs = new ArrayList<>();
        defs.add(def("plan_steps", "规划本阶段的小流程清单（做什么、顺序、谁来做）。阶段开始或需求变化时调用；会替换尚未开始执行的既有规划。必须携带 intent_anchor（全局意图锚）并为每个 TASK 步给出可判定的 acceptance 验收断言。",
                planStepsSchema(), List.of("steps")));
        defs.add(def("discuss", "发起一场讨论并主持（你是主席）：参与者串行发言（每人能看到前面所有人的观点并补充），发言全文直接回到你手里。之后由你判断：观点够了用 discuss_close 收口，不够用 discuss_more 追加或 @定向提问。讨论未收口前保持进行中。",
                discussSchema(), List.of("name", "topic", "participants")));
        defs.add(def("discuss_more", "追加讨论发言（主席权柄）：participants 传多人=再来一轮串行发言；传一人=@定向——就 question（用户的问题或某发言者提出的问题）让该角色回答，回答会结合全部讨论现场。",
                discussMoreSchema(), List.of("seq", "participants")));
        defs.add(def("discuss_close", "主席收口：给出你的总结（共识/分歧/待办/OPEN_QUESTION），纪要=各方观点+你的主席总结，落档完成。讨论必须收口才能推进后续步骤。",
                discussCloseSchema(), List.of("seq", "summary")));
        defs.add(def("execute_step", "同步执行一个任务型小流程（产出登记为交付物）：阻塞到完成，产出直接回到你手里，当场消化再决定下一步；失败原因也直接返回——立即决策重试/换路/跳过。instruction 必须包含该步的 acceptance 验收口径；执行 agent 遇到疑问会通过 ask_peer 向对应角色求助，问答留档。",
                executeStepSchema(), List.of("name", "agent", "instruction")));
        defs.add(def("skip_step", "跳过某个未执行的小流程，必须写明理由（留档，用户可见）。",
                skipStepSchema(), List.of("seq", "reason")));
        defs.add(def("declare_deviation", "申报偏差：某步无法按计划完成、需要换路或降级时必须先申报（原因 + 替代方案），禁止静默换路。申报后继续用 request_decision 征询用户是否接受降级方案。",
                declareDeviationSchema(), List.of("seq", "reason", "fallback")));
        defs.add(def("declare_requirements", "需求先行：把讨论沉淀的需求项落库（PROPOSED），或在用户明确同意后置为 CONFIRMED。需求项是所有实施工作的锚定物——规划步骤前必须先有确认的需求；运行中新增需求也走这里（先 propose 再请用户确认）。confirm 动作必须先有用户明确同意（用 request_decision 征询并留档），禁止未经用户确认直接置 CONFIRMED。",
                declareRequirementsSchema(), List.of("action", "requirements")));
        defs.add(def("request_decision", "向用户提出决策请求（必须调用本工具留档，禁止只在回复文本里说已发出决策而不调用——没有工具调用就没有决策留痕，用户无处看到。调用后直接在回复正文里展开问题内容，并告诉用户：直接在对话输入框回复即可，答复入口就是对话本身）。",
                requestDecisionSchema(), List.of("question")));
        defs.add(def("record_answer", "把用户在对话里给出的答复落到对应决策上（决策闭环）。",
                recordAnswerSchema(), List.of("seq", "answer")));
        defs.add(def("complete_stage", "宣告本阶段完成并推进到下一阶段。前提：无执行中/等待中的小流程；收口总结会留档。交付物有缺口时必须先补齐或说明豁免理由。",
                completeStageSchema(), List.of("summary")));
        defs.add(def("declare_test_cases", "落测试用例（测试设计步骤必调）：用例挂需求项，需求交付树据此呈现「需求→用例→bug」链路。", declareTestCasesSchema(), List.of("cases")));
        defs.add(def("settle_test_cases", "回写用例执行结果（测试执行步骤必调）：PASSED/FAILED/BLOCKED；全部通过且无未了结 bug 的需求自动机器判定 DONE，存在未通过的已 DONE 需求会被拉回 IN_PROGRESS。", settleTestCasesSchema(), List.of("results")));
        defs.add(def("report_bug", "上报 bug（测试发现缺陷必调，禁止只在回复文本里说发现 bug）：挂需求与用例，需求交付树可见。", reportBugSchema(), List.of("bugs")));
        defs.add(def("settle_bugs", "流转 bug 状态（FIXING/FIXED/CLOSED/REJECTED）：全部了结且用例全通过的需求自动机器判定 DONE。", settleBugsSchema(), List.of("updates")));
        defs.add(def("ask_stage", "跨阶段求助：向另一个阶段的负责角色发起支援请求（如 REQUIREMENT 阶段请 OPERATE 的 devops 确认服务器现状、DEVELOP 阶段请 REQUIREMENT 的 product_analyst 澄清需求）。异步进行——发起后本阶段可继续工作，对方答复后结果进黑板与对话流，问答双落文档。仅限跨阶段协作；本阶段内的疑问用 discuss 或让执行步走 ask_peer。",
                askStageSchema(), List.of("target_stage", "name", "question")));
        return defs;
    }

    // ---------- 工具实现（每个原语一个 Tool，行为收敛、可独立测试） ----------

    private Tool planSteps() {
        return tool("plan_steps", req -> {
            Object stepsObj = req.input().get("steps");
            if (!(stepsObj instanceof List<?> steps) || steps.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "steps 不能为空"));
            }
            // 意图锚：规划级全局意图与验收口径，落库后每轮 prompt 强制携带（防长链漂移）
            String anchor = str(req.input().get("intent_anchor"));
            if (anchor.isBlank()) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "intent_anchor 不能为空：用一段话锁定本阶段的全局意图与总体验收口径，后续每步执行都会对照它防走偏"));
            }
            List<StageStep> rows = new ArrayList<>();
            // 重拾复用（2026-10-19 用户反馈「相同节点可以重拾 不能直接创建」）：同名同类型同 agent
            // 的 FAILED/SKIPPED 旧步骤原地重置（seq 不变，追溯图节点原地从红转灰再转绿），
            // 不再新建——否则失败重做会在图上留一堆红色旧节点 + 灰色新节点并存，吓人且误导。
            // DONE 行不复用（已交付不重做）；WAITING（等决策）不复用（该走决策答复）。
            List<StageStep> settled = new ArrayList<>(stepRepo.findByStage(wsId, stage, iteration).stream()
                    .filter(s -> s.status() == StepStatus.FAILED || s.status() == StepStatus.SKIPPED)
                    .toList());
            int reused = 0;
            // seq 从已占用最大值顺延：已销账行（DONE/FAILED/SKIPPED）占住的 seq 不能复用，
            // 否则重规划撞 uk_pss_ws_stage_iter_seq 唯一键（收口死锁事故的帮凶之一）
            int seq = stepRepo.maxSeq(wsId, stage, iteration) + 1;
            for (Object o : steps) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String rowAgent = str(m.get("agent"));
                List<String> rowParts = m.get("participants") instanceof List<?> pl0
                        ? pl0.stream().map(String::valueOf).toList() : List.of();
                String bad = checkAgents(rowParts);
                if (bad == null && !rowAgent.isBlank()) {
                    bad = checkAgents(List.of(rowAgent));
                }
                if (bad != null) {
                    return ToolResult.of(Map.of("ok", false, "message", bad));
                }
                String name = str(m.get("name"));
                @SuppressWarnings("unchecked")
                List<String> participants = m.get("participants") instanceof List<?> pl
                        ? pl.stream().map(String::valueOf).toList() : List.of();
                // acceptance 断言：可判定的验收标准（test/artifact/api/shell），规划时锁定，执行链不可改
                String acceptanceJson = toJson(m.get("acceptance"));
                // 需求先行：步骤声明服务哪些需求项（code 列表，可空）
                @SuppressWarnings("unchecked")
                List<String> reqCodes = m.get("requirement_codes") instanceof List<?> rl
                        ? rl.stream().map(String::valueOf).toList() : List.of();
                // 重拾命中：同名同类型同 agent 的 FAILED/SKIPPED 行原地重置（保持旧 seq），
                // 规划内容按新规划覆盖；一行只复用一次（同名多行时逐个消费）
                StepType type = "DISCUSS".equalsIgnoreCase(str(m.get("type"))) ? StepType.DISCUSS : StepType.TASK;
                // 验收责任链（P0）：每步声明验收人——'tool'=机器验收（默认）| 'human'=用户验收
                // （执行完进 WAITING_ACCEPT，用户通过才 DONE）。交付型步骤（TASK）必填，
                // 缺失拒绝规划——验收人缺位=没人对结果负责（敷衍产出一路绿灯的根因）
                String acceptanceOwner = str(m.get("acceptance_owner"));
                if (type != StepType.DISCUSS && !"human".equalsIgnoreCase(acceptanceOwner)
                        && !"tool".equalsIgnoreCase(acceptanceOwner)) {
                    return ToolResult.of(Map.of("ok", false, "message",
                            "步骤「" + name + "」缺 acceptance_owner：交付型步骤必须声明验收人——"
                                    + "\"tool\"=平台机器验收（产物断言/验证命令），\"human\"=用户验收（关键交付：PRD/架构设计/上线报告）。"
                                    + "验收人缺位=没人对结果负责，敷衍产出会一路绿灯"));
                }
                StageStep match = settled.stream()
                        .filter(s -> s.name().equals(name) && s.stepType() == type
                                && s.agent() != null && s.agent().equals(rowAgent))
                        .findFirst().orElse(null);
                if (match != null) {
                    settled.remove(match);
                    stepRepo.resetForReuse(match.id(), str(m.get("instruction")), acceptanceJson, reqCodes);
                    // 重拾同步刷新验收人（重规划可能改变验收安排）
                    stepRepo.updateAcceptanceOwner(match.id(), acceptanceOwner);
                    reused++;
                    continue;
                }
                rows.add(new StageStep(null, wsId, stage, iteration, seq++, name, type,
                        str(m.get("agent")), participants, StepStatus.PLANNED,
                        str(m.get("instruction")), null, null, null, null, null, null, null, null,
                        null, acceptanceJson, null, reqCodes,
                        "human".equalsIgnoreCase(acceptanceOwner) ? "human" : "tool"));
            }
            stepRepo.replacePlanned(wsId, stage, iteration, rows);
            stepRepo.saveAnchor(wsId, stage, iteration, anchor);
            return ToolResult.of(Map.of("ok", true, "planned", rows.size(),
                    "message", "规划已登记，共 " + (rows.size() + reused) + " 个小流程"
                            + (reused > 0 ? "（其中 " + reused + " 个重拾此前失败/跳过的同名步骤，原地重试）" : "")
                            + "；意图锚已锁定"));
        });
    }

    /** 偏差申报：降级/换路的唯一合法通道，落 deviation_json 留档（静默降级由判定器比对抓取）。 */
    private Tool declareDeviation() {
        return tool("declare_deviation", req -> {
            long seq = parseLong(req.input().get("seq"), -1);
            String reason = str(req.input().get("reason"));
            String fallback = str(req.input().get("fallback"));
            Optional<StageStep> found = stepRepo.findByStage(wsId, stage, iteration).stream()
                    .filter(s -> s.seq() == seq).findFirst();
            if (found.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "序号不存在: " + seq));
            }
            if (reason.isBlank() || fallback.isBlank()) {
                return ToolResult.of(Map.of("ok", false, "message", "reason/fallback 必填：说清为什么做不到、打算改成什么"));
            }
            String deviationJson = toJson(Map.of(
                    "reason", reason, "fallback", fallback,
                    "declaredAt", java.time.LocalDateTime.now().toString()));
            stepRepo.updateStatus(found.get().withDeviation(deviationJson));
            return ToolResult.of(Map.of("ok", true, "message",
                    "偏差已申报留档（seq=" + seq + "）。若降级影响交付口径，继续用 request_decision 征询用户"));
        });
    }

    /**
     * 需求先行（V34）：需求项是所有实施工作的锚定物。
     * propose=落 PROPOSED（可自动生成 code）；confirm=置 CONFIRMED（纪律：必须先有用户明确同意并留档）；
     * update=改 PROPOSED 草稿；drop=废弃（被 active 需求依赖时拒绝）。
     * depends_on 引用必须存在且不得成环（DFS 检测）。
     */
    private Tool declareRequirements() {
        return tool("declare_requirements", req -> {
            String action = str(req.input().get("action"));
            if (!(req.input().get("requirements") instanceof List<?> items) || items.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "requirements 不能为空"));
            }
            List<String> results = new ArrayList<>();
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String title = str(m.get("title"));
                if (title.isBlank()) {
                    return ToolResult.of(Map.of("ok", false, "message", "每条需求必须有 title"));
                }
                String code = str(m.get("code"));
                String depsJson = m.get("depends_on") instanceof List<?> dl && !dl.isEmpty()
                        ? toJson(dl.stream().map(String::valueOf).toList()) : null;
                switch (action) {
                    case "propose" -> {
                        if (code.isBlank()) {
                            code = nextRequirementCode();
                        }
                        if (requirementRepo.findByCode(wsId, code).isPresent()) {
                            return ToolResult.of(Map.of("ok", false, "message",
                                    "需求编号已存在: " + code + "（更新已有需求请用 update 动作）"));
                        }
                        String cycle = checkDependencyCycle(code, depsJson);
                        if (cycle != null) {
                            return ToolResult.of(Map.of("ok", false, "message", cycle));
                        }
                        requirementRepo.insert(new RequirementRepository.Requirement(
                                null, wsId, code, title, str(m.get("description")),
                                m.get("acceptance") == null ? null : toJson(m.get("acceptance")),
                                "PROPOSED", depsJson, "INCUBATION", str(m.get("parent_code")).isBlank() ? null : str(m.get("parent_code")),
                                user.username(), null, null));
                        results.add(code + " PROPOSED「" + title + "」");
                    }
                    case "confirm" -> {
                        if (code.isBlank()) {
                            return ToolResult.of(Map.of("ok", false, "message", "confirm 必须带 code"));
                        }
                        if (requirementRepo.findByCode(wsId, code).isEmpty()) {
                            return ToolResult.of(Map.of("ok", false, "message", "需求不存在: " + code));
                        }
                        // 纪律（工具描述与 guidance 双重声明）：confirm 前必须已用 request_decision
                        // 征得用户同意并 record_answer 留档——PM 不得代替用户确认
                        requirementRepo.updateStatus(wsId, code, "CONFIRMED");
                        results.add(code + " CONFIRMED");
                    }
                    case "update" -> {
                        if (code.isBlank()) {
                            return ToolResult.of(Map.of("ok", false, "message", "update 必须带 code"));
                        }
                        RequirementRepository.Requirement existing = requirementRepo
                                .findByCode(wsId, code).orElse(null);
                        if (existing == null) {
                            return ToolResult.of(Map.of("ok", false, "message", "需求不存在: " + code));
                        }
                        if (!"PROPOSED".equals(existing.status())) {
                            return ToolResult.of(Map.of("ok", false, "message",
                                    code + " 已确认锁定（" + existing.status() + "）：改动请落一条新的 PROPOSED 需求并说明取代关系"));
                        }
                        String cycle = checkDependencyCycle(code, depsJson);
                        if (cycle != null) {
                            return ToolResult.of(Map.of("ok", false, "message", cycle));
                        }
                        requirementRepo.updateDraft(new RequirementRepository.Requirement(
                                existing.id(), wsId, code,
                                title.isBlank() ? existing.title() : title,
                                str(m.get("description")).isBlank() ? existing.description() : str(m.get("description")),
                                m.get("acceptance") == null ? existing.acceptanceJson() : toJson(m.get("acceptance")),
                                existing.status(),
                                depsJson == null ? existing.dependsOn() : depsJson,
                                existing.source(), existing.parentCode(), existing.createdBy(), existing.createdAt(), existing.updatedAt()));
                        results.add(code + " UPDATED");
                    }
                    case "drop" -> {
                        if (code.isBlank()) {
                            return ToolResult.of(Map.of("ok", false, "message", "drop 必须带 code"));
                        }
                        if (requirementRepo.countDependents(wsId, code) > 0) {
                            return ToolResult.of(Map.of("ok", false, "message",
                                    code + " 被其他需求依赖，先处理依赖方再废弃"));
                        }
                        requirementRepo.updateStatus(wsId, code, "DROPPED");
                        results.add(code + " DROPPED");
                    }
                    default -> {
                        return ToolResult.of(Map.of("ok", false,
                                "message", "action 必须是 propose/confirm/update/drop"));
                    }
                }
            }
            // 项目状态实时推送：需求落库/确认/状态推进，在线前端收到即刷新需求看板
            // （对话轮结束无刷新回调，主控 confirm 后看板不推就停在旧状态）
            List<String> touched = results.stream()
                    .map(r -> r.substring(0, r.indexOf(' ')))
                    .distinct().toList();
            if (!touched.isEmpty()) {
                projectEvents.publish(wsId, "requirement.updated", Map.of(
                        "stage", stage, "codes", touched, "action", action));
            }
            return ToolResult.of(Map.of("ok", true, "message",
                    "需求已落档: " + String.join("；", results)
                            + ("propose".equals(action) ? "。请用 request_decision 请用户确认后再规划实施" : "")
                            + orphanRootNote(action, items)));
        });
    }

    /**
     * 需求链完整性提醒（2026-10-20 实锤）：批量 propose 全孤根（无 parent_code/depends_on）
     * → 追溯图散球无链、用户看到断链警示——返回值当场提醒主控补声明（确属独立可忽略）。
     */
    private String orphanRootNote(String action, List<?> items) {
        if (!"propose".equals(action) || items.size() < 2) {
            return "";
        }
        boolean anyLink = items.stream().anyMatch(o -> o instanceof Map<?, ?> m
                && (m.get("depends_on") instanceof List<?> dl && !dl.isEmpty()
                    || !str(m.get("parent_code")).isBlank()));
        return anyLink ? "" : " ⚠️ 本次登记 " + items.size()
                + " 条需求全部为孤根（无 parent_code/depends_on）——若存在主次/派生/前置关系，请用 update 补声明（追溯图据此画需求链）；确属彼此独立可忽略。";
    }

    /** 自动生成下一个需求编号：REQ-001 起，取现有最大序号 +1。 */
    // ---------- V36 需求交付树：测试用例 / bug ----------

    /** 用例编号自增（TC-001）。 */
    private String nextTestCaseCode() {
        int max = testCaseRepo.findByWorkspace(wsId).stream()
                .map(TestCaseRepository.TestCase::code)
                .filter(c -> c.matches("TC-\\d+"))
                .mapToInt(c -> Integer.parseInt(c.substring(3)))
                .max().orElse(0);
        return String.format("TC-%03d", max + 1);
    }

    /** bug 编号自增（BUG-001）。 */
    private String nextBugCode() {
        int max = bugRepo.findByWorkspace(wsId).stream()
                .map(BugRepository.Bug::code)
                .filter(c -> c.matches("BUG-\\d+"))
                .mapToInt(c -> Integer.parseInt(c.substring(4)))
                .max().orElse(0);
        return String.format("BUG-%03d", max + 1);
    }

    private Tool declareTestCases() {
        return tool("declare_test_cases", req -> {
            if (!(req.input().get("cases") instanceof List<?> items) || items.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "cases 不能为空"));
            }
            List<String> results = new ArrayList<>();
            List<String> touchedReqs = new ArrayList<>();
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String title = str(m.get("title"));
                if (title.isBlank()) {
                    return ToolResult.of(Map.of("ok", false, "message", "每条用例必须有 title"));
                }
                String code = str(m.get("code"));
                if (code.isBlank()) {
                    code = nextTestCaseCode();
                }
                String reqCode = str(m.get("requirement_code"));
                if (!reqCode.isBlank() && requirementRepo.findByCode(wsId, reqCode).isEmpty()) {
                    return ToolResult.of(Map.of("ok", false, "message", "需求不存在: " + reqCode));
                }
                testCaseRepo.upsert(new TestCaseRepository.TestCase(
                        null, wsId, code, title, "ACTIVE", reqCode.isBlank() ? null : reqCode,
                        null, str(m.get("content")), user.username(), null, null));
                results.add(code + "「" + title + "」" + (reqCode.isBlank() ? "" : " → " + reqCode));
                if (!reqCode.isBlank() && !touchedReqs.contains(reqCode)) {
                    touchedReqs.add(reqCode);
                }
            }
            projectEvents.publish(wsId, "testcase.updated", Map.of("stage", stage, "count", results.size()));
            return ToolResult.of(Map.of("ok", true, "message", "已落用例: " + String.join("、", results)));
        });
    }

    private Tool settleTestCases() {
        return tool("settle_test_cases", req -> {
            if (!(req.input().get("results") instanceof List<?> items) || items.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "results 不能为空"));
            }
            List<String> results = new ArrayList<>();
            List<String> touchedReqs = new ArrayList<>();
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String code = str(m.get("code"));
                String result = str(m.get("result")).toUpperCase();
                if (code.isBlank() || !List.of("PASSED", "FAILED", "BLOCKED").contains(result)) {
                    return ToolResult.of(Map.of("ok", false, "message",
                            "每条结果必须有 code 与 result（PASSED/FAILED/BLOCKED）: " + code));
                }
                var found = testCaseRepo.findByCode(wsId, code);
                if (found.isEmpty()) {
                    return ToolResult.of(Map.of("ok", false, "message", "用例不存在: " + code));
                }
                testCaseRepo.updateStatus(wsId, code, result);
                String note = str(m.get("note"));
                results.add(code + " → " + result + (note.isBlank() ? "" : "（" + note + "）"));
                String reqCode = found.get().requirementCode();
                if (reqCode != null && !touchedReqs.contains(reqCode)) {
                    touchedReqs.add(reqCode);
                }
            }
            // 机器判定门禁：全通过且无未了结 bug → 需求 DONE；有未通过 → 已 DONE 的拉回
            List<String> settled = touchedReqs.stream()
                    .map(c -> deliverySettler.settleRequirement(wsId, c))
                    .filter(java.util.Objects::nonNull).toList();
            projectEvents.publish(wsId, "testcase.updated", Map.of("stage", stage, "count", results.size()));
            return ToolResult.of(Map.of("ok", true,
                    "message", "已回写: " + String.join("、", results)
                            + (settled.isEmpty() ? "" : "；需求联动: " + String.join("；", settled))));
        });
    }

    private Tool reportBug() {
        return tool("report_bug", req -> {
            if (!(req.input().get("bugs") instanceof List<?> items) || items.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "bugs 不能为空"));
            }
            List<String> results = new ArrayList<>();
            List<String> touchedReqs = new ArrayList<>();
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String title = str(m.get("title"));
                if (title.isBlank()) {
                    return ToolResult.of(Map.of("ok", false, "message", "每条 bug 必须有 title"));
                }
                String code = str(m.get("code"));
                if (code.isBlank()) {
                    code = nextBugCode();
                }
                String severity = str(m.get("severity")).toUpperCase();
                if (severity.isBlank()) {
                    severity = "P2";
                }
                if (!List.of("P0", "P1", "P2", "P3").contains(severity)) {
                    return ToolResult.of(Map.of("ok", false, "message", "severity 只允许 P0/P1/P2/P3: " + severity));
                }
                String reqCode = str(m.get("requirement_code"));
                if (!reqCode.isBlank() && requirementRepo.findByCode(wsId, reqCode).isEmpty()) {
                    return ToolResult.of(Map.of("ok", false, "message", "需求不存在: " + reqCode));
                }
                String tcCode = str(m.get("test_case_code"));
                if (!tcCode.isBlank() && testCaseRepo.findByCode(wsId, tcCode).isEmpty()) {
                    return ToolResult.of(Map.of("ok", false, "message", "用例不存在: " + tcCode));
                }
                bugRepo.upsert(new BugRepository.Bug(
                        null, wsId, code, title, severity, "OPEN",
                        reqCode.isBlank() ? null : reqCode, tcCode.isBlank() ? null : tcCode,
                        null, str(m.get("content")), user.username(), null, null));
                results.add(code + "（" + severity + "）「" + title + "」");
                if (!reqCode.isBlank() && !touchedReqs.contains(reqCode)) {
                    touchedReqs.add(reqCode);
                }
            }
            projectEvents.publish(wsId, "bug.updated", Map.of("stage", stage, "count", results.size()));
            return ToolResult.of(Map.of("ok", true, "message", "已上报: " + String.join("、", results)
                    + "——修复后用 settle_bugs 流转状态"));
        });
    }

    private Tool settleBugs() {
        return tool("settle_bugs", req -> {
            if (!(req.input().get("updates") instanceof List<?> items) || items.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "updates 不能为空"));
            }
            List<String> results = new ArrayList<>();
            List<String> touchedReqs = new ArrayList<>();
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String code = str(m.get("code"));
                String status = str(m.get("status")).toUpperCase();
                if (code.isBlank() || !List.of("FIXING", "FIXED", "CLOSED", "REJECTED").contains(status)) {
                    return ToolResult.of(Map.of("ok", false, "message",
                            "每条更新必须有 code 与 status（FIXING/FIXED/CLOSED/REJECTED）: " + code));
                }
                var found = bugRepo.findByCode(wsId, code);
                if (found.isEmpty()) {
                    return ToolResult.of(Map.of("ok", false, "message", "bug 不存在: " + code));
                }
                bugRepo.updateStatus(wsId, code, status);
                String note = str(m.get("note"));
                results.add(code + " → " + status + (note.isBlank() ? "" : "（" + note + "）"));
                String reqCode = found.get().requirementCode();
                if (reqCode != null && !touchedReqs.contains(reqCode)) {
                    touchedReqs.add(reqCode);
                }
            }
            List<String> settled = touchedReqs.stream()
                    .map(c -> deliverySettler.settleRequirement(wsId, c))
                    .filter(java.util.Objects::nonNull).toList();
            projectEvents.publish(wsId, "bug.updated", Map.of("stage", stage, "count", results.size()));
            return ToolResult.of(Map.of("ok", true,
                    "message", "已流转: " + String.join("、", results)
                            + (settled.isEmpty() ? "" : "；需求联动: " + String.join("；", settled))));
        });
    }

    /** declare_test_cases 入参 schema：cases 数组（title 必填，code/requirement_code/content 可选）。 */
    private static Map<String, Object> declareTestCasesSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "cases", Map.of(
                                "type", "array",
                                "description", "用例清单",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "code", Map.of("type", "string", "description", "用例编号（TC-001），留空自动分配"),
                                                "title", Map.of("type", "string", "description", "用例标题"),
                                                "requirement_code", Map.of("type", "string", "description", "挂哪条需求（REQ-001），全局用例留空"),
                                                "content", Map.of("type", "string", "description", "用例正文：前置/步骤/预期")),
                                        "required", List.of("title")))),
                "required", List.of("cases"));
    }

    private static Map<String, Object> settleTestCasesSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "results", Map.of(
                                "type", "array",
                                "description", "执行结果清单",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "code", Map.of("type", "string", "description", "用例编号"),
                                                "result", Map.of("type", "string", "description", "PASSED/FAILED/BLOCKED"),
                                                "note", Map.of("type", "string", "description", "结果说明（失败原因/阻塞原因）")),
                                        "required", List.of("code", "result")))),
                "required", List.of("results"));
    }

    private static Map<String, Object> reportBugSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "bugs", Map.of(
                                "type", "array",
                                "description", "bug 清单",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "code", Map.of("type", "string", "description", "bug 编号（BUG-001），留空自动分配"),
                                                "title", Map.of("type", "string", "description", "bug 标题"),
                                                "severity", Map.of("type", "string", "description", "严重度 P0/P1/P2/P3，默认 P2"),
                                                "requirement_code", Map.of("type", "string", "description", "归属需求（REQ-001）"),
                                                "test_case_code", Map.of("type", "string", "description", "发现自哪个用例（TC-001）"),
                                                "content", Map.of("type", "string", "description", "现象/复现步骤/根因")),
                                        "required", List.of("title")))),
                "required", List.of("bugs"));
    }

    private static Map<String, Object> settleBugsSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "updates", Map.of(
                                "type", "array",
                                "description", "状态流转清单",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "code", Map.of("type", "string", "description", "bug 编号"),
                                                "status", Map.of("type", "string", "description", "FIXING/FIXED/CLOSED/REJECTED"),
                                                "note", Map.of("type", "string", "description", "流转说明（根因/修复方式/误报理由）")),
                                        "required", List.of("code", "status")))),
                "required", List.of("updates"));
    }

    private String nextRequirementCode() {
        int max = requirementRepo.findByWorkspace(wsId).stream()
                .map(RequirementRepository.Requirement::code)
                .filter(c -> c.matches("REQ-\\d+"))
                .mapToInt(c -> Integer.parseInt(c.substring(4)))
                .max().orElse(0);
        return String.format("REQ-%03d", max + 1);
    }

    /** 依赖防环：从新需求出发沿 depends_on 深搜，回到自身即成环。返回 null 表示无环。 */
    private String checkDependencyCycle(String code, String depsJson) {
        if (depsJson == null) {
            return null;
        }
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        stack.push(code);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (!visited.add(cur)) {
                continue;
            }
            Optional<RequirementRepository.Requirement> found = requirementRepo.findByCode(wsId, cur);
            if (found.isEmpty() || found.get().dependsOn() == null) {
                continue;
            }
            try {
                for (Object d : objectMapper.readValue(found.get().dependsOn(), List.class)) {
                    String dep = String.valueOf(d);
                    if (code.equals(dep)) {
                        return "依赖成环：" + code + " 的依赖链绕回了自身，请调整 depends_on";
                    }
                    stack.push(dep);
                }
            } catch (Exception ignore) {
                // 依赖 JSON 损坏按无依赖处理
            }
        }
        return null;
    }

    /**
     * 发起讨论（主席模型）：串行发言（每人读全部已有发言再开口），发言全文同步回流主控。
     * 讨论步保持 EXECUTING——由主控以主席身份决定 discuss_more 追加/@定向 或 discuss_close 收口。
     */
    private Tool discuss() {
        return tool("discuss", req -> {
            String name = str(req.input().get("name"));
            String topic = str(req.input().get("topic"));
            List<String> participants = req.input().get("participants") instanceof List<?> pl
                    ? pl.stream().map(String::valueOf).toList() : List.of();
            if (name.isBlank() || topic.isBlank() || participants.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "name/topic/participants 必填"));
            }
            String badAgents = checkAgents(participants);
            if (badAgents != null) {
                return ToolResult.of(Map.of("ok", false, "message", badAgents));
            }
            StageStep step = nextStep(name, StepType.DISCUSS, "project_manager", participants, topic);
            List<String> codes = req.input().get("requirement_codes") instanceof List<?> rl
                    ? rl.stream().map(String::valueOf).filter(c -> !c.isBlank()).toList() : List.of();
            if (codes.isEmpty()) {
                // 兜底：LLM 漏传时从讨论名/议题提取 REQ-xxx（实测 PM 会漏）
                codes = StageStep.extractRequirementCodes(name + " " + topic);
            }
            if (!codes.isEmpty()) {
                step = step.withRequirementCodes(codes);
            }
            long id = stepRepo.insert(step);
            StageStep exec = step.withId(id);
            stepRepo.updateStatus(exec.withStatus(StepStatus.EXECUTING));
            try {
                // 串行发言：前一个发言，后一个能看到并补充；无超时，做完为止
                String transcript = discussionEngine.runDiscussion(exec, brief(topic), participants, null);
                return ToolResult.of(Map.of("ok", true, "seq", exec.seq(),
                        "speeches", transcript,
                        "message", "第一轮发言完成（" + participants.size() + " 人串行）。你是讨论主席："
                                + "观点够了用 discuss_close(你的主席总结) 收口；不够用 discuss_more 追加"
                                + "（participants 传多人=再来一轮，传一人=@定向回答某个问题）。"
                                + "讨论未收口前不要推进其他步骤。"));
            } catch (DiscussionEngine.UserInterrupted ui) {
                discussionEngine.markInterrupted(exec);
                return ToolResult.of(Map.of("ok", false, "seq", exec.seq(),
                        "message", "用户已终止本讨论。请直接进入下一步，不要重试。"));
            } catch (Exception e) {
                stepRepo.updateStatus(exec.withError(TaskRunner.rootMessage(e)));
                return ToolResult.of(Map.of("ok", false, "seq", exec.seq(),
                        "message", "讨论失败: " + TaskRunner.rootMessage(e) + "——可重试或调整参会人"));
            }
        });
    }

    /**
     * 追加讨论发言（主席权柄）：participants 传多人=再来一轮串行发言；
     * 传一人=@定向——就 focusQuestion（用户的问题/某发言者提出的问题）让该角色回答。
     * 被 @ 者读 DB 最新全量（必然含当前发言者结论与用户插话）。
     */
    private Tool discussMore() {
        return tool("discuss_more", req -> {
            long seq = parseLong(req.input().get("seq"), -1);
            List<String> participants = req.input().get("participants") instanceof List<?> pl
                    ? pl.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList() : List.of();
            String focus = str(req.input().get("question"));
            if (seq < 0 || participants.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "seq/participants 必填；participants 传多人=追加一轮，传一人=@定向（配 question）"));
            }
            StageStep step = stepRepo.findByStage(wsId, stage, iteration).stream()
                    .filter(s -> s.seq() == seq && s.stepType() == StepType.DISCUSS)
                    .findFirst().orElse(null);
            if (step == null) {
                return ToolResult.of(Map.of("ok", false, "message", "讨论步不存在: seq=" + seq));
            }
            if (step.status() != StepStatus.EXECUTING) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "讨论已收口（" + step.status() + "），不能追加发言；需要新讨论就重新 discuss 发起"));
            }
            String badAgents = checkAgents(participants);
            if (badAgents != null) {
                return ToolResult.of(Map.of("ok", false, "message", badAgents));
            }
            try {
                String transcript = discussionEngine.runDiscussion(step, brief(step.instruction()),
                        participants, focus.isBlank() ? null : focus);
                return ToolResult.of(Map.of("ok", true, "seq", step.seq(),
                        "speeches", transcript,
                        "message", "追加发言完成。继续以主席身份判断：discuss_more 再追加，或 discuss_close 收口。"));
            } catch (DiscussionEngine.UserInterrupted ui) {
                discussionEngine.markInterrupted(step);
                return ToolResult.of(Map.of("ok", false, "seq", step.seq(),
                        "message", "用户已终止本讨论。请直接进入下一步，不要重试。"));
            } catch (Exception e) {
                stepRepo.updateStatus(step.withError(TaskRunner.rootMessage(e)));
                return ToolResult.of(Map.of("ok", false, "seq", step.seq(),
                        "message", "追加发言失败: " + TaskRunner.rootMessage(e)));
            }
        });
    }

    /** 主席收口：主控给出总结，纪要（各方观点+主席总结）落档 DONE。 */
    private Tool discussClose() {
        return tool("discuss_close", req -> {
            long seq = parseLong(req.input().get("seq"), -1);
            String summary = str(req.input().get("summary"));
            if (seq < 0 || summary.isBlank()) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "seq/summary 必填——summary 是你的主席总结（共识/分歧/待办/OPEN_QUESTION）"));
            }
            StageStep step = stepRepo.findByStage(wsId, stage, iteration).stream()
                    .filter(s -> s.seq() == seq && s.stepType() == StepType.DISCUSS)
                    .findFirst().orElse(null);
            if (step == null) {
                return ToolResult.of(Map.of("ok", false, "message", "讨论步不存在: seq=" + seq));
            }
            if (step.status() != StepStatus.EXECUTING) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "讨论不在进行中（" + step.status() + "），无需收口"));
            }
            discussionEngine.closeDiscussion(step, summary);
            return ToolResult.of(Map.of("ok", true, "seq", step.seq(),
                    "message", "讨论已收口落档（纪要=各方观点+你的主席总结，已进留档与交付物索引）。继续推进。"));
        });
    }

    /** 跨阶段求助：向另一阶段的负责角色发起异步支援请求（双阶段台账 + 文档双落）。 */
    private Tool askStage() {
        return tool("ask_stage", req -> {
            String targetStage = str(req.input().get("target_stage")).trim().toUpperCase();
            String name = str(req.input().get("name"));
            String question = str(req.input().get("question"));
            if (targetStage.isBlank() || name.isBlank() || question.isBlank()) {
                return ToolResult.of(Map.of("ok", false, "message", "target_stage/name/question 必填"));
            }
            if (!STAGES.contains(targetStage)) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "target_stage 必须是 " + String.join("/", STAGES) + " 之一，当前值: " + targetStage));
            }
            if (targetStage.equals(stage)) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "目标阶段不能是当前阶段（" + stage + "）：本阶段内的疑问用 discuss 发起讨论，"
                                + "执行中的疑问由执行步走 ask_peer"));
            }
            long asked = stepRepo.findByStage(wsId, stage, iteration).stream()
                    .filter(s -> s.name() != null && s.name().startsWith("跨阶段求助·"))
                    .count();
            if (asked >= MAX_ASK_STAGE_PER_ITERATION) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "本轮跨阶段求助已达上限（" + MAX_ASK_STAGE_PER_ITERATION + " 次）："
                                + "反复求助说明阶段边界或规划有问题，请基于已有答复继续"));
            }
            String targetAgent = STAGE_DEFAULT_AGENT.get(targetStage);
            String bad = checkAgents(List.of(targetAgent));
            if (bad != null) {
                return ToolResult.of(Map.of("ok", false, "message", bad));
            }
            // 发起阶段台账步（EXECUTING 起步，答复后 DONE——聊天框可见）
            StageStep launch = nextStep("跨阶段求助·" + targetStage + "·" + name, StepType.CONSULT,
                    "project_manager", List.of(targetAgent), question);
            List<String> codes = req.input().get("requirement_codes") instanceof List<?> rl
                    ? rl.stream().map(String::valueOf).filter(c -> !c.isBlank()).toList() : List.of();
            if (codes.isEmpty()) {
                codes = StageStep.extractRequirementCodes(name + " " + question);
            }
            if (!codes.isEmpty()) {
                launch = launch.withRequirementCodes(codes);
            }
            long launchId = stepRepo.insert(launch);
            // 目标阶段支援记录步（该阶段台账/聊天框同样可见）
            List<StageStep> targetSteps = stepRepo.findByWorkspace(wsId).stream()
                    .filter(s -> targetStage.equals(s.stage())).toList();
            int targetIter = targetSteps.stream().mapToInt(StageStep::iteration).max().orElse(1);
            int targetSeq = targetSteps.stream().filter(s -> s.iteration() == targetIter)
                    .mapToInt(StageStep::seq).max().orElse(0) + 1;
            StageStep support = new StageStep(null, wsId, targetStage, targetIter, targetSeq,
                    "支援·" + stage + "·" + name, StepType.CONSULT, targetAgent, List.of("project_manager"),
                    StepStatus.PLANNED, question, null, null, null, null, null, null,
                    null, null, null, null, null, codes);
            long supportId = stepRepo.insert(support);
            try {
                // 同步直答（无超时）：答案直接回到你手里
                String answer = peerConsult.askStageSync(launch.withId(launchId), support.withId(supportId),
                        targetAgent, stage, question, brief(question));
                String briefAns = answer.length() > 1200 ? answer.substring(0, 1200) + "…(全文已留档)" : answer;
                return ToolResult.of(Map.of("ok", true, "seq", launch.seq(),
                        "answer", briefAns,
                        "message", targetAgent + " 已答复（问答双落文档）。消化答案后继续推进。"));
            } catch (Exception e) {
                return ToolResult.of(Map.of("ok", false, "seq", launch.seq(),
                        "message", "跨阶段求助失败: " + TaskRunner.rootMessage(e) + "——可重试或换路"));
            }
        });
    }

    private Tool executeStep() {
        return tool("execute_step", req -> {
            String name = str(req.input().get("name"));
            String agent = str(req.input().get("agent"));
            String instruction = str(req.input().get("instruction"));
            if (name.isBlank() || agent.isBlank()) {
                return ToolResult.of(Map.of("ok", false, "message", "name/agent 必填"));
            }
            String badAgent = checkAgents(List.of(agent));
            if (badAgent != null) {
                return ToolResult.of(Map.of("ok", false, "message", badAgent));
            }
            // 需求关联：显式参数 + 任务名/说明正则兜底（LLM 漏传时从文本提取 REQ-xxx）
            List<String> codes = new ArrayList<>(req.input().get("requirement_codes") instanceof List<?> rl
                    ? rl.stream().map(String::valueOf).filter(c -> !c.isBlank()).toList() : List.<String>of());
            Matcher rm = REQ_CODE.matcher(name + " " + instruction);
            while (rm.find()) {
                String c = rm.group(1);
                if (!codes.contains(c)) {
                    codes.add(c);
                }
            }
            // 需求门禁：未确认（PROPOSED）需求不能派发实现任务——先征询确认，或显式豁免（留档）
            boolean gateSkip = Boolean.TRUE.equals(req.input().get("requirement_gate_skip"));
            String gateReason = str(req.input().get("requirement_gate_reason"));
            // 挂链门禁（2026-10-20 断链 6 项实锤）：codes 为空=追溯图断链警示——必须显式声明：
            // 专属步骤挂具体 REQ-xxx；全局性步骤（架构设计/测试执行/发布/环境准备）传 ["*"]；
            // 确实无法归类才用 requirement_gate_skip=true + reason 留档
            if (codes.isEmpty() && !gateSkip) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "步骤「" + name + "」未声明 requirement_codes——需求先行：请声明本步服务的需求项编号"
                                + "（如 [\\\"REQ-001\\\"]）；全局性步骤（架构设计/测试执行/发布/环境准备等不专属某条需求的）"
                                + "传 [\\\"*\\\"]；确实无法归类才用 requirement_gate_skip=true + requirement_gate_reason 留档"));
            }
            if (gateSkip && gateReason.isBlank()) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "需求门禁豁免必须给 requirement_gate_reason（豁免留档用户可见）"));
            }
            if (!codes.isEmpty() && !gateSkip) {
                List<String> blocked = new ArrayList<>();
                for (String code : codes) {
                    var r = requirementRepo.findByCode(wsId, code);
                    if (r.isEmpty()) {
                        blocked.add(code + "(需求不存在，检查编号)");
                    } else if ("PROPOSED".equals(r.get().status())) {
                        blocked.add(code + "(待确认)");
                    }
                }
                if (!blocked.isEmpty()) {
                    return ToolResult.of(Map.of("ok", false, "message",
                            "需求门禁：以下需求未确认，不能派发实现任务——" + String.join("、", blocked)
                                    + "。两条出路：① 用 request_decision 征询用户（带 requirement_codes），用户答复后需求自动确认；"
                                    + "② 确属无需需求确认的探索性/技术验证工作，带 requirement_gate_skip=true + requirement_gate_reason 显式豁免"));
                }
            }
            StageStep step = nextStep(name, StepType.TASK, agent, List.of(), instruction)
                    .withRequirementCodes(codes);
            if (gateSkip) {
                // 豁免留痕：写进步骤说明，台账可见
                step = new StageStep(step.id(), step.workspaceId(), step.stage(), step.iteration(), step.seq(),
                        step.name(), step.stepType(), step.agent(), step.participants(), step.status(),
                        "[需求门禁豁免: " + gateReason + "] " + instruction, step.outputSummary(), step.outputFull(),
                        step.artifactId(), step.skipReason(), step.error(), step.createdAt(), step.startedAt(),
                        step.finishedAt(), step.verifiedBy(), step.acceptanceJson(), step.deviationJson(),
                        step.requirementCodes());
            }
            // 重拾复用（2026-10-19「相同节点可以重拾 不能直接创建」）：同名同 agent 的
            // FAILED/SKIPPED 旧步骤原地重置（seq 不变，追溯图节点原地转绿）——execute_step
            // 是即席派单，失败重试若每次都新建，图上会堆一堆红色旧节点 + 灰色新节点。
            // PLANNED 也复用（2026-10-20 实锤）：plan_steps 登记的规划节点被 execute_step
            // 落地时必须原地关联——否则规划节点永远残留 PLANNED，autoPilot 反复「黑板还有
            // N 个未执行步骤」空转唤醒，主控被误导后文本模拟验收卡死等用户（用户点伪卡无响应）
            Optional<StageStep> stale = stepRepo.findByStage(wsId, stage, iteration).stream()
                    .filter(s -> s.status() == StepStatus.FAILED || s.status() == StepStatus.SKIPPED
                            || s.status() == StepStatus.PLANNED)
                    .filter(s -> s.name().equals(name) && s.agent() != null && s.agent().equals(agent))
                    .findFirst();
            long id;
            StageStep exec;
            if (stale.isPresent()) {
                stepRepo.resetForReuse(stale.get().id(), step.instruction(), step.acceptanceJson(), codes);
                id = stale.get().id();
                exec = step.withId(id).withSeq(stale.get().seq());
            } else {
                id = stepRepo.insert(step);
                exec = step.withId(id);
            }
            try {
                // 同步执行（无超时）：产出直接回到你手里，当场消化再决定下一步
                String output = taskRunner.runSync(exec, brief(""));
                String briefOut = output.length() > 1500 ? output.substring(0, 1500) + "…(全文已留档)" : output;
                // 唯一真相防御（2026-10-20 实锤）：执行 agent 在产出文本里模拟「验收卡/等待确认」，
                // 主控若照搬转述，用户面对的是无响应的伪卡——返回值当场提醒主控补 request_decision
                boolean fakeCard = output.contains("等待您确认") || output.contains("等待确认")
                        || output.contains("验收卡") || output.contains("等待您的确认");
                return ToolResult.of(Map.of("ok", true, "seq", exec.seq(),
                        "output", briefOut,
                        "message", "任务完成，产出已留档（交付物+文档）。消化产出后继续推进；"
                                + "若产出不合格可重发 execute_step 重做该步。"
                                + (fakeCard ? " ⚠️ 产出文本声称「等待用户确认/验收」——对话文本里的选项用户点了没有任何响应；"
                                        + "若该任务确需用户验收，你必须调 request_decision 创建真决策卡（或确认该步骤 acceptance_owner=human 走 WAITING_ACCEPT）；"
                                        + "若无需确认，直接继续推进，不要转述伪卡。" : "")));
            } catch (DiscussionEngine.UserInterrupted ui) {
                return ToolResult.of(Map.of("ok", false, "seq", exec.seq(),
                        "message", "用户已终止本任务。请直接进入下一步，不要重试。"));
            } catch (Exception e) {
                return ToolResult.of(Map.of("ok", false, "seq", exec.seq(),
                        "message", "任务失败: " + TaskRunner.rootMessage(e)
                                + "——立即决策：重试（再调一次）、换路（declare_deviation）或跳过（skip_step）"));
            }
        });
    }

    private Tool skipStep() {
        return tool("skip_step", req -> {
            long seq = parseLong(req.input().get("seq"), -1);
            String reason = str(req.input().get("reason"));
            Optional<StageStep> found = stepRepo.findByStage(wsId, stage, iteration).stream()
                    .filter(s -> s.seq() == seq).findFirst();
            if (found.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "序号不存在: " + seq));
            }
            StageStep s = found.get();
            if (s.active() || s.produced()) {
                return ToolResult.of(Map.of("ok", false, "message", "该步骤已执行/产出，不可跳过"));
            }
            if (reason.isBlank()) {
                return ToolResult.of(Map.of("ok", false, "message", "跳过必须写明理由"));
            }
            stepRepo.updateStatus(s.withSkip(reason));
            return ToolResult.of(Map.of("ok", true, "message", "已跳过并留档: " + reason));
        });
    }

    private Tool requestDecision() {
        return tool("request_decision", req -> {
            String question = str(req.input().get("question"));
            if (question.isBlank()) {
                return ToolResult.of(Map.of("ok", false, "message", "question 必填"));
            }
            // 需求关联：显式参数 + 问题文本正则兜底——答复后系统自动把关联需求置 CONFIRMED
            List<String> codes = new ArrayList<>(req.input().get("requirement_codes") instanceof List<?> rl
                    ? rl.stream().map(String::valueOf).filter(c -> !c.isBlank()).toList() : List.<String>of());
            Matcher rm = REQ_CODE.matcher(question);
            while (rm.find()) {
                String c = rm.group(1);
                if (!codes.contains(c)) {
                    codes.add(c);
                }
            }
            StageStep step = nextStep("决策：" + truncate(question, 40), StepType.DECISION,
                    "user", List.of(), question).withRequirementCodes(codes);
            // 创建即 WAITING（2026-10-20 审计修正）：决策步的生命周期是 WAITING→DONE，
            // 此前创建后一直 PLANNED（WAITING 从未被写入，状态机注释与实现不符）——
            // 后果：autoPilot 把它当待办唤醒主控空转（当晚实锤）、收口校验拦不住决策悬空。
            // WAITING=active：收口被拦、autoPilot 不抢跑、决策卡照常收集，全链路自洽。
            step = step.withStatus(StepStatus.WAITING);
            stepRepo.insert(step);
            // 实时性（2026-10-19）：决策步落库即推送——否则决策卡要等 30s 轮询才出现，
            // 用户不知道 AI 在等什么（审计实锤缺口 #3）
            projectEvents.publish(wsId, "step.updated", Map.of(
                    "stage", stage, "seq", step.seq(), "stepType", "DECISION",
                    "status", "WAITING", "name", step.name()));
            return ToolResult.of(Map.of("ok", true, "seq", step.seq(),
                    "message", "已向用户提出决策请求，等待答复（答复后需求自动确认并生成决策记录；也可用 record_answer 落档）"));
        });
    }

    private Tool recordAnswer() {
        return tool("record_answer", req -> {
            long seq = parseLong(req.input().get("seq"), -1);
            String answer = str(req.input().get("answer"));
            Optional<StageStep> found = stepRepo.findByStage(wsId, stage, iteration).stream()
                    .filter(s -> s.seq() == seq && s.stepType() == StepType.DECISION).findFirst();
            if (found.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "决策序号不存在: " + seq));
            }
            // 与用户答复端点同一条校验：已处理（DONE/SKIPPED）的决策拒绝重复落档——
            // 否则主控重复答复会覆盖用户原答复（审计发现与 answerDecision 不一致）
            StepStatus cur = found.get().status();
            if (cur == StepStatus.DONE || cur == StepStatus.SKIPPED) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "决策 #" + seq + " 已处理（" + cur + "），无需重复落档"));
            }
            stepRepo.updateStatus(found.get().withAnswer(answer).withVerifiedBy("human"));
            // 决策答复闭环：关联需求自动确认 + 决策记录落档（与答复端点同一条确定性联动）
            List<String> confirmed = decisionSettler.settle(found.get().withAnswer(answer), answer,
                    user == null ? "user" : user.username());
            return ToolResult.of(Map.of("ok", true, "message", "决策已闭环"
                    + (confirmed.isEmpty() ? "" : "；关联需求已自动确认: " + String.join("、", confirmed))));
        });
    }

    private Tool completeStage() {
        return tool("complete_stage", req -> {
            String summary = str(req.input().get("summary"));
            if (stepRepo.hasActiveStep(wsId, stage, iteration)) {
                return ToolResult.of(Map.of("ok", false,
                        "message", "仍有未了结的小流程（执行中/等待答复/待验收/规划未执行），不能收口；"
                                + "先逐个执行或 skip_step 处理掉，等待答复的决策等用户答复后再收口"));
            }
            // 验收责任链（P0）：本阶段没有任何用户验收过的交付 → 自动插一道「阶段交付验收」
            // 决策（等用户答复）并拒绝收口——用户不点头，阶段不推进（结构性保证，不靠 PM 自觉）
            // 兜底（2026-10-20 收口循环实锤）：DONE 的 DECISION 步本身即用户答复过的人确认——
            // 旧数据 verifiedBy 可能缺失，只查 verified_by=human 会让主控每轮收口都重发同一个
            // 验收决策门（#15→#16→#17 无限循环），用户答复一次就被追问一次
            List<StageStep> steps = stepRepo.findByStage(wsId, stage, iteration);
            boolean humanAccepted = steps.stream()
                    .anyMatch(s -> s.status() == StepStatus.DONE && "human".equals(s.verifiedBy()))
                    || steps.stream().anyMatch(s -> s.status() == StepStatus.DONE
                            && s.stepType() == StepType.DECISION);
            if (!humanAccepted) {
                StageStep gate = nextStep("阶段交付验收：" + truncate(summary, 40), StepType.DECISION,
                        "user", List.of(),
                        "本阶段交付是否验收通过？通过后进入下一阶段；有问题请直接说明，PM 会组织整改。")
                        .withStatus(StepStatus.WAITING);
                stepRepo.insert(gate);
                projectEvents.publish(wsId, "step.updated", Map.of(
                        "stage", stage, "seq", gate.seq(), "stepType", "DECISION",
                        "status", "WAITING", "name", gate.name()));
                return ToolResult.of(Map.of("ok", false,
                        "seq", gate.seq(),
                        "message", "本阶段还没有任何用户验收过的交付（verified_by=human 的步骤为 0）——"
                                + "已自动发起「阶段交付验收」决策（#" + gate.seq() + "），等用户答复后再收口。"
                                + "不要重复发起，直接告知用户在决策卡验收即可"));
            }
            try {
                Map<String, Object> advanced = advancer.complete(wsId, stage, summary);
                String nextStage = String.valueOf(advanced.getOrDefault("current_stage", ""));
                // 阶段收口自动续跑（2026-10-19「后续阶段一会儿就没后续了」）：收口即自动启动
                // 下一阶段首轮主控对话（系统唤醒轮，走事件总线实时回显）——流程不断链，
                // 用户不需要再发一条消息才动。下一阶段是 OPERATE 且项目无该阶段时 advancer 已兜底。
                if (!nextStage.isBlank() && !"DONE".equalsIgnoreCase(nextStage)) {
                    stageAutoStart.accept(wsId, nextStage);
                }
                return ToolResult.of(Map.of("ok", true,
                        "next_stage", nextStage,
                        "message", "阶段已收口并推进到 " + nextStage
                                + (nextStage.isBlank() || "DONE".equalsIgnoreCase(nextStage)
                                        ? "（项目全部阶段完成）" : "，下一阶段已自动启动（系统唤醒轮，右侧追溯可见）")));
            } catch (Exception e) {
                return ToolResult.of(Map.of("ok", false,
                        "message", "收口失败: " + TaskRunner.rootMessage(e)));
            }
        });
    }

    // ---------- 组装辅助 ----------

    private TopicBrief brief(String topic) {
        return TopicBrief.of(projectGoal, stageGoal, topic, boardDigest());
    }

    /** 黑板摘要：意图锚 + 需求全景 + 本阶段各步骤状态与产出（goal 传达的上下文来源，执行 agent 与讨论参与者同见）。 */
    public String boardDigest() {
        StringBuilder sb = new StringBuilder();
        String anchor = stepRepo.findAnchorText(wsId, stage, iteration);
        if (anchor != null && !anchor.isBlank()) {
            sb.append("【意图锚——本阶段一切工作的最高准绳，产出偏离它即走偏】\n")
              .append(truncate(anchor, 400)).append("\n");
        }
        String reqDigest = requirementDigest();
        if (!reqDigest.isEmpty()) {
            sb.append("【需求全景——项目级共识，所有工作围绕它】\n").append(reqDigest);
        }
        else {
            sb.append("【需求全景】（尚无需求项——任何实施性工作前必须先 declare_requirements 落项并请用户确认）\n");
        }
        String opsDigest = opsResourceDigest();
        if (!opsDigest.isEmpty()) {
            sb.append(opsDigest);
        }
        List<StageStep> allSteps = stepRepo.findByStage(wsId, stage, iteration);
        // 同名步重复检测（2026-10-20 全链路诊断）：「分诊讨论会」×12 的教训——113 行平铺清单里
        // LLM 看不出重复模式，继续新开同名步。这里确定性统计、显式警示，主控无法忽视。
        Map<String, Integer> nameCount = new LinkedHashMap<>();
        for (StageStep s : allSteps) {
            nameCount.merge(s.name(), 1, Integer::sum);
        }
        List<String> dupNames = nameCount.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .map(e -> "「" + e.getKey() + "」×" + e.getValue())
                .toList();
        if (!dupNames.isEmpty()) {
            sb.append("【⚠️ 重复步骤警报——硬性禁令】以下步骤名已重复出现：")
              .append(String.join("、", dupNames))
              .append("\n重复 = 流程空转。禁止再规划/新开任何与上述同名的步骤：")
              .append("\n- 讨论类重复 → 禁止再 discuss，直接基于已有结论行动（execute_step 派活或收口）；")
              .append("\n- 任务类重复 → 用 retry 复用既有失败步，或 execute_step 继续未完成的步（带明确增量目标）；")
              .append("\n- 收口/总结类重复 → 一次性完成，失败先解决失败原因（工具/路径），不要重开新步。\n");
        }
        for (StageStep s : allSteps) {
            sb.append("- [").append(s.seq()).append("] ").append(s.name())
                    .append("（").append(s.status()).append("）");
            if (!s.requirementCodes().isEmpty()) {
                sb.append("→").append(s.requirementCodes());
            }
            // 流程正义：验证成色随行透出——self=AI 自述（默认不标），tool=机器判定，human=人确认；
            // 主控与用户都能看出「这一步的完成是谁说了算」，未验证的完成不许说成已交付
            if ("tool".equals(s.verifiedBy())) {
                sb.append("【机器验证】");
            } else if ("human".equals(s.verifiedBy())) {
                sb.append("【人确认】");
            }
            if (s.outputSummary() != null && !s.outputSummary().isBlank()) {
                sb.append("：").append(truncate(s.outputSummary(), 120));
            }
            // 留痕三要素：谁记录的、什么时间、是否完结（用户要求黑板条目可追溯）
            sb.append(" —— by ").append(s.agent())
                    .append(" · ").append(s.createdAt() == null ? "?" : s.createdAt().format(TS_FMT))
                    .append(" · ").append(s.status().active() ? "进行中" : "已完结")
                    .append("\n");
        }
        return sb.length() == 0 ? "（本阶段尚无记录）" : sb.toString();
    }

    /**
     * 运维资源段：目标服务器 + 数据库地址 + 部署规范（读 OPERATE 阶段能力绑定，项目级事实全阶段可见）。
     * 新项目没有平台级数据源连接器可绑——数据库只有地址（host:port/库名+说明）；
     * 部署没有预置目录——只有服务器与部署规范（目录/启动方式由规范约定）。
     * 主控、执行 agent、讨论参与者经 boardDigest 同见，部署/排障不再靠猜。
     */
    private String opsResourceDigest() {
        List<Map<String, Object>> rows = projectRepo.findStageCapability(wsId, "OPERATE");
        if (rows.isEmpty() || rows.get(0).get("capability_json") == null) {
            return "";
        }
        Map<String, Object> cap;
        try {
            Object raw = rows.get(0).get("capability_json");
            cap = raw instanceof Map<?, ?> m
                    ? new LinkedHashMap<String, Object>((Map<String, Object>) m)
                    : objectMapper.readValue(String.valueOf(raw), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return "";
        }
        // 安全边界：地址与凭证都不进 AI 上下文——只给引用 ID + 用途说明。
        // AI 拿引用 ID 调 ops_remote_shell/db_query/db_dml，连接由平台代理（OpsConnectionManager）。
        List<Map<String, Object>> servers = mapListValue(cap.get("servers"));
        List<Map<String, Object>> databases = mapListValue(cap.get("databases"));
        String deploySpec = cap.get("deploy_spec") == null ? "" : String.valueOf(cap.get("deploy_spec")).trim();
        if (servers.isEmpty() && databases.isEmpty() && deploySpec.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【运维资源——部署/排障的目标环境，地址与凭证由平台托管，工具参数只填引用 ID】\n");
        for (int i = 0; i < servers.size(); i++) {
            String note = strValue(servers.get(i).get("note"));
            sb.append("- 服务器连接 server-").append(i + 1)
                    .append(note.isEmpty() ? "" : "（" + note + "）").append("\n");
        }
        for (int i = 0; i < databases.size(); i++) {
            String note = strValue(databases.get(i).get("note"));
            sb.append("- 数据库连接 db-").append(i + 1)
                    .append(note.isEmpty() ? "" : "（" + note + "）").append("\n");
        }
        if (!deploySpec.isEmpty()) {
            sb.append("- 部署规范：").append(truncate(deploySpec, 400)).append("\n");
        }
        return sb.toString();
    }

    /** capability 里的对象列表条目（servers/databases 新模型）。 */
    private List<Map<String, Object>> mapListValue(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add(new LinkedHashMap<String, Object>((Map<String, Object>) m));
            }
        }
        return out;
    }

    private String strValue(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** capability JSONB 里的字符串列表安全读取（PGobject/Map/字符串三种形态都兼容）。 */
    private List<String> strListValue(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> l) {
            List<String> out = new ArrayList<String>();
            for (Object o : l) {
                String v = o == null ? "" : String.valueOf(o).trim();
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
            return out;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? List.of() : List.of(s);
    }

    /** 需求全景段：编号/状态/标题/依赖——PM 规划与执行 agent 的共同依据。 */
    private String requirementDigest() {        List<RequirementRepository.Requirement> reqs = requirementRepo.findByWorkspace(wsId);
        if (reqs.isEmpty()) {
            return "";
        }
        // 流程点归属：每条需求在三阶段的链路进展（挂链步骤数/完成数）——
        // 主控执行任何一步前都知道「这条需求链走到哪了、本阶段的哪些步在服务它」，
        // 不再只看本阶段台账的井底视角（用户定调：智能体必须知道流程点内归属）
        List<StageStep> allSteps = stepRepo.findByWorkspace(wsId);
        java.util.Map<String, java.util.Map<String, int[]>> chain = new java.util.LinkedHashMap<>();
        for (StageStep s : allSteps) {
            for (String code : s.requirementCodes()) {
                chain.computeIfAbsent(code, k -> new java.util.LinkedHashMap<>())
                        .computeIfAbsent(s.stage(), k -> new int[2]);
                int[] c = chain.get(code).get(s.stage());
                c[0]++;
                if (!s.status().active()) {
                    c[1]++;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (RequirementRepository.Requirement r : reqs) {
            sb.append("- ").append(r.code()).append(" [").append(r.status()).append("] ")
                    .append(truncate(r.title(), 60));
            if (r.dependsOn() != null && !r.dependsOn().isBlank() && !"[]".equals(r.dependsOn())) {
                sb.append("（依赖 ").append(r.dependsOn()).append("）");
            }
            java.util.Map<String, int[]> byStage = chain.get(r.code());
            if (byStage != null && !byStage.isEmpty()) {
                sb.append("（链路");
                boolean first = true;
                for (String st : STAGES) {
                    int[] c = byStage.get(st);
                    if (c == null) {
                        continue;
                    }
                    sb.append(first ? "：" : " · ").append(stageShort(st)).append(" ").append(c[1]).append("/").append(c[0]);
                    first = false;
                }
                sb.append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 阶段短名（链路统计用）：REQUIREMENT→需求 / DEVELOP→开发 / OPERATE→运维。 */
    private static String stageShort(String stage) {
        return switch (stage) {
            case "REQUIREMENT" -> "需求";
            case "DEVELOP" -> "开发";
            case "OPERATE" -> "运维";
            default -> stage;
        };
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** agent 名前置校验：LLM 偶尔传中文显示名（实测「项目经理」400），提前拦截并给出正确名单。 */
    private String checkAgents(List<String> names) {
        List<String> bad = names.stream().filter(n -> !n.isBlank() && !agentNodes.agentExists(n)).toList();
        if (bad.isEmpty()) {
            return null;
        }
        return "agent 不存在: " + String.join(", ", bad) + "（可用名单: " + String.join(", ", agentNodes.agentNames()) + "，一律用英文名，不要用中文显示名）";
    }

    /** synchronized：主控常在同一轮并行发多个工具调用，max(seq)+1 有竞态（实测出现两个 seq=2）。 */
    private synchronized StageStep nextStep(String name, StepType type, String agent, List<String> participants, String instruction) {
        int seq = stepRepo.findByStage(wsId, stage, iteration).stream()
                .mapToInt(StageStep::seq).max().orElse(0) + 1;
        return new StageStep(null, wsId, stage, iteration, seq, name, type, agent,
                participants, StepStatus.PLANNED, instruction, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private Tool tool(String id, java.util.function.Function<ToolRequest, ToolResult> body) {
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return new ToolMetadata(id, id, id, io.xuanji.core.api.JsonSchema.anyObject(), null);
            }

            @Override
            public ToolResult invoke(ToolRequest request) {
                try {
                    return body.apply(request);
                } catch (Exception e) {
                    return ToolResult.of(Map.of("ok", false, "message", TaskRunner.rootMessage(e)));
                }
            }
        };
    }

    private ToolDef def(String name, String description, Map<String, Object> parameters, List<String> required) {
        Tool t = toolByName(name);
        return new ToolDef(name, description, parameters, "BEAN", null, t);
    }

    /** 按名取对应 Tool 实现（def 与实现一一对应）。 */
    private Tool toolByName(String name) {
        return switch (name) {
            case "plan_steps" -> planSteps();
            case "discuss" -> discuss();
            case "discuss_more" -> discussMore();
            case "discuss_close" -> discussClose();
            case "execute_step" -> executeStep();
            case "skip_step" -> skipStep();
            case "declare_deviation" -> declareDeviation();
            case "declare_requirements" -> declareRequirements();
            case "request_decision" -> requestDecision();
            case "record_answer" -> recordAnswer();
            case "complete_stage" -> completeStage();
            case "ask_stage" -> askStage();
            case "declare_test_cases" -> declareTestCases();
            case "settle_test_cases" -> settleTestCases();
            case "report_bug" -> reportBug();
            case "settle_bugs" -> settleBugs();
            default -> throw new IllegalArgumentException("unknown orchestration tool: " + name);
        };
    }

    /** plan_steps 入参 schema：intent_anchor + steps 数组（name/type/agent/participants/instruction/acceptance/requirement_codes）。 */
    private Map<String, Object> planStepsSchema() {
        Map<String, Object> acceptanceProps = acceptanceProps();
        Map<String, Object> stepProps = new LinkedHashMap<>();
        stepProps.put("name", str("小流程名"));
        stepProps.put("type", str("TASK=产出型 / DISCUSS=讨论型"));
        stepProps.put("agent", str("TASK 的执行 agent 英文名（agent_config.name，如 product_analyst；禁用中文显示名）；DISCUSS 可省"));
        stepProps.put("participants", arr(str("DISCUSS 参与者 agent 英文名名单（如 [\"product_analyst\",\"architect\"]；禁用中文显示名）")));
        stepProps.put("instruction", str("任务说明/讨论议题"));
        stepProps.put("acceptance", arr(obj(acceptanceProps)));
        stepProps.put("acceptance_owner", str("验收人（TASK 必填）：\"tool\"=平台机器验收（产物断言/验证命令，默认）；\"human\"=用户验收（关键交付必用：PRD/架构设计/上线报告——执行完停在待验收，用户通过才 DONE）"));
        stepProps.put("requirement_codes", arr(str("本步服务的需求项编号列表（如 [\"REQ-001\"]）——需求先行的锚定：实施步骤应声明为了哪个需求")));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("intent_anchor", str("意图锚：一段话锁定本阶段全局意图与总体验收口径（用户要什么、成功长什么样）。规划后锁定，后续每步执行都对照它防走偏；重规划时才可更新"));
        props.put("steps", arr(obj(stepProps)));
        return schema(props, List.of("intent_anchor", "steps"));
    }

    /** declare_requirements 入参 schema：action + 需求数组（code/title/description/acceptance/depends_on）。 */
    private Map<String, Object> declareRequirementsSchema() {
        Map<String, Object> reqProps = new LinkedHashMap<>();
        reqProps.put("code", str("需求编号（REQ-001；propose 可省略自动生成，confirm/update/drop 必填）"));
        reqProps.put("title", str("需求标题（一句话说清要什么）"));
        reqProps.put("description", str("需求描述：背景、边界、约束"));
        reqProps.put("acceptance", obj(acceptanceProps()));
        reqProps.put("depends_on", arr(str("依赖的需求编号列表（如 [\"REQ-001\"]）：被依赖需求完成前，本需求不得排实施步骤。批量登记时凡有前置关系的必须声明（如「时间线浏览」依赖「创建点滴记录」先有数据）——追溯图据此画依赖链，全留空=散球无链")));
        reqProps.put("parent_code", str("父需求编号（REQ-001）：本条是父需求拆出的子需求时填——交付图据此画「原始需求→拆分点」父子连线。批量登记时先识别主次：主需求（根）留空，派生需求挂父（如「可见性控制」「数据安全」多从核心功能派生）"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", str("propose=落新需求（PROPOSED）/ confirm=用户已同意后置 CONFIRMED / update=改 PROPOSED 草稿 / drop=废弃"));
        props.put("requirements", arr(obj(reqProps)));
        return schema(props, List.of("action", "requirements"));
    }

    /** 验收断言字段（plan_steps 与 declare_requirements 共用同一套口径）。 */
    private Map<String, Object> acceptanceProps() {
        Map<String, Object> acceptanceProps = new LinkedHashMap<>();
        acceptanceProps.put("type", str("验收方式：test=测试通过 / artifact=产物存在且合规 / shell=真跑命令核对输出（command+expect_contains，超时120s）/ api=接口响应符合预期"));
        acceptanceProps.put("target", str("判定对象：测试类名/产物路径/接口端点"));
        acceptanceProps.put("command", str("shell 断言专用：要在项目根目录执行的命令（如 mvn -q test、node --version）"));
        acceptanceProps.put("expect_contains", str("shell 断言专用：命令输出必须包含的期望串（如 \"Tests run: 0, Failures: 0\"）"));
        acceptanceProps.put("expect", str("可判定的预期：如 pass、含某章节、返回 200 且含某字段——禁止写「质量良好」这类不可判定描述"));
        return acceptanceProps;
    }

    private Map<String, Object> askStageSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("target_stage", str("目标阶段：REQUIREMENT / DEVELOP / OPERATE（不能是当前阶段）"));
        props.put("name", str("求助标题（如：确认服务器现状、澄清需求边界）"));
        props.put("question", str("求助内容：具体说清要对方做什么/答什么，带上下文，让对方不需要猜"));
        props.put("requirement_codes", java.util.Map.of("type", "array", "items", java.util.Map.of("type", "string"),
                "description", "本次求助涉及的需求编号（如 [\"REQ-001\"]），不涉及可省略"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("target_stage", "name", "question"));
        return schema;
    }

    private Map<String, Object> discussSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", str("讨论名"));
        props.put("topic", str("讨论议题"));
        props.put("participants", arr(str("参与方 agent 英文名名单（按问题域拉人，如需求会诊 [\"product_analyst\",\"architect\"]；串行发言每人可见前人观点；禁用中文显示名）")));
        props.put("requirement_codes", arr(str("本次讨论涉及的需求编号（如 [\"REQ-001\",\"REQ-003\"]）；讨论为了哪个需求就关联哪个，需求看板据此统计每个需求被讨论的次数")));
        return schema(props, List.of("name", "topic", "participants"));
    }

    private Map<String, Object> discussMoreSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("seq", str("要追加发言的讨论步 seq（discuss 返回的 seq）"));
        props.put("participants", arr(str("本轮发言人 agent 英文名：传多人=再来一轮串行发言；传一人=@定向回答 question")));
        props.put("question", str("@定向时要回答的具体问题（用户问的/某发言者提出的）；普通追加轮可省"));
        return schema(props, List.of("seq", "participants"));
    }

    private Map<String, Object> discussCloseSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("seq", str("要收口的讨论步 seq"));
        props.put("summary", str("你的主席总结：达成的共识（分点）/仍存的分歧/待办与责任方/需要用户澄清的问题（OPEN_QUESTION，没有写无）"));
        return schema(props, List.of("seq", "summary"));
    }

    private Map<String, Object> executeStepSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", str("任务名"));
        props.put("agent", str("执行 agent 英文名（agent_config.name，如 developer；禁用中文显示名）"));
        props.put("instruction", str("任务说明（含验收口径）"));
        props.put("requirement_codes", new LinkedHashMap<>(Map.of(
                "type", "array", "items", Map.of("type", "string"),
                "description", "本任务服务的需求项编号列表（如 [\"REQ-002\"]）。实现类任务必填——需求门禁会拒绝派发未确认（PROPOSED）需求的实现任务")));
        props.put("requirement_gate_skip", new LinkedHashMap<>(Map.of(
                "type", "boolean",
                "description", "需求门禁豁免开关：确属无需需求确认的探索性/技术验证工作时才置 true，必须同时给 requirement_gate_reason（豁免留档用户可见）")));
        props.put("requirement_gate_reason", str("门禁豁免理由（requirement_gate_skip=true 时必填，留档可见）"));
        return schema(props, List.of("name", "agent", "instruction"));
    }

    private Map<String, Object> skipStepSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("seq", str("要跳过的小流程序号"));
        props.put("reason", str("跳过理由"));
        return schema(props, List.of("seq", "reason"));
    }

    private Map<String, Object> declareDeviationSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("seq", str("偏差发生的小流程序号"));
        props.put("reason", str("为什么无法按计划完成（技术障碍/依赖缺失/成本超限）"));
        props.put("fallback", str("替代方案或降级方案的具体内容"));
        return schema(props, List.of("seq", "reason", "fallback"));
    }

    private Map<String, Object> requestDecisionSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("question", str("要用户决策的问题，含背景与选项"));
        return schema(props, List.of("question"));
    }

    private Map<String, Object> recordAnswerSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("seq", str("决策序号"));
        props.put("answer", str("用户答复要点"));
        return schema(props, List.of("seq", "answer"));
    }

    private Map<String, Object> completeStageSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("summary", str("阶段收口总结：做完了什么、产出哪些交付物、遗留什么"));
        return schema(props, List.of("summary"));
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required);
        return m;
    }

    private static Map<String, Object> obj(Map<String, Object> properties) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        return m;
    }

    private static Map<String, Object> arr(Object items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> str(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        if (desc != null && !desc.isBlank()) {
            m.put("description", desc);
        }
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String orDefault(String s, String dflt) {
        return s == null || s.isBlank() ? dflt : s;
    }

    private static int parseInt(Object o, int dflt) {
        try {
            return o == null ? dflt : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static long parseLong(Object o, long dflt) {
        try {
            return o == null ? dflt : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
