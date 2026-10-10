package io.xuanji.server.report;

import io.xuanji.server.engine.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;

/**
 * 报表调度执行器（主文档 1.5：SCHEDULE 触发器）。
 *
 * <p>每张启用报表注册一个 CronTrigger（cron_expr 为 Spring 6 段 cron）；
 * 触发时按钉住版本发起 run（triggerType=SCHEDULE，审计可区分来源）。
 * run 在虚拟线程执行——调度线程只负责触发，长流程不阻塞其他报表调度。</p>
 *
 * <p>注册时机：启动时加载全部启用报表（ApplicationReadyEvent，Flyway 之后）；
 * 创建/删除报表即时注册/注销（ReportService 回调）。调度器重启后按当前
 * report 表重建注册表，无持久化调度状态。</p>
 */
@Component
public class ReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);

    private final TaskScheduler scheduler;
    private final ExecutorService runExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RunService runService;

    public ReportScheduler() {
        ThreadPoolTaskScheduler ts = new ThreadPoolTaskScheduler();
        ts.setPoolSize(1);
        ts.setThreadNamePrefix("report-sched-");
        ts.initialize();
        this.scheduler = ts;
    }

    /** 启动注册：加载全部启用报表（Flyway 迁移完成后执行） */
    @EventListener(ApplicationReadyEvent.class)
    public void registerAll() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, definition_id, pinned_version, cron_expr FROM report WHERE enabled = TRUE");
        rows.forEach(this::register);
        log.info("report scheduler started: {} report(s) registered", rows.size());
    }

    /** 注册单张报表（创建/启用时回调；同 id 重复注册先注销旧任务） */
    public synchronized void register(Map<String, Object> report) {
        String id = (String) report.get("id");
        cancel(id);
        String cron = (String) report.get("cron_expr");
        long definitionId = ((Number) report.get("definition_id")).longValue();
        int version = ((Number) report.get("pinned_version")).intValue();
        try {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> fire(id, definitionId, version), new CronTrigger(cron));
            tasks.put(id, future);
            log.info("report scheduled: {} definition={} v{} cron=[{}]", id, definitionId, version, cron);
        } catch (IllegalArgumentException e) {
            log.error("report cron invalid, skip: {} cron=[{}] -> {}", id, cron, e.toString());
        }
    }

    /** 注销（删除/停用时回调；未注册时静默） */
    public synchronized void cancel(String reportId) {
        ScheduledFuture<?> future = tasks.remove(reportId);
        if (future != null) {
            future.cancel(false);
            log.info("report unscheduled: {}", reportId);
        }
    }

    /** 立即触发一次执行（不等 cron）：复用 fire 的异步 run + 快照逻辑，供「立即执行」端点调用 */
    public void runNow(String reportId, long definitionId, int version) {
        fire(reportId, definitionId, version);
    }

    /** 触发一次执行：虚拟线程内同步跑 run（失败只记日志，不影响后续调度）；SUCCESS 后固化看板快照 */
    private void fire(String reportId, long definitionId, int version) {
        runExecutor.submit(() -> {
            try {
                Map<String, Object> result = runService.createRun(definitionId, version, Map.of(), "SCHEDULE");
                String runId = String.valueOf(result.get("runId"));
                log.info("report fired: {} -> run {} {}", reportId, runId, result.get("status"));
                if ("SUCCESS".equals(result.get("status"))) {
                    snapshot(reportId, runId);
                }
            } catch (Exception e) {
                log.error("report fire failed: {} definition={} v{} -> {}", reportId, definitionId, version, e.toString());
            }
        });
    }

    /**
     * 固化看板快照：run 输出含 board 配置（basic.board_render 节点产出）时
     * 落 report_snapshot——报表详情默认展示最新快照，历史快照可回看审计。
     */
    private void snapshot(String reportId, String runId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT output_json->'board' AS board FROM wf_run WHERE id = ? "
                            + "AND output_json->'board' IS NOT NULL", runId);
            if (rows.isEmpty()) {
                return; // workflow 无看板渲染节点输出，不落快照
            }
            String board = String.valueOf(rows.get(0).get("board"));
            String id = "rs_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            jdbc.update("INSERT INTO report_snapshot (id, report_id, run_id, snapshot_json) "
                    + "VALUES (?, ?, ?, ?::jsonb)", id, reportId, runId, board);
            log.info("report snapshot saved: {} run={}", reportId, runId);
        } catch (Exception e) {
            log.error("report snapshot failed: {} run={} -> {}", reportId, runId, e.toString());
        }
    }

    @PreDestroy
    public void shutdown() {
        tasks.values().forEach(f -> f.cancel(false));
        tasks.clear();
        runExecutor.shutdown();
    }
}
