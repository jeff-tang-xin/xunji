package io.xuanji.server.engine;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 决策门超时调度器：周期扫描挂起且已过 {@code wf_run.gate_deadline} 的 run，
 * 委托 {@link RunService#handleGateTimeouts()} 按各 gate 的 onTimeout 策略自动处置
 * （APPROVE/REJECT 复用 resume，ESCALATE 告警保持挂起）。
 *
 * <p>与报表调度（cron 数据驱动、每报表一任务）不同，本器是<b>单一固定周期轮询</b>——
 * 超时是「到点被动检查」，无需为每个 run 注册任务。周期可经系统属性
 * {@code -Dxuanji.gate.timeoutScanSeconds=15} 调整（默认 15s）。</p>
 */
@Component
public class GateTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(GateTimeoutScheduler.class);

    private final RunService runService;
    private ScheduledExecutorService executor;

    public GateTimeoutScheduler(RunService runService) {
        this.runService = runService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        long periodSec = Long.getLong("xuanji.gate.timeoutScanSeconds", 15L);
        if (periodSec <= 0) {
            log.info("gate timeout scheduler disabled (timeoutScanSeconds<=0)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "xuanji-gate-timeout");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                runService.handleGateTimeouts();
            } catch (Exception e) {
                log.warn("gate timeout scan error: {}", e.toString());
            }
        }, periodSec, periodSec, TimeUnit.SECONDS);
        log.info("gate timeout scheduler started: scan every {}s", periodSec);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}