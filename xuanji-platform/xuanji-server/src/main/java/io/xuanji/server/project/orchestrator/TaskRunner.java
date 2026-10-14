package io.xuanji.server.project.orchestrator;

import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.server.engine.AgentNodeExecutor;
import io.xuanji.server.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
                      StageStepRepository stepRepo, ProjectRepository projectRepo) {
        this.invoker = invoker;
        this.agentNodes = agentNodes;
        this.stepRepo = stepRepo;
        this.projectRepo = projectRepo;
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
            AgentSpec spec = agentNodes.buildSpecByAgentName(step.agent(), null);
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
            log.info("任务执行完成: ws={} seq={} 产出 {} 字", step.workspaceId(), step.seq(), output.length());
        } catch (Exception e) {
            String msg = rootMessage(e);
            stepRepo.updateStatus(step.withError(msg));
            log.warn("任务执行失败: ws={} seq={} -> {}", step.workspaceId(), step.seq(), msg);
        }
    }

    static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.toString() : cur.getMessage();
    }
}
