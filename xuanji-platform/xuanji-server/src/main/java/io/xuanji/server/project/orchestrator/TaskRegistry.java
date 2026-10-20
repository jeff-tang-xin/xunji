package io.xuanji.server.project.orchestrator;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 项目级在跑任务注册表：删除项目时一键终止该项目所有在跑任务。
 *
 * <p>背景（2026-10-20 实锤）：删除项目只删了 DB 行与目录，在跑的异步任务（主控轮、
 * 人工重试后台任务）不知情继续跑——LLM 照烧、落库复活脏数据（CONSULT 步 seq 从 1
 * 重新编号复活 7 行）。根因是删除没有主动取消任务，本注册表补上这一环。</p>
 *
 * <p>同步化改造后执行任务/讨论/求助都在主控轮线程内同步跑，cancel 主控轮 Future
 * （interrupt）即中断整棵调用树；TaskRunner.submit（人工重试）是独立后台线程，单独注册。
 * interrupt 对 AgentScope LLM 调用的中断有效性已由 StepInterruption（用户终止按钮）实测。</p>
 */
public final class TaskRegistry {

    private static final Map<String, Set<Future<?>>> BY_WS = new ConcurrentHashMap<>();

    private TaskRegistry() {
    }

    /** 任务提交后注册；执行结束（无论成败）必须 unregister，防泄漏与误杀后续任务。 */
    public static void register(String wsId, Future<?> future) {
        if (wsId == null || future == null) {
            return;
        }
        BY_WS.computeIfAbsent(wsId, k -> ConcurrentHashMap.newKeySet()).add(future);
    }

    public static void unregister(String wsId, Future<?> future) {
        if (wsId == null || future == null) {
            return;
        }
        Set<Future<?>> set = BY_WS.get(wsId);
        if (set != null) {
            set.remove(future);
            if (set.isEmpty()) {
                BY_WS.remove(wsId, set);
            }
        }
    }

    /**
     * 终止该项目所有在跑任务（删除项目时调用）。
     *
     * @return 终止的任务数（0=没有在跑任务）
     */
    public static int terminateAll(String wsId) {
        Set<Future<?>> set = BY_WS.remove(wsId);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Future<?> f : set) {
            f.cancel(true);
            n++;
        }
        return n;
    }
}
