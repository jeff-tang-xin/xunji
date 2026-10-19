package io.xuanji.server.project.orchestrator;

import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.server.engine.AgentNodeExecutor;
import io.xuanji.server.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Map;

/**
 * 任务型小流程执行器：单 agent 后台执行，产出登记为交付物。
 *
 * <p>执行模型（2026-10-17 同步化）：主控调 execute_step → {@link #runSync} 在主控
 * 工具调用线程内阻塞执行到完成（无超时，thinking 保留），产出经返回值直接回到主控手里；
 * 失败原因同样直接上抛给主控——当场决策重试/换路/跳过，不再依赖唤醒链路。
 * {@link #submit} 仅人工重试端点使用（后台跑 + 完成唤醒主控）。</p>
 *
 * <p>人为终止：Future 注册进 {@link StepInterruption}，终止端点 cancel 后落 SKIPPED
 * 并抛 {@link DiscussionEngine.UserInterrupted}，主控直接进入下一步。</p>
 */
@Component
public class TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final AgentInvoker invoker;
    private final AgentNodeExecutor agentNodes;
    private final StageStepRepository stepRepo;
    private final ProjectRepository projectRepo;
    private final PeerConsultService peerConsult;
    private final RequirementRepository requirementRepo;
    private final StageDocStore docStore;
    private final StageOrchestrator orchestrator;
    private final ProjectEventBus projectEvents;
    private final StageChatEventBus chatEvents;
    private final OpsConnectionManager ops;

    /** 双池防自等死锁：调度池跑 run（含限时等待），工作池跑阻塞的 invoke。
     * 工作池 4 线程：单线程时一个 8 分钟任务就把全平台任务型步骤串成队，多项目并发必排队。 */
    private final ExecutorService dispatchPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stage-task-dispatch");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService workPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "stage-task-worker");
        t.setDaemon(true);
        return t;
    });

    public TaskRunner(AgentInvoker invoker, AgentNodeExecutor agentNodes,
                      StageStepRepository stepRepo, ProjectRepository projectRepo,
                      PeerConsultService peerConsult, RequirementRepository requirementRepo,
                      StageDocStore docStore, ProjectEventBus projectEvents,
                      StageChatEventBus chatEvents, OpsConnectionManager ops,
                      @org.springframework.context.annotation.Lazy StageOrchestrator orchestrator) {
        this.invoker = invoker;
        this.agentNodes = agentNodes;
        this.stepRepo = stepRepo;
        this.projectRepo = projectRepo;
        this.peerConsult = peerConsult;
        this.requirementRepo = requirementRepo;
        this.docStore = docStore;
        this.projectEvents = projectEvents;
        this.chatEvents = chatEvents;
        this.ops = ops;
        this.orchestrator = orchestrator;
    }

    /**
     * 提交后台执行（人工重试端点专用）：立即返回，结果落黑板 + 唤醒主控。
     * 主控的 execute_step 已改走 {@link #runSync} 同步链路（产出直接回主控手里）。
     */
    public void submit(StageStep step, TopicBrief brief) {
        dispatchPool.submit(() -> {
            try {
                runSync(step, brief);
                orchestrator.wakeIfSettled(step.workspaceId(), step.stage(), step.iteration(),
                        "任务 #" + step.seq() + "「" + step.name() + "」已完成（人工重试），产出已留档。请消化后继续推进。");
            } catch (DiscussionEngine.UserInterrupted ui) {
                orchestrator.wakeIfSettled(step.workspaceId(), step.stage(), step.iteration(),
                        "任务 #" + step.seq() + "「" + step.name() + "」已被用户终止。请评估后续。");
            } catch (Exception e) {
                orchestrator.wakeIfSettled(step.workspaceId(), step.stage(), step.iteration(),
                        "任务 #" + step.seq() + "「" + step.name() + "」重试失败（" + TaskRunner.rootMessage(e) + "）。请评估后调整。");
            }
        });
    }

    /**
     * 同步执行（主控 execute_step 链路）：阻塞到任务完成，产出直接返回主控。
     *
     * <p><b>无超时</b>（2026-10-17 定稿）：模型想多久想多久（thinking 保留），做完为止——
     * TimeoutException 失败形态废除。Future 仅为人为终止留 cancel 通道
     * （{@link StepInterruption#terminate}），终止后落 SKIPPED 并抛「用户已终止」，
     * 主控直接进入下一步。</p>
     *
     * @return 产出全文
     * @throws IllegalStateException 任务失败（message 为可读原因，主控当场决策重试/换路/跳过）
     * @throws DiscussionEngine.UserInterrupted 用户终止
     */
    public String runSync(StageStep step, TopicBrief brief) {
        stepRepo.updateStatus(step.withStatus(StepStatus.EXECUTING));
        log.info("任务执行开始: ws={} {}#{} seq={} agent={}",
                step.workspaceId(), step.stage(), step.iteration(), step.seq(), step.agent());
        // 执行过程收集（每步执行都可视）：agent 的思考/回复增量与工具调用进轨迹，
        // 节流落 output_full（轮询兜底）+ 广播 step.process（在线前端实时看）；
        // 完成时 output_full 被纯产出覆盖，轨迹另落 CHATLOG 文件留档（回放通道）
        StringBuilder trace = new StringBuilder();
        final long[] lastFlush = {System.currentTimeMillis()};
        final StringBuilder pending = new StringBuilder();
        java.util.function.Consumer<String> flush = (force) -> {
            String buf = pending.toString();
            if (buf.isEmpty()) {
                return;
            }
            if (!"now".equals(force) && System.currentTimeMillis() - lastFlush[0] < 2000) {
                return; // 节流：delta 每 token 一帧，2s 落一次库/推一次
            }
            pending.setLength(0);
            lastFlush[0] = System.currentTimeMillis();
            trace.append(buf);
            try {
                stepRepo.appendSpeech(step.id(), buf);
            } catch (Exception ignore) {
                // 落库失败不阻断执行（在线实时不受影响）
            }
            try {
                chatEvents.publish(StageChatEventBus.key(step.workspaceId(), step.stage(), step.iteration()),
                        "step.process", java.util.Map.of(
                                "seq", step.seq(), "stage", step.stage(),
                                "stepType", "TASK", "name", step.name(),
                                "agent", step.agent(), "delta", buf));
            } catch (Exception ignore) {
                // 广播失败不阻断执行
            }
        };
        java.util.function.Consumer<io.xuanji.core.runtime.AgentStreamEvent> onEvent = evt -> {
            String type = evt.type();
            if ("agent.delta".equals(type) && evt.delta() != null && !evt.delta().isEmpty()) {
                pending.append(evt.delta());
                flush.accept("throttle");
            } else if ("tool.call".equals(type)) {
                String tool = evt.meta() == null ? "" : String.valueOf(evt.meta().getOrDefault("tool", evt.meta().get("name")));
                if (!tool.isBlank()) {
                    pending.append("\n🔧 调用 ").append(tool).append(" …\n");
                    flush.accept("now");
                }
            } else if ("tool.result".equals(type)) {
                // 工具执行结果帧：轨迹里只有「调用 …」没有结果的话，回放时看不出工具成败
                String tool = evt.meta() == null ? "" : String.valueOf(evt.meta().getOrDefault("tool", ""));
                if (!tool.isBlank()) {
                    boolean ok = !"ERROR".equals(String.valueOf(evt.meta() == null ? "" : evt.meta().getOrDefault("state", "")));
                    pending.append(ok ? "✅ " : "❌ ").append(tool).append(ok ? " 完成\n" : " 失败\n");
                    flush.accept("now");
                }
            }
        };
        try {
            // 挂 ask_peer：执行中遇到需求/架构问题可 @对应方解答（三方结构，问答留档）——
            // 讨论式开发核心：执行 agent 不清楚就问，禁止靠猜
            // 四参重载传 workspaceId：启用内置文件工具（agent_config.builtin_tools_json 白名单，
            // developer=fs.read+fs.write）——任务执行是「写盘交付」主路径，不启用的话 agent 想写
            // 文件没工具，只能嘴上说「已创建」实际目录是空的（2026-10-19 用户反馈「AI 总是报没有工具」实锤）。
            // 白名单现无 shell（DB 实况 5 agent 均 fs.*），无 ASK 挂死风险；若未来配 shell 需先做无人值守批准通道
            AgentSpec base = agentNodes.buildSpecByAgentName(step.agent(), null, null, step.workspaceId());
            // 挂运维工具三件套（ops_remote_shell/db_query/db_dml）：地址与凭证不暴露给 AI，
            // 工具参数只收引用 ID（server-1/db-1），连接由 OpsConnectionManager 平台侧代理——
            // 用不用由 AI 按任务决定（部署/排障任务才会调）
            AgentSpec spec = withTools(base, java.util.stream.Stream.concat(
                    List.of(peerConsult.askPeerDef(step, brief)).stream(),
                    OpsTools.defsFor(step.workspaceId(), ops).stream()).toList());
            String prompt = brief.render(step.name(), step.instruction());
            Future<String> future = workPool.submit(() -> invoker.invoke(spec, prompt, onEvent));
            StepInterruption.register(step.id(), future);
            String output;
            try {
                output = future.get(); // 无超时：做完为止
            } catch (java.util.concurrent.CancellationException ce) {
                stepRepo.updateStatus(step.withStatus(StepStatus.SKIPPED).withSkip("用户终止"));
                projectEvents.publish(step.workspaceId(), "step.updated", Map.of(
                        "stage", step.stage(), "seq", step.seq(), "stepType", "TASK",
                        "status", "SKIPPED", "name", step.name()));
                throw new DiscussionEngine.UserInterrupted();
            } catch (java.util.concurrent.ExecutionException ee) {
                throw new IllegalStateException(TaskRunner.rootMessage(ee), ee);
            } finally {
                StepInterruption.unregister(step.id());
            }
            // nodeId 带阶段与迭代：跨阶段 seq 会重合（REQUIREMENT seq4 与 DEVELOP seq4），
            // 裸 "step-{seq}" 会让追溯关联与 file_path 回填串阶段（trace 端点按全键匹配）
            String stepNodeId = "step-" + step.stage() + "-" + step.iteration() + "-" + step.seq();
            // 11 参重载带 stage：交付物必须标阶段归属，否则生命周期「交付物 N 份」按 stage 统计时
            // 交付物全被漏掉、DOC 被误当交付物计数（测试003 实锤：交付物 tab 5 条 vs 生命周期报 24）
            projectRepo.insertArtifact(step.workspaceId(), stepNodeId,
                    "DELIVERABLE", step.name(), "TEXT", null, output, 1, "ACTIVE", step.stage(), step.agent());
            String summary = output.length() > 300 ? output.substring(0, 300) + "…" : output;
            stepRepo.updateStatus(step.withOutput(summary, output, null));
            // 执行轨迹留档（每步可视的回放通道）：output_full 已被纯产出覆盖，
            // 轨迹落 CHATLOG 文件——前端过程留档区可点击回看完整执行轨迹
            flush.accept("now");
            String trajectory = trace.toString();
            if (!trajectory.isBlank()) {
                docStore.writeFile(step.workspaceId(), step.stage(), step.iteration(), "CHATLOG",
                        step.seq(), step.name() + "-执行轨迹", trajectory);
            }
            // 文档双落：产出落项目目录（docs/{stage}/iter{n}/），相对路径回填 DELIVERABLE 索引
            // （不另插 DOC 索引——同一产出两条索引会让交付物 tab 重复）
            String docPath = docStore.writeFile(step.workspaceId(), step.stage(), step.iteration(),
                    "TASK", step.seq(), step.name(), output);
            if (docPath != null) {
                projectRepo.updateArtifactFilePath(step.workspaceId(), stepNodeId, docPath);
            }
            settleRequirements(step);
            log.info("任务执行完成: ws={} seq={} 产出 {} 字", step.workspaceId(), step.seq(), output.length());
            // 项目状态实时推送：步骤落终态，在线前端收到即刷新台账（离线靠拉全量兜底）
            projectEvents.publish(step.workspaceId(), "step.updated", Map.of(
                    "stage", step.stage(), "seq", step.seq(), "stepType", "TASK",
                    "status", "DONE", "name", step.name()));
            // 同步链路不唤醒：主控就阻塞在本方法调用上，产出经返回值直接回到它手里
            return output;
        } catch (DiscussionEngine.UserInterrupted ui) {
            // 已落 SKIPPED；终止前的轨迹同样落档（用户看到执行到哪一步被终止）
            flush.accept("now");
            if (!trace.isEmpty()) {
                docStore.writeFile(step.workspaceId(), step.stage(), step.iteration(), "CHATLOG",
                        step.seq(), step.name() + "-执行轨迹(终止)", trace + "\n\n⏹ 用户终止\n");
            }
            throw ui;
        } catch (Exception e) {
            String msg = rootMessage(e);
            stepRepo.updateStatus(step.withError(msg));
            // 失败也要把已产生的轨迹落档（排障需要）+ 广播终止
            flush.accept("now");
            String trajectory = trace.toString();
            if (!trajectory.isBlank()) {
                docStore.writeFile(step.workspaceId(), step.stage(), step.iteration(), "CHATLOG",
                        step.seq(), step.name() + "-执行轨迹(失败)", trajectory + "\n\n❌ 失败原因：" + msg + "\n");
            }
            log.warn("任务执行失败: ws={} seq={} -> {}", step.workspaceId(), step.seq(), msg);
            // 项目状态实时推送：失败也是终态
            projectEvents.publish(step.workspaceId(), "step.updated", Map.of(
                    "stage", step.stage(), "seq", step.seq(), "stepType", "TASK",
                    "status", "FAILED", "name", step.name()));
            // 失败原因上抛：主控当场拿到，立即决策（重试/换路/跳过），不停摆
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 需求状态回写：步骤完成时，检查其声明的需求项——若该需求全部关联步骤均已产出
     * （无 PLANNED/EXECUTING/WAITING），置 DONE；首个关联步骤开工时置 IN_PROGRESS。
     * 回写失败只记日志（需求状态是看板信息，不阻塞任务完成）。
     */
    private void settleRequirements(StageStep doneStep) {
        try {
            for (String code : doneStep.requirementCodes()) {
                requirementRepo.findByCode(doneStep.workspaceId(), code).ifPresent(r -> {
                    if ("PROPOSED".equals(r.status()) || "CONFIRMED".equals(r.status())) {
                        requirementRepo.updateStatus(doneStep.workspaceId(), code, "IN_PROGRESS");
                    }
                    List<StageStep> linked = stepRepo.findByStage(doneStep.workspaceId(),
                            doneStep.stage(), doneStep.iteration()).stream()
                            .filter(s -> s.requirementCodes().contains(code))
                            .toList();
                    boolean allSettled = linked.stream().allMatch(StageStep::produced);
                    if (allSettled && !linked.isEmpty()) {
                        requirementRepo.updateStatus(doneStep.workspaceId(), code, "DONE");
                        log.info("需求项完成回写: ws={} {} DONE（{} 个关联步骤全部产出）",
                                doneStep.workspaceId(), code, linked.size());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("需求状态回写失败（不阻塞任务）: ws={} seq={} -> {}",
                    doneStep.workspaceId(), doneStep.seq(), rootMessage(e));
        }
    }

    /** 重建 spec：把会话级工具（BEAN 形态 ToolDef，payload 携带 Tool 实例）追加进 resolvedTools。 */
    private static AgentSpec withTools(AgentSpec base, List<io.xuanji.core.runtime.ToolDef> tools) {
        List<io.xuanji.core.runtime.ToolDef> merged = new java.util.ArrayList<>(
                base.resolvedTools() == null ? List.of() : base.resolvedTools());
        merged.addAll(tools);
        // 全参构造：透传内置工具上下文（同 StageOrchestrator.withTools，旧 10 参会丢批六新字段）
        return new AgentSpec(base.agentConfigId(), base.name(), base.systemPrompt(), base.model(),
                base.tools(), base.permission(), base.runtime(), base.sessionId(),
                merged, base.skills(), base.builtinTools(), base.workspaceRoot(), base.workspaceId());
    }

    static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        if (cur.getMessage() != null) {
            return cur.getMessage();
        }
        // 裸类名用户看不懂（2026-10-19 反馈）：常见无 message 异常给可读文案
        if (cur instanceof InterruptedException) {
            return "执行被中断（服务重启或人为终止）——可重试";
        }
        if (cur instanceof java.util.concurrent.TimeoutException) {
            return "执行超时";
        }
        if (cur instanceof java.net.ConnectException) {
            return "连接不上上游服务（模型网关/网络）";
        }
        return cur.toString();
    }
}
