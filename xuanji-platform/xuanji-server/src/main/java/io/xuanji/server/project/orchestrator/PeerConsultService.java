package io.xuanji.server.project.orchestrator;

import io.xuanji.core.api.Tool;
import io.xuanji.core.api.ToolResult;
import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.core.runtime.ToolDef;
import io.xuanji.server.engine.AgentNodeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行中求助（ask_peer）——讨论式开发的执行侧配套。
 *
 * <p><b>三方结构</b>：发起方（当前执行 agent）+ 目的方（target_agent，能解答的角色）
 * + PM（意图锚注入目的方 prompt 对照回答 + 问答落台账 + 主控可见可审计）。
 * PM 的在场形态 = 锚在场 + 台账在场 + 事后审计（发现需求矛盾升级 request_decision）。</p>
 *
 * <p><b>防失控</b>：① 每个执行步最多求助 3 次（按台账 CONSULT 步计数）；
 * ② 目的方是裸 spec（不挂 ask_peer），物理上无递归。直答无超时（2026-10-17 同步化：
 * 做完为止，答案直接回调用方）。</p>
 *
 * <p><b>独立线程池</b>：目的方 invoke 不能用 TaskRunner.workPool——执行 agent 的
 * ReAct 循环本身占着 workPool 线程等 ask_peer 返回，复用会自我死锁。</p>
 */
@Component
public class PeerConsultService {

    private static final Logger log = LoggerFactory.getLogger(PeerConsultService.class);

    /** 单个执行步的求助次数上限。 */
    static final int MAX_PER_STEP = 3;

    /** CONSULT 台账步的 name 前缀（计数与识别用）。 */
    static final String CONSULT_PREFIX = "求助·";

    private final AgentInvoker invoker;
    private final AgentNodeExecutor agentNodes;
    private final StageStepRepository stepRepo;
    private final StageDocStore docStore;

    public PeerConsultService(AgentInvoker invoker, AgentNodeExecutor agentNodes,
                              StageStepRepository stepRepo, StageDocStore docStore) {
        this.invoker = invoker;
        this.agentNodes = agentNodes;
        this.stepRepo = stepRepo;
        this.docStore = docStore;
    }

    /**
     * 构造 ask_peer 会话级工具（BEAN 形态，闭包捕获当前执行步与议题包）。
     * 由 TaskRunner 挂进执行 agent 的 resolvedTools；目的方 spec 不挂（无递归）。
     */
    public ToolDef askPeerDef(StageStep step, TopicBrief brief) {
        Tool tool = new Tool() {
            @Override
            public io.xuanji.core.api.ToolMetadata metadata() {
                return new io.xuanji.core.api.ToolMetadata("ask_peer", "ask_peer", "ask_peer",
                        io.xuanji.core.api.JsonSchema.anyObject(), null);
            }

            @Override
            public ToolResult invoke(io.xuanji.core.api.ToolRequest request) {
                try {
                    return ask(step, brief, request);
                } catch (Exception e) {
                    return ToolResult.of(Map.of("ok", false,
                            "message", TaskRunner.rootMessage(e)));
                }
            }
        };
        return new ToolDef("ask_peer",
                "向能解答的同事求助：执行中遇到职责外的问题，直接问对应角色，拿到答案再继续，禁止靠猜。"
                        + "路由表：需求口径/验收标准→product_analyst，架构/技术选型/接口契约→architect，"
                        + "代码实现→developer，测试用例/验收→tester，环境/部署/服务器→devops，数据库/SQL→dba。"
                        + "提问给足上下文（问题、背景、期望产出）。问答会留档，主控可见。",
                askPeerSchema(), "BEAN", null, tool);
    }

    /** ask_peer 执行体：校验 → 目的方直答（锚注入）→ CONSULT 台账 → 返回答案。 */
    private ToolResult ask(StageStep step, TopicBrief brief, io.xuanji.core.api.ToolRequest req) {
        String target = str(req.input().get("target_agent"));
        String question = str(req.input().get("question"));
        if (target.isBlank() || question.isBlank()) {
            return ToolResult.of(Map.of("ok", false, "message", "target_agent/question 必填"));
        }
        if (target.equalsIgnoreCase(step.agent())) {
            return ToolResult.of(Map.of("ok", false, "message",
                    "不能向自己求助：需求问题找 product_analyst，架构问题找 architect"));
        }
        if (!agentNodes.agentExists(target)) {
            return ToolResult.of(Map.of("ok", false, "message",
                    "agent 不存在: " + target + "（可用名单: " + String.join(", ", agentNodes.agentNames()) + "）"));
        }
        String namePrefix = consultPrefix(step.seq());
        long asked = stepRepo.findByStage(step.workspaceId(), step.stage(), step.iteration()).stream()
                .filter(s -> s.name() != null && s.name().startsWith(namePrefix))
                .count();
        if (asked >= MAX_PER_STEP) {
            return ToolResult.of(Map.of("ok", false, "message",
                    "本步骤求助已达上限（" + MAX_PER_STEP + " 次）：反复求助说明任务边界或规划有问题，"
                            + "请基于已有答案继续，或让主控重新规划该步"));
        }

        // 目的方直答：锚 + 议题上下文 + 问题——对照锚回答，直接给结论与依据
        String prompt = "【求助】你是被同事 @ 来解答问题的（目的方）。\n"
                + brief.render("解答同事的提问", null)
                + "\n【发起人】" + step.agent() + "（正在执行：步骤#" + step.seq() + " " + step.name() + "）\n"
                + "【问题】\n" + question + "\n\n"
                + "【回答要求】直接给结论与依据，不要寒暄；若问题超出你的职责域，说明该找谁；"
                + "若答案与意图锚冲突，明确指出冲突点。";

        String answer;
        try {
            AgentSpec targetSpec = agentNodes.buildSpecByAgentName(target, null);
            // 无超时（2026-10-17）：对方想多久想多久（thinking 保留），做完为止
            answer = invoker.invoke(targetSpec, prompt);
        } catch (Exception e) {
            return ToolResult.of(Map.of("ok", false, "message",
                    "求助失败: " + TaskRunner.rootMessage(e)));
        }

        // CONSULT 台账：发起方=agent，目的方=participants，问答全文留档（主控下轮 boardDigest 可见）
        List<String> reqCodes = req.input().get("requirement_codes") instanceof List<?> rl
                ? rl.stream().map(String::valueOf).filter(c -> !c.isBlank()).toList() : List.of();
        if (reqCodes.isEmpty()) {
            // 兜底：LLM 漏传时从问题文本提取 REQ-xxx
            reqCodes = StageStep.extractRequirementCodes(question);
        }
        saveConsultStep(step, target, question, answer, reqCodes);
        log.info("peer consult: ws={} seq={} {} -> {} ({} 字)", step.workspaceId(), step.seq(),
                step.agent(), target, answer.length());
        return ToolResult.of(Map.of(
                "ok", true,
                "answer", answer,
                "message", "已获得 " + target + " 的解答（问答已留档，主控可见）"));
    }

    /** 求助留档：独立 CONSULT 步，DONE 态，不参与执行计数但进黑板摘要；requirementCodes 关联需求（看板统计澄清次数）。 */
    private void saveConsultStep(StageStep from, String target, String question, String answer, List<String> reqCodes) {
        int seq = stepRepo.findByStage(from.workspaceId(), from.stage(), from.iteration()).stream()
                .mapToInt(StageStep::seq).max().orElse(0) + 1;
        String name = consultPrefix(from.seq()) + "步骤#" + from.seq() + "·" + target + "·"
                + (question.length() > 24 ? question.substring(0, 24) + "…" : question);
        String full = "【问】(" + from.agent() + ")\n" + question + "\n\n【答】(" + target + ")\n" + answer;
        StageStep consult = new StageStep(null, from.workspaceId(), from.stage(), from.iteration(),
                seq, name, StepType.CONSULT, from.agent(), List.of(target), StepStatus.DONE,
                question, answer.length() > 300 ? answer.substring(0, 300) + "…" : answer,
                full, null, null, null, null, null, null, null, null, null, reqCodes);
        stepRepo.insert(consult);
        // 文档双落：问答全文进项目目录 + project_artifact 索引
        docStore.write(from.workspaceId(), from.stage(), from.iteration(), "CONSULT",
                seq, name, full, from.agent());
    }

    /**
     * 跨阶段求助（ask_stage）执行体：异步直答 → 双阶段台账 → 文档双落。
     *
     * <p>与 {@link #ask}（同阶段执行中求助，同步等答案）互补：跨阶段求助由主控发起、
     * 异步进行（发起阶段不阻塞，可继续规划/讨论），目标阶段 agent 直答后结果回流——
     * 发起阶段 CONSULT 步 DONE（聊天框可见），目标阶段留支援记录，问答双落文档。</p>
     *
     * @param launchStep  发起阶段的 CONSULT 步（已 insert，PLANNED 态）
     * @param supportStep 目标阶段的支援记录步（已 insert，PLANNED 态）
     * @param targetAgent 目标阶段负责 agent
     * @param fromStage   发起阶段名
     * @param question    求助内容
     * @param brief       议题包（发起阶段意图锚 + 需求全景，供目标 agent 对照回答）
     */
    public String askStageSync(StageStep launchStep, StageStep supportStep, String targetAgent,
                               String fromStage, String question, TopicBrief brief) {
        String prompt = "【跨阶段支援】你是 " + targetAgent + "（" + supportStep.stage()
                + " 阶段负责角色），另一阶段（" + fromStage + "）的团队向你求助。\n"
                + brief.render("解答兄弟阶段的提问", null)
                + "\n【求助方】" + fromStage + " 阶段主控\n"
                + "【求助内容】\n" + question + "\n\n"
                + "【回答要求】以你所在阶段的职责域直接给出可执行的结论/操作/依据，不要寒暄；"
                + "若求助超出你的职责域，说明该由哪个阶段接手；若与意图锚冲突，明确指出冲突点。";
        try {
            AgentSpec targetSpec = agentNodes.buildSpecByAgentName(targetAgent, null);
            // 无超时（2026-10-17 同步化）：直答做完为止，答案直接回主控
            String answer = invoker.invoke(targetSpec, prompt);
            String full = "【问】(" + fromStage + " 阶段)\n" + question + "\n\n【答】(" + targetAgent + ")\n" + answer;
            String summary = answer.length() > 300 ? answer.substring(0, 300) + "…" : answer;
            // 发起阶段：问答留档（聊天框 process 卡片可见，boardDigest 可见）
            stepRepo.updateStatus(launchStep.withOutput(summary, full, null));
            // 目标阶段：支援记录（该阶段台账/聊天框同样可见）
            stepRepo.updateStatus(supportStep.withOutput(summary, full, null));
            // 文档双落（落在发起阶段目录——求助产出归发起方）
            docStore.write(launchStep.workspaceId(), launchStep.stage(), launchStep.iteration(),
                    "CONSULT", launchStep.seq(), launchStep.name(), full, targetAgent);
            log.info("跨阶段求助完成: ws={} {} -> {} ({} 字)", launchStep.workspaceId(),
                    fromStage, supportStep.stage(), answer.length());
            return answer;
        } catch (Exception e) {
            String msg = TaskRunner.rootMessage(e);
            stepRepo.updateStatus(launchStep.withError(msg));
            stepRepo.updateStatus(supportStep.withError(msg));
            log.warn("跨阶段求助失败: ws={} {} -> {} -> {}", launchStep.workspaceId(),
                    fromStage, supportStep.stage(), msg);
            throw new IllegalStateException("跨阶段求助失败: " + msg, e);
        }
    }

    static String consultPrefix(int seq) {
        return CONSULT_PREFIX + "步骤#" + seq + "·";
    }

    private Map<String, Object> askPeerSchema() {
        // JSON Schema 的 properties 值必须是对象（{"type":...,"description":...}）——
        // 曾经直接放纯字符串（str(...)），请求体 schema 非法，网关上游 400 InvalidParameter，
        // 且只有挂 ask_peer 的任务型步骤全挂（讨论/主控不挂此工具，无法对照）——实测 seq=9/10/11 全灭
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("target_agent", java.util.Map.of("type", "string",
                "description", "目的方 agent 英文名（需求问题→product_analyst，架构/技术→architect，数据库→dba；禁用中文显示名）"));
        props.put("question", java.util.Map.of("type", "string",
                "description", "要问的具体问题：带上下文（你在做什么、卡在哪、已试过什么），让对方不需要猜"));
        props.put("requirement_codes", java.util.Map.of("type", "array", "items", java.util.Map.of("type", "string"),
                "description", "本次提问涉及的需求编号（如 [\"REQ-001\"]），涉及多个需求就都列上；不涉及具体需求可省略。需求看板会据此统计每个需求被澄清的次数"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("target_agent", "question"));
        return schema;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
