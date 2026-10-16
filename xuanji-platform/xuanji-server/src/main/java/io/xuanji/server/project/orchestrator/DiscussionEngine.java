package io.xuanji.server.project.orchestrator;

import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.server.engine.AgentNodeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 讨论型小流程引擎：结构化群聊。
 *
 * <p>协议（架构 v2 定稿）：</p>
 * <ol>
 *   <li><b>第一轮并行</b>——各参与者独立发言（互不可见，像评审会先各自写意见，
 *       观点不被带偏），N 人并行 ≈ 单次发言耗时；</li>
 *   <li><b>后续轮串行</b>——每人读完整历史后发言（真讨论：看到别人观点才有交锋），
 *       轮数由主控在发起时指定（默认 1 轮）；</li>
 *   <li><b>收敛</b>——主控（或指定汇总人）读全部发言，产出共识 + 分歧 + OPEN_QUESTION；</li>
 *   <li><b>落档</b>——纪要（谁说了什么）进 step.output_full，共识进 output_summary。</li>
 * </ol>
 *
 * <p>同步机制：讨论历史由本引擎显式管理（AgentScope 记忆按 (agentId, sessionId)
 * 隔离，跨 agent 共享必须显式传递）。整场讨论在独立线程异步进行，
 * 不阻塞主控对话；完成落黑板，主控下轮对话可见。</p>
 */
@Component
public class DiscussionEngine {

    private static final Logger log = LoggerFactory.getLogger(DiscussionEngine.class);

    /**
     * 单次发言超时（秒）。实测模型单次生成可达 2-3 分钟（qwen 慢响应），90s 会把正常发言掐死
     * （TimeoutException → 整场 FAILED）；放宽到 240s，讨论在后台跑，不占用户等待。
     */
    static final int SPEECH_TIMEOUT_SECONDS = 240;

    /**
     * 汇总超时（秒）。汇总要读全部发言（实测 1.5 万字 transcript），qwen 长输入处理慢，
     * 240s 实测不够（FAILED「汇总超时」）；放宽到 480s，同时 prompt 限制输出 500 字内缩短生成。
     */
    static final int SUMMARY_TIMEOUT_SECONDS = 480;

    private final AgentInvoker invoker;
    private final AgentNodeExecutor agentNodes;
    private final StageStepRepository stepRepo;
    private final StageDocStore docStore;

    /** 发言线程池：第一轮并行度 = 参与者数（cached 池按需扩）。 */
    private final ExecutorService speakerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stage-discuss-speaker");
        t.setDaemon(true);
        return t;
    });

    /** 整场讨论的调度线程（串行轮 + 汇总在此执行）。 */
    private final ExecutorService dispatchPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stage-discuss-dispatch");
        t.setDaemon(true);
        return t;
    });

    public DiscussionEngine(AgentInvoker invoker, AgentNodeExecutor agentNodes,
                            StageStepRepository stepRepo, StageDocStore docStore) {
        this.invoker = invoker;
        this.agentNodes = agentNodes;
        this.stepRepo = stepRepo;
        this.docStore = docStore;
    }

    /**
     * 提交一场讨论：立即返回，整场在后台进行，纪要落黑板。
     *
     * @param step         DISCUSS 型步骤（participants=参与者名单）
     * @param brief        议题包（goal + 黑板摘要）
     * @param rounds       串行轮数（0=只收集一轮独立观点；1=再加一轮互见讨论）
     * @param summarizer   汇总人 agent 名（通常主控或 PM）
     */
    public void submit(StageStep step, TopicBrief brief, int rounds, String summarizer) {
        stepRepo.updateStatus(step.withStatus(StepStatus.EXECUTING));
        dispatchPool.submit(() -> run(step, brief, rounds, summarizer));
    }

    private void run(StageStep step, TopicBrief brief, int rounds, String summarizer) {
        log.info("讨论开始: ws={} {}#{} seq={} 参与者={} 轮数={}",
                step.workspaceId(), step.stage(), step.iteration(), step.seq(),
                step.participants(), rounds);
        List<String> history = new ArrayList<>();
        try {
            // 进度行：讨论全程 10-20 分钟（qwen 每人 2-5 分钟），第一条发言落地前界面不能静默——
            // 先落一行预期说明，让用户知道系统活着、要等多久（AI 过程可见化）
            stepRepo.appendSpeech(step.id(),
                    "⏱ 讨论开始：" + step.participants().size() + " 人并行发言（每人约 2-5 分钟），"
                            + "发言逐条显示在这里，全部完成后自动汇总纪要（预计 10-20 分钟）。\n\n");
            // 第一轮：并行独立发言（互不可见）
            List<CompletableFuture<String>> firstRound = step.participants().stream()
                    .map(p -> CompletableFuture.supplyAsync(() -> speak(p, brief, step, ""), speakerPool))
                    .toList();
            for (int i = 0; i < firstRound.size(); i++) {
                String speaker = step.participants().get(i);
                // 进度行：谁还在发言中（并行起跑，逐个等完成——未完成的人保持「发言中」状态可见）
                long pending = firstRound.stream().filter(f -> !f.isDone()).count();
                if (pending > 0) {
                    stepRepo.appendSpeech(step.id(), "▶ " + pending + " 人发言中…\n\n");
                }
                String speech = firstRound.get(i).get(SPEECH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                history.add("【" + speaker + "】" + speech);
                // 逐条落库：讨论全程可能数分钟，轮询方实时看到已收集的发言（AI 过程可见化）
                stepRepo.appendSpeech(step.id(), "【" + speaker + "】" + speech + "\n\n");
            }

            // 后续轮：串行互见讨论。seenHistory 从 DB 读而非内存 history——
            // 用户插话（【用户】前缀）实时落库，后续发言者能看到并回应（用户可参与讨论）
            for (int r = 0; r < rounds; r++) {
                for (String p : step.participants()) {
                    stepRepo.appendSpeech(step.id(), "▶ " + p + " 发言中…\n\n");
                    String seen = latestSpeech(step.id());
                    String speech = speak(p, brief, step, seen);
                    history.add("【" + p + "】" + speech);
                    stepRepo.appendSpeech(step.id(), "【" + p + "】" + speech + "\n\n");
                }
            }

            // 收敛：汇总人读全部发言（与发言同款超时——invoke 是同步阻塞，无超时则模型 hang 会把讨论永远挂在 EXECUTING）
            stepRepo.appendSpeech(step.id(),
                    "⏱ 发言收集完毕（" + history.size() + " 条），正在汇总纪要…\n\n");
            // transcript 从 DB 读：含用户插话（【用户】）——用户参与讨论的意见必须进纪要；
            // 进度行（⏱/▶）也在库里，prompt 里让汇总人忽略
            String transcript = latestSpeech(step.id());
            AgentSpec sumSpec = agentNodes.buildSpecByAgentName(summarizer, null);
            java.util.concurrent.Future<String> sumFuture = speakerPool.submit(() -> invoker.invoke(sumSpec,
                    brief.render(step.name(),
                            "以下是本次讨论的全部发言（⏱/▶ 开头的是进度提示，忽略；【用户】开头的是用户本人意见，优先采纳），"
                                    + "请汇总（控制在 500 字内）：1) 达成的共识（分点）；2) 仍存的分歧；"
                                    + "3) 需要用户澄清的问题（OPEN_QUESTION，没有则写「无」）。\n\n" + transcript)));
            String consensus;
            try {
                consensus = sumFuture.get(SUMMARY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                sumFuture.cancel(true);
                throw new IllegalStateException("汇总超时（" + SUMMARY_TIMEOUT_SECONDS + "s）");
            }

            String summary = consensus.length() > 300 ? consensus.substring(0, 300) + "…" : consensus;
            String full = "===== 讨论纪要 =====\n" + transcript + "\n\n===== 汇总 =====\n" + consensus;
            stepRepo.updateStatus(step.withOutput(summary, full, null));
            // 文档双落：纪要进项目目录（docs/{stage}/iter{n}/）+ project_artifact 索引
            docStore.write(step.workspaceId(), step.stage(), step.iteration(), "DISCUSS",
                    step.seq(), step.name(), full, summarizer);
            log.info("讨论完成: ws={} seq={} 发言 {} 条", step.workspaceId(), step.seq(), history.size());
        } catch (Exception e) {
            String msg = TaskRunner.rootMessage(e);
            // withError 会用内存里的 outputFull(null) 覆盖库里的已收发言（appendSpeech 落的），
            // 先落失败状态，再把已收到的发言补回去——部分成果对用户/主控可见，不白等。
            stepRepo.updateStatus(step.withError(msg));
            if (!history.isEmpty()) {
                stepRepo.appendSpeech(step.id(),
                        "===== 中断前已收到 " + history.size() + " 条发言 =====\n" + String.join("\n\n", history) + "\n");
            }
            log.warn("讨论失败: ws={} seq={} -> {}", step.workspaceId(), step.seq(), msg);
        }
    }

    /** 当前已落库的讨论全文（含 AI 发言与用户插话）：后续轮与汇总的输入源。 */
    private String latestSpeech(long stepId) {
        return stepRepo.findOutputFull(stepId).orElse("");
    }

    /** 单人发言：读议题包（+已有历史）以角色视角发言。 */
    private String speak(String participant, TopicBrief brief, StageStep step, String seenHistory) {
        try {
            AgentSpec spec = agentNodes.buildSpecByAgentName(participant, null);
            TopicBrief withHistory = new TopicBrief(brief.projectGoal(), brief.stageGoal(),
                    brief.topic(), brief.boardDigest(), seenHistory);
            String ask = withHistory.render(step.name(),
                    (seenHistory.isBlank()
                            ? "请以你的角色就本议题给出独立观点：明确立场 + 依据 + 你关注的风险。"
                            : "请基于上方讨论历史，以你的角色补充观点：认同什么、反对什么、还缺什么。不要重复他人已说的内容。"));
            return invoker.invoke(spec, ask);
        } catch (Exception e) {
            return "（" + participant + " 发言失败: " + TaskRunner.rootMessage(e) + "）";
        }
    }
}
