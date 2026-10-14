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
import java.util.concurrent.TimeUnit;

/**
 * 任务型小流程执行器：单 agent 后台执行，产出登记为交付物。
 *
 * <p>执行模型：主控在对话中调 execute_step → 本类立即把步骤标 EXECUTING 并提交
 * 后台线程（不阻塞主控的 ReAct 循环与 SSE 会话），完成后落黑板（DONE + 产出 +
 * 产物登记）；失败落 FAILED（主控下轮对话可见，自主决定重试或换路）。</p>
 *
 * <p>超时：单任务 8 分钟硬上限（Future.get）——实测 qwen 单次生成可达 2-3 分钟，
 * 3 分钟会把正常任务掐死；任务仍应设计得小，超时即任务过大或模型异常（主控下轮可见，自主重试/换路）。
 * 无状态：线程池外所有状态在黑板，多项目并发安全。</p>
 */
@Component
public class TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    /** 单任务硬超时（分钟）。 */
    static final int TIMEOUT_MINUTES = 8;

    private final AgentInvoker invoker;
    private final AgentNodeExecutor agentNodes;
    private final StageStepRepository stepRepo;
    private final ProjectRepository projectRepo;
    private final PeerConsultService peerConsult;
    private final RequirementRepository requirementRepo;

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
                      PeerConsultService peerConsult, RequirementRepository requirementRepo) {
        this.invoker = invoker;
        this.agentNodes = agentNodes;
        this.stepRepo = stepRepo;
        this.projectRepo = projectRepo;
        this.peerConsult = peerConsult;
        this.requirementRepo = requirementRepo;
    }

    /**
     * 提交后台执行：立即返回（主控继续对话），结果落黑板。
     * 调用方保证 step 处于 PLANNED/WAITING 态。
     */
    public void submit(StageStep step, TopicBrief brief) {
        stepRepo.updateStatus(step.withStatus(StepStatus.EXECUTING));
        dispatchPool.submit(() -> run(step, brief));
    }

    private void run(StageStep step, TopicBrief brief) {
        log.info("任务执行开始: ws={} {}#{} seq={} agent={}",
                step.workspaceId(), step.stage(), step.iteration(), step.seq(), step.agent());
        try {
            // 挂 ask_peer：执行中遇到需求/架构问题可 @对应方解答（三方结构，问答留档）——
            // 讨论式开发核心：执行 agent 不清楚就问，禁止靠猜
            AgentSpec base = agentNodes.buildSpecByAgentName(step.agent(), null);
            AgentSpec spec = withTools(base, List.of(peerConsult.askPeerDef(step, brief)));
            String prompt = brief.render(step.name(), step.instruction());
            Future<String> future = workPool.submit(() -> invoker.invoke(spec, prompt));
            String output;
            try {
                output = future.get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException te) {
                // 超时必须 cancel：invoke 是阻塞的 LLM 调用，不取消则僵尸任务永久占住工作线程，
                // 后续所有项目的任务型步骤全部排队饿死
                future.cancel(true);
                throw te;
            }
            projectRepo.insertArtifact(step.workspaceId(), "step-" + step.seq(),
                    "DELIVERABLE", step.name(), "TEXT", null, output, 1, "ACTIVE", step.agent());
            String summary = output.length() > 300 ? output.substring(0, 300) + "…" : output;
            stepRepo.updateStatus(step.withOutput(summary, output, null));
            settleRequirements(step);
            log.info("任务执行完成: ws={} seq={} 产出 {} 字", step.workspaceId(), step.seq(), output.length());
        } catch (Exception e) {
            String msg = rootMessage(e);
            stepRepo.updateStatus(step.withError(msg));
            log.warn("任务执行失败: ws={} seq={} -> {}", step.workspaceId(), step.seq(), msg);
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
        return new AgentSpec(base.agentConfigId(), base.name(), base.systemPrompt(), base.model(),
                base.tools(), base.permission(), base.runtime(), base.sessionId(),
                merged, base.skills());
    }

    static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.toString() : cur.getMessage();
    }
}
