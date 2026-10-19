package io.xuanji.server.project.orchestrator;

import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.server.engine.AgentNodeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 讨论引擎（主席模型 v3）：串行发言 + 主控主持 + 人为终止。
 *
 * <p>形态（2026-10-17 定稿）：讨论本来就是串行的——前一个发言，后一个能看到并补充
 * （像真实会议，不是互不可见的评审会）。主控是讨论主席：</p>
 * <ol>
 *   <li><b>discuss 发起</b>——参会人按序串行发言（每人读 DB 全量：前人观点+用户插话），
 *       发言全文同步回流主控；</li>
 *   <li><b>discuss_more 追加</b>——participants 传多人=再来一轮；传一人=<b>@定向</b>
 *       （回答用户的问题或某发言者提出的问题，focusQuestion 聚焦）；</li>
 *   <li><b>discuss_close 收口</b>——主席总结（主控自己总结，不再有 summarizer 角色），
 *       纪要=全部发言+主席总结，落档 DONE。</li>
 * </ol>
 *
 * <p><b>无超时</b>：模型想多久想多久（thinking 保留），做完为止——TimeoutException
 * 这个失败形态已废除。发言 prompt 约束 300 字内（输出效率，不是阉割思考）。</p>
 *
 * <p><b>@ 的等待语义是结构性保证</b>：主控单线程同步——讨论进行中用户消息只走插话
 * 落库（chat 入口互斥，见 StageOrchestrator.chat），主控拿到 transcript 后才发起
 * discuss_more，被 @ 者读 DB 最新全量（必然含当前发言者结论与用户问题）。</p>
 *
 * <p><b>人为终止</b>：终止端点经 {@link StepInterruption} cancel 在跑的发言——
 * 当前 LLM 调用被中断、半截发言不落库，步骤落 SKIPPED（留痕），主控收到终止信号
 * 直接进入下一步。</p>
 */
@Component
public class DiscussionEngine {

    private static final Logger log = LoggerFactory.getLogger(DiscussionEngine.class);

    private final AgentInvoker invoker;
    private final AgentNodeExecutor agentNodes;
    private final StageStepRepository stepRepo;
    private final StageDocStore docStore;
    private final ProjectEventBus projectEvents;

    /** 发言线程池：同步执行也走池——Future 注册进 StepInterruption 才有人为终止通道。 */
    private final ExecutorService speakerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "stage-discuss-speaker");
        t.setDaemon(true);
        return t;
    });

    public DiscussionEngine(AgentInvoker invoker, AgentNodeExecutor agentNodes,
                            StageStepRepository stepRepo, StageDocStore docStore,
                            ProjectEventBus projectEvents) {
        this.invoker = invoker;
        this.agentNodes = agentNodes;
        this.stepRepo = stepRepo;
        this.docStore = docStore;
        this.projectEvents = projectEvents;
    }

    /**
     * 串行发言一轮（同步，阻塞到全部发言完成）。
     *
     * @param step          DISCUSS 步（EXECUTING 态）
     * @param brief         议题包
     * @param participants  本轮发言人（按序串行）
     * @param focusQuestion 定向问题（@定向时非空；普通轮 null）
     * @return 本轮新增发言的 transcript（【发言人】内容 拼接）
     * @throws UserInterrupted 用户终止
     */
    public String runDiscussion(StageStep step, TopicBrief brief, List<String> participants, String focusQuestion) {
        StringBuilder round = new StringBuilder();
        for (String p : participants) {
            stepRepo.appendSpeech(step.id(), "▶ " + p + " 发言中…\n\n");
            // 每次发言前从 DB 读最新全量：前序发言者结论 + 用户插话都在（结论同步给它）
            String seen = latestSpeech(step.id());
            String speech;
            Future<String> f = speakerPool.submit(() -> speak(p, brief, step, seen, focusQuestion));
            StepInterruption.register(step.id(), f);
            try {
                speech = f.get(); // 无超时：想多久想多久
            } catch (java.util.concurrent.CancellationException ce) {
                throw new UserInterrupted();
            } catch (Exception e) {
                Throwable root = rootOf(e);
                if (root instanceof UserInterrupted) {
                    throw (UserInterrupted) root;
                }
                speech = "（" + p + " 发言失败: " + TaskRunner.rootMessage(e) + "）";
            } finally {
                StepInterruption.unregister(step.id());
            }
            round.append("【").append(p).append("】").append(speech).append("\n\n");
            stepRepo.appendSpeech(step.id(), "【" + p + "】" + speech + "\n\n");
        }
        return round.toString();
    }

    /**
     * 主席收口：主控的总结 + 全部发言落档 DONE。
     * 纪要结构（会议纪要三段式）：主题 → 各方观点（谁说了什么，原样保留）→ 主席总结。
     */
    public void closeDiscussion(StageStep step, String chairmanSummary) {
        String transcript = latestSpeech(step.id());
        String summary = chairmanSummary == null ? "" : chairmanSummary;
        String briefSummary = summary.length() > 300 ? summary.substring(0, 300) + "…" : summary;
        String full = "===== 会议纪要 =====\n【主题】" + step.name()
                + "\n\n【各方观点】\n" + transcript
                + "\n\n【主席总结】\n" + summary;
        stepRepo.updateStatus(step.withOutput(briefSummary, full, null));
        docStore.write(step.workspaceId(), step.stage(), step.iteration(), "DISCUSS",
                step.seq(), step.name(), full, step.agent());
        log.info("讨论收口: ws={} seq={} 总结 {} 字", step.workspaceId(), step.seq(), summary.length());
        projectEvents.publish(step.workspaceId(), "step.updated", Map.of(
                "stage", step.stage(), "seq", step.seq(), "stepType", "DISCUSS",
                "status", "DONE", "name", step.name()));
    }

    /** 用户终止落档：SKIPPED + 留痕（终止端点与执行体共用）。 */
    public void markInterrupted(StageStep step) {
        stepRepo.updateStatus(step.withStatus(StepStatus.SKIPPED).withSkip("用户终止"));
        projectEvents.publish(step.workspaceId(), "step.updated", Map.of(
                "stage", step.stage(), "seq", step.seq(), "stepType", "DISCUSS",
                "status", "SKIPPED", "name", step.name()));
    }

    /** 用户插话落库（chat 互斥路径）：【用户】前缀，后续发言者自然看到。 */
    public void userSpeech(StageStep step, String query) {
        stepRepo.appendSpeech(step.id(), "【用户】" + query + "\n\n");
    }

    /** 单人发言：读议题包+已有全部发言以角色视角发言；focusQuestion 非空=被 @ 定向回答。 */
    private String speak(String participant, TopicBrief brief, StageStep step, String seenHistory, String focusQuestion) {
        try {
            AgentSpec spec = agentNodes.buildSpecByAgentName(participant, null);
            TopicBrief withHistory = new TopicBrief(brief.projectGoal(), brief.stageGoal(),
                    brief.topic(), brief.boardDigest(), seenHistory);
            String ask;
            if (focusQuestion != null && !focusQuestion.isBlank()) {
                ask = withHistory.render(step.name(),
                        "主持人 @你 定向回答以下问题（结合上方讨论现场，直接给结论与依据）：\n" + focusQuestion
                                + "\n\n要求：直接回答，300 字内，不复述议题、不寒暄、不重复他人已说的内容。");
            } else {
                ask = withHistory.render(step.name(),
                        "请基于上方讨论现场（含用户插话），以你的角色发言：补充新观点、对他人观点表态（认同什么/反对什么/还缺什么）。"
                                + "要求：直接给观点（立场+依据+你关注的风险），300 字内，不复述议题、不寒暄、不重复他人已说的内容。"
                                + (seenHistory.isBlank() ? "你是第一个发言人，直接给独立观点。" : ""));
            }
            return invoker.invoke(spec, ask);
        } catch (Exception e) {
            return "（" + participant + " 发言失败: " + TaskRunner.rootMessage(e) + "）";
        }
    }

    /** 当前已落库的讨论全文（含 AI 发言与用户插话）。 */
    private String latestSpeech(long stepId) {
        return stepRepo.findOutputFull(stepId).orElse("");
    }

    private static Throwable rootOf(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    /** 用户终止信号：执行体捕获后落 SKIPPED 并上抛给主控工具层。 */
    public static class UserInterrupted extends RuntimeException {
        public UserInterrupted() {
            super("用户已终止本步骤");
        }
    }
}
