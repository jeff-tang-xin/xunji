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
                              RequirementRepository requirementRepo, PeerConsultService peerConsult) {
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
    }

    /** 主控可见的全部编排工具（BEAN 形态 ToolDef）。 */
    public List<ToolDef> toolDefs() {
        List<ToolDef> defs = new ArrayList<>();
        defs.add(def("plan_steps", "规划本阶段的小流程清单（做什么、顺序、谁来做）。阶段开始或需求变化时调用；会替换尚未开始执行的既有规划。必须携带 intent_anchor（全局意图锚）并为每个 TASK 步给出可判定的 acceptance 验收断言。",
                planStepsSchema(), List.of("steps")));
        defs.add(def("discuss", "发起一场多方讨论（结构化群聊）：参与者逐个发言（第一轮并行独立观点、后续轮互见讨论），最后由你汇总共识。讨论最少三方——你必须主持，participants 填目的方等参与方（至少 2 个角色，按问题域拉能解答的人）。异步进行，完成后结果进黑板。",
                discussSchema(), List.of("name", "topic", "participants")));
        defs.add(def("execute_step", "派发一个任务型小流程给指定 agent 后台执行（产出登记为交付物，8 分钟超时）。异步进行，完成后结果进黑板。instruction 必须包含该步的 acceptance 验收口径；执行 agent 遇到需求/架构疑问会通过 ask_peer 向对应角色求助，问答留档你可见。",
                executeStepSchema(), List.of("name", "agent", "instruction")));
        defs.add(def("skip_step", "跳过某个未执行的小流程，必须写明理由（留档，用户可见）。",
                skipStepSchema(), List.of("seq", "reason")));
        defs.add(def("declare_deviation", "申报偏差：某步无法按计划完成、需要换路或降级时必须先申报（原因 + 替代方案），禁止静默换路。申报后继续用 request_decision 征询用户是否接受降级方案。",
                declareDeviationSchema(), List.of("seq", "reason", "fallback")));
        defs.add(def("declare_requirements", "需求先行：把讨论沉淀的需求项落库（PROPOSED），或在用户明确同意后置为 CONFIRMED。需求项是所有实施工作的锚定物——规划步骤前必须先有确认的需求；运行中新增需求也走这里（先 propose 再请用户确认）。confirm 动作必须先有用户明确同意（用 request_decision 征询并留档），禁止未经用户确认直接置 CONFIRMED。",
                declareRequirementsSchema(), List.of("action", "requirements")));
        defs.add(def("request_decision", "向用户提出决策请求（问题会显示在右侧决策区，用户答复后你在对话里收到）。",
                requestDecisionSchema(), List.of("question")));
        defs.add(def("record_answer", "把用户在对话里给出的答复落到对应决策上（决策闭环）。",
                recordAnswerSchema(), List.of("seq", "answer")));
        defs.add(def("complete_stage", "宣告本阶段完成并推进到下一阶段。前提：无执行中/等待中的小流程；收口总结会留档。交付物有缺口时必须先补齐或说明豁免理由。",
                completeStageSchema(), List.of("summary")));
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
            int seq = 1;
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
                StepType type = "DISCUSS".equalsIgnoreCase(str(m.get("type"))) ? StepType.DISCUSS : StepType.TASK;
                @SuppressWarnings("unchecked")
                List<String> participants = m.get("participants") instanceof List<?> pl
                        ? pl.stream().map(String::valueOf).toList() : List.of();
                // acceptance 断言：可判定的验收标准（test/artifact/api），规划时锁定，执行链不可改
                String acceptanceJson = toJson(m.get("acceptance"));
                // 需求先行：步骤声明服务哪些需求项（code 列表，可空）
                @SuppressWarnings("unchecked")
                List<String> reqCodes = m.get("requirement_codes") instanceof List<?> rl
                        ? rl.stream().map(String::valueOf).toList() : List.of();
                rows.add(new StageStep(null, wsId, stage, iteration, seq++, name, type,
                        str(m.get("agent")), participants, StepStatus.PLANNED,
                        str(m.get("instruction")), null, null, null, null, null, null, null, null,
                        null, acceptanceJson, null, reqCodes));
            }
            stepRepo.replacePlanned(wsId, stage, iteration, rows);
            stepRepo.saveAnchor(wsId, stage, iteration, anchor);
            return ToolResult.of(Map.of("ok", true, "planned", rows.size(),
                    "message", "规划已登记，共 " + rows.size() + " 个小流程；意图锚已锁定"));
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
                                "PROPOSED", depsJson, "INCUBATION", user.username(), null, null));
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
                                existing.source(), existing.createdBy(), existing.createdAt(), existing.updatedAt()));
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
            return ToolResult.of(Map.of("ok", true, "message",
                    "需求已落档: " + String.join("；", results)
                            + ("propose".equals(action) ? "。请用 request_decision 请用户确认后再规划实施" : "")));
        });
    }

    /** 自动生成下一个需求编号：REQ-001 起，取现有最大序号 +1。 */
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

    private Tool discuss() {
        return tool("discuss", req -> {
            String name = str(req.input().get("name"));
            String topic = str(req.input().get("topic"));
            List<String> participants = req.input().get("participants") instanceof List<?> pl
                    ? pl.stream().map(String::valueOf).toList() : List.of();
            if (name.isBlank() || topic.isBlank() || participants.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "name/topic/participants 必填"));
            }
            // 三方编制：讨论最少三人——PM（你）必须主持，其余覆盖发起方与目的方。
            // participants 由主控填目的方等参与方（≥2），PM 自动加入发言与汇总
            List<String> roster = new ArrayList<>(participants);
            if (!roster.contains("project_manager")) {
                roster.add("project_manager");
            }
            if (roster.size() < 3) {
                return ToolResult.of(Map.of("ok", false, "message",
                        "讨论最少三方（PM 主持 + 发起方 + 目的方）：participants 至少 2 个角色，"
                                + "按问题域拉能解答的人（需求→product_analyst，架构→architect，实现→developer）"));
            }
            String badAgents = checkAgents(roster);
            if (badAgents == null) {
                badAgents = checkAgents(List.of(orDefault(str(req.input().get("summarizer")), "project_manager")));
            }
            if (badAgents != null) {
                return ToolResult.of(Map.of("ok", false, "message", badAgents));
            }
            int rounds = parseInt(req.input().get("rounds"), 0);
            String summarizer = orDefault(str(req.input().get("summarizer")), "project_manager");
            StageStep step = nextStep(name, StepType.DISCUSS, summarizer, roster, topic);
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
            discussionEngine.submit(step.withId(id), brief(topic), rounds, summarizer);
            return ToolResult.of(Map.of("ok", true, "seq", step.seq(),
                    "message", "讨论已发起（" + roster.size() + " 人含 PM 主持，后台进行中），完成后结果进黑板"));
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
            peerConsult.askStageAsync(launch.withId(launchId), support.withId(supportId),
                    targetAgent, stage, question, brief(question));
            return ToolResult.of(Map.of("ok", true, "seq", launch.seq(),
                    "message", "已向 " + targetStage + " 阶段的 " + targetAgent + " 发起支援请求（后台处理中），"
                            + "答复后进黑板与对话流，问答双落文档"));
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
            StageStep step = nextStep(name, StepType.TASK, agent, List.of(), instruction);
            long id = stepRepo.insert(step);
            taskRunner.submit(step.withId(id), brief(""));
            return ToolResult.of(Map.of("ok", true, "seq", step.seq(),
                    "message", "任务已派发给 " + agent + "（后台执行中，3 分钟超时），完成后结果进黑板"));
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
            StageStep step = nextStep("决策：" + truncate(question, 40), StepType.DECISION,
                    "user", List.of(), question);
            stepRepo.insert(step);
            return ToolResult.of(Map.of("ok", true, "seq", step.seq(),
                    "message", "已向用户提出决策请求，等待答复（答复后用 record_answer 落档）"));
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
            stepRepo.updateStatus(found.get().withAnswer(answer));
            return ToolResult.of(Map.of("ok", true, "message", "决策已闭环"));
        });
    }

    private Tool completeStage() {
        return tool("complete_stage", req -> {
            String summary = str(req.input().get("summary"));
            if (stepRepo.hasActiveStep(wsId, stage, iteration)) {
                return ToolResult.of(Map.of("ok", false,
                        "message", "仍有执行中/等待中的小流程，不能收口；先等它们完成或处理掉"));
            }
            try {
                Map<String, Object> advanced = advancer.complete(wsId, stage, summary);
                return ToolResult.of(Map.of("ok", true,
                        "next_stage", String.valueOf(advanced.getOrDefault("current_stage", "")),
                        "message", "阶段已收口并推进到 " + advanced.getOrDefault("current_stage", "下一阶段")));
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
        for (StageStep s : stepRepo.findByStage(wsId, stage, iteration)) {
            sb.append("- [").append(s.seq()).append("] ").append(s.name())
                    .append("（").append(s.status()).append("）");
            if (!s.requirementCodes().isEmpty()) {
                sb.append("→").append(s.requirementCodes());
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

    /** 需求全景段：编号/状态/标题/依赖——PM 规划与执行 agent 的共同依据。 */
    private String requirementDigest() {
        List<RequirementRepository.Requirement> reqs = requirementRepo.findByWorkspace(wsId);
        if (reqs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (RequirementRepository.Requirement r : reqs) {
            sb.append("- ").append(r.code()).append(" [").append(r.status()).append("] ")
                    .append(truncate(r.title(), 60));
            if (r.dependsOn() != null && !r.dependsOn().isBlank() && !"[]".equals(r.dependsOn())) {
                sb.append("（依赖 ").append(r.dependsOn()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
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
            case "execute_step" -> executeStep();
            case "skip_step" -> skipStep();
            case "declare_deviation" -> declareDeviation();
            case "declare_requirements" -> declareRequirements();
            case "request_decision" -> requestDecision();
            case "record_answer" -> recordAnswer();
            case "complete_stage" -> completeStage();
            case "ask_stage" -> askStage();
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
        reqProps.put("depends_on", arr(str("依赖的需求编号列表（如 [\"REQ-001\"]）：被依赖需求完成前，本需求不得排实施步骤")));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", str("propose=落新需求（PROPOSED）/ confirm=用户已同意后置 CONFIRMED / update=改 PROPOSED 草稿 / drop=废弃"));
        props.put("requirements", arr(obj(reqProps)));
        return schema(props, List.of("action", "requirements"));
    }

    /** 验收断言字段（plan_steps 与 declare_requirements 共用同一套口径）。 */
    private Map<String, Object> acceptanceProps() {
        Map<String, Object> acceptanceProps = new LinkedHashMap<>();
        acceptanceProps.put("type", str("验收方式：test=测试通过 / artifact=产物存在且合规 / api=接口响应符合预期"));
        acceptanceProps.put("target", str("判定对象：测试类名/产物路径/接口端点"));
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
        props.put("participants", arr(str("参与方 agent 英文名名单（至少 2 个：按问题域拉能解答的目的方，如需求会诊 [\"product_analyst\",\"architect\"]；PM 会自动加入主持与汇总，不用写；禁用中文显示名）")));
        props.put("rounds", str("互见讨论轮数，默认 0（只收集一轮独立观点）"));
        props.put("summarizer", str("汇总人 agent 英文名，默认 project_manager（你主持并汇总）"));
        props.put("requirement_codes", arr(str("本次讨论涉及的需求编号（如 [\"REQ-001\",\"REQ-003\"]）；讨论为了哪个需求就关联哪个，需求看板据此统计每个需求被讨论的次数")));
        return schema(props, List.of("name", "topic", "participants"));
    }

    private Map<String, Object> executeStepSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", str("任务名"));
        props.put("agent", str("执行 agent 英文名（agent_config.name，如 developer；禁用中文显示名）"));
        props.put("instruction", str("任务说明（含验收口径）"));
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
