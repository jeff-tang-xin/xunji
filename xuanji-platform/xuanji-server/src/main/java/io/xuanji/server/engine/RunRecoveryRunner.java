package io.xuanji.server.engine;

import io.xuanji.server.project.orchestrator.StageStepRepository;
import io.xuanji.server.repository.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自愈：把上次进程中断遗留的 RUNNING run/节点、EXECUTING 小流程标记为 FAILED。
 *
 * <p>为什么必须做：阶段执行是进程内异步线程（LiteFlow / 编排线程池），kill 进程
 * （部署重启/崩溃）时正在跑的 agent 被直接杀死，但状态永远停在 RUNNING/EXECUTING——
 * 界面显示「执行中」却不会再推进（僵尸状态）。启动时统一标 FAILED
 * （error 注明「服务重启中断」）：旧内核可在阶段执行面板重跑恢复；
 * 新内核（编排）的主控在下轮对话看到 FAILED 后自主决定重试或换路（AI 自愈）。</p>
 *
 * <p>为什么全标 FAILED 是对的：进程都没了，线程必然死了，不存在「还在执行」的
 * 可能；真在执行的只存在于活进程里，而本方法只在启动瞬间跑一次。</p>
 */
@Component
public class RunRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunRecoveryRunner.class);

    private final RunRepository runRepo;
    private final StageStepRepository stepRepo;

    public RunRecoveryRunner(RunRepository runRepo, StageStepRepository stepRepo) {
        this.runRepo = runRepo;
        this.stepRepo = stepRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        int[] n = runRepo.failOrphanRuns();
        if (n[0] > 0 || n[1] > 0) {
            log.warn("启动自愈: {} 条 RUNNING run / {} 条 RUNNING 节点已标记为 FAILED（服务重启中断），可在阶段执行面板重跑恢复", n[0], n[1]);
        }
        int steps = stepRepo.failOrphanExecuting("服务重启中断");
        if (steps > 0) {
            log.warn("启动自愈: {} 条 EXECUTING 小流程已标记为 FAILED（服务重启中断），主控将在对话中自主处理", steps);
        }
    }
}

