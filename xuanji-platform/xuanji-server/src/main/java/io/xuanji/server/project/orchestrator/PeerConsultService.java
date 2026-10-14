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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 执行中求助（ask_peer）——讨论式开发的执行侧配套。
 *
 * <p><b>三方结构</b>：发起方（当前执行 agent）+ 目的方（target_agent，能解答的角色）
 * + PM（意图锚注入目的方 prompt 对照回答 + 问答落台账 + 主控下轮对话可见可审计）。
 * PM 不实时发言——执行 agent 总超时 8 分钟，三方实时讨论（4-6 分钟）会挤爆干活时间；
 * PM 的在场形态 = 锚在场 + 台账在场 + 事后审计（发现需求矛盾升级 request_decision）。</p>
 *
 * <p><b>防失控</b>：① 每个执行步最多求助 3 次（按台账 CONSULT 步计数）；
 * ② 目的方是裸 spec（不挂 ask_peer），物理上无递归；③ 目的方直答超时 240s，
 * 超时返回失败提示而不是挂死执行 agent 的 ReAct 循环。</p>
 *
 * <p><b>独立线程池</b>：目的方 invoke 不能用 TaskRunner.workPool——执行 agent 的
 * ReAct 循环本身占着 workPool 线程等 ask_peer 返回，复用会自我死锁。</p>
 */
@Component
public class PeerConsultService {

    private static final Logger log = LoggerFactory.getLogger(PeerConsultService.class);

    /** 单个执行步的求助次数上限。 */
    static final int MAX_PER_STEP = 3;

    /** 目的方直答超时（秒）——与 DiscussionEngine 单次发言同款（qwen 单次可达 2-3 分钟）。 */
    static final int ANSWER_TIMEOUT_SECONDS = 240;

    /** CONSULT 台账步的 name 前缀（计数与识别用）。 */
    static final String CONSULT_PREFIX = "求助·";

    private final AgentInvoker invoker;
    private final AgentNodeExecutor agentNodes;
    private final StageStepRepository stepRepo;

    /** 求助线程池：cached 按需扩（求助低频，目的方是裸 invoke 无递归）。 */
    private final ExecutorService consultPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stage-peer-consult");
        t.setDaemon(true);
        return t;
    });

    public PeerConsultService(AgentInvoker invoker, AgentNodeExecutor agentNodes,
                              StageStepRepository stepRepo) {
        this.invoker = invoker;
        this.agentNodes = agentNodes;
        this.stepRepo = stepRepo;
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
                "向能解答的同事求助：执行中遇到需求不清楚、架构疑问、技术难题时，直接问对应角色"
                        + "（需求问题→product_analyst，架构/技术选型→architect，数据库→dba），拿到答案再继续，禁止靠猜。"
                        + "问答会留档，主控可见。",
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
            answer = CompletableFuture.supplyAsync(() -> invoker.invoke(targetSpec, prompt), consultPool)
                    .get(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            return ToolResult.of(Map.of("ok", false, "message",
                    "对方超时未答（" + ANSWER_TIMEOUT_SECONDS + "s）：可稍后重试一次，或换一个能解答的角色"));
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
    }

    static String consultPrefix(int seq) {
        return CONSULT_PREFIX + "步骤#" + seq + "·";
    }

    private Map<String, Object> askPeerSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("target_agent", str("目的方 agent 英文名（需求问题→product_analyst，架构/技术→architect，数据库→dba；禁用中文显示名）"));
        props.put("question", str("要问的具体问题：带上下文（你在做什么、卡在哪、已试过什么），让对方不需要猜"));
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
