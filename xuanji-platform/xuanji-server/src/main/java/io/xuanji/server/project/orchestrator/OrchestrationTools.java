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

    public OrchestrationTools(LoginUser user, String wsId, String stage, int iteration,
                              String projectGoal, String stageGoal,
                              StageStepRepository stepRepo, TaskRunner taskRunner,
                              DiscussionEngine discussionEngine, ProjectRepository projectRepo,
                              StageAdvancer advancer, io.xuanji.server.engine.AgentNodeExecutor agentNodes) {
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
    }

    /** 主控可见的全部编排工具（BEAN 形态 ToolDef）。 */
    public List<ToolDef> toolDefs() {
        List<ToolDef> defs = new ArrayList<>();
        defs.add(def("plan_steps", "规划本阶段的小流程清单（做什么、顺序、谁来做）。阶段开始或需求变化时调用；会替换尚未开始执行的既有规划。",
                planStepsSchema(), List.of("steps")));
        defs.add(def("discuss", "发起一场多方讨论（结构化群聊）：参与者逐个发言（第一轮并行独立观点、后续轮互见讨论），最后汇总共识。异步进行，完成后结果进黑板。",
                discussSchema(), List.of("name", "topic", "participants")));
        defs.add(def("execute_step", "派发一个任务型小流程给指定 agent 后台执行（产出登记为交付物，3 分钟超时）。异步进行，完成后结果进黑板。",
                executeStepSchema(), List.of("name", "agent", "instruction")));
        defs.add(def("skip_step", "跳过某个未执行的小流程，必须写明理由（留档，用户可见）。",
                skipStepSchema(), List.of("seq", "reason")));
        defs.add(def("request_decision", "向用户提出决策请求（问题会显示在右侧决策区，用户答复后你在对话里收到）。",
                requestDecisionSchema(), List.of("question")));
        defs.add(def("record_answer", "把用户在对话里给出的答复落到对应决策上（决策闭环）。",
                recordAnswerSchema(), List.of("seq", "answer")));
        defs.add(def("complete_stage", "宣告本阶段完成并推进到下一阶段。前提：无执行中/等待中的小流程；收口总结会留档。交付物有缺口时必须先补齐或说明豁免理由。",
                completeStageSchema(), List.of("summary")));
        return defs;
    }

    // ---------- 工具实现（每个原语一个 Tool，行为收敛、可独立测试） ----------

    private Tool planSteps() {
        return tool("plan_steps", req -> {
            Object stepsObj = req.input().get("steps");
            if (!(stepsObj instanceof List<?> steps) || steps.isEmpty()) {
                return ToolResult.of(Map.of("ok", false, "message", "steps 不能为空"));
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
                rows.add(new StageStep(null, wsId, stage, iteration, seq++, name, type,
                        str(m.get("agent")), participants, StepStatus.PLANNED,
                        str(m.get("instruction")), null, null, null, null, null, null, null, null));
            }
            stepRepo.replacePlanned(wsId, stage, iteration, rows);
            return ToolResult.of(Map.of("ok", true, "planned", rows.size(),
                    "message", "规划已登记，共 " + rows.size() + " 个小流程"));
        });
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
            String badAgents = checkAgents(participants);
            if (badAgents == null) {
                badAgents = checkAgents(List.of(orDefault(str(req.input().get("summarizer")), "project_manager")));
            }
            if (badAgents != null) {
                return ToolResult.of(Map.of("ok", false, "message", badAgents));
            }
            int rounds = parseInt(req.input().get("rounds"), 0);
            String summarizer = orDefault(str(req.input().get("summarizer")), "project_manager");
            StageStep step = nextStep(name, StepType.DISCUSS, summarizer, participants, topic);
            long id = stepRepo.insert(step);
            discussionEngine.submit(step.withId(id), brief(topic), rounds, summarizer);
            return ToolResult.of(Map.of("ok", true, "seq", step.seq(),
                    "message", "讨论已发起（" + participants.size() + " 人，后台进行中），完成后结果进黑板"));
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

    /** 黑板摘要：本阶段各步骤状态与产出（goal 传达的上下文来源）。 */
    public String boardDigest() {
        StringBuilder sb = new StringBuilder();
        for (StageStep s : stepRepo.findByStage(wsId, stage, iteration)) {
            sb.append("- [").append(s.seq()).append("] ").append(s.name())
                    .append("（").append(s.status()).append("）");
            if (s.outputSummary() != null && !s.outputSummary().isBlank()) {
                sb.append("：").append(truncate(s.outputSummary(), 120));
            }
            sb.append("\n");
        }
        return sb.length() == 0 ? "（本阶段尚无记录）" : sb.toString();
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
                participants, StepStatus.PLANNED, instruction, null, null, null, null, null, null, null, null);
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
            case "request_decision" -> requestDecision();
            case "record_answer" -> recordAnswer();
            case "complete_stage" -> completeStage();
            default -> throw new IllegalArgumentException("unknown orchestration tool: " + name);
        };
    }

    /** plan_steps 入参 schema：steps 数组，元素为 name/type/agent/participants/instruction。 */
    private Map<String, Object> planStepsSchema() {
        Map<String, Object> stepProps = new LinkedHashMap<>();
        stepProps.put("name", str("小流程名"));
        stepProps.put("type", str("TASK=产出型 / DISCUSS=讨论型"));
        stepProps.put("agent", str("TASK 的执行 agent 英文名（agent_config.name，如 product_analyst；禁用中文显示名）；DISCUSS 可省"));
        stepProps.put("participants", arr(str("DISCUSS 参与者 agent 英文名名单（如 [\"product_analyst\",\"architect\"]；禁用中文显示名）")));
        stepProps.put("instruction", str("任务说明/讨论议题"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("steps", arr(obj(stepProps)));
        return schema(props, List.of("steps"));
    }

    private Map<String, Object> discussSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", str("讨论名"));
        props.put("topic", str("讨论议题"));
        props.put("participants", arr(str("参与者 agent 英文名名单（如 [\"product_analyst\",\"architect\"]；禁用中文显示名）")));
        props.put("rounds", str("互见讨论轮数，默认 0（只收集一轮独立观点）"));
        props.put("summarizer", str("汇总人 agent 英文名，默认 project_manager"));
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
