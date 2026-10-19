package io.xuanji.server.project.orchestrator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 步骤终止注册表：同步执行时代的人为终止通道。
 *
 * <p>同步化后主控线程阻塞在工具调用里等步骤完成（无超时），用户随时可能要求
 * 终止——执行体（TaskRunner/DiscussionEngine）把在跑的 Future 注册到这里，
 * 终止端点调 {@link #terminate} cancel 它：阻塞中的 get() 立即抛 CancellationException，
 * 执行体落 SKIPPED（留痕「用户终止」）并把终止信号返回主控，主控直接进入下一步。</p>
 *
 * <p>终止是立即中断（用户不想等了）：当前 LLM 调用被 cancel，半截发言不落库
 * （appendSpeech 在 invoke 返回后才调，中断时不会写）。</p>
 *
 * <p>纯内存：重启后 EXECUTING 步由 RunRecoveryRunner 标 FAILED，注册表自然为空。</p>
 */
public final class StepInterruption {

    private static final Map<Long, Future<?>> ACTIVE = new ConcurrentHashMap<>();

    private StepInterruption() {
    }

    /** 执行体开始等待前注册；stepId 唯一（同一步不会并发跑两份）。 */
    public static void register(long stepId, Future<?> future) {
        ACTIVE.put(stepId, future);
    }

    /** 执行结束（无论成败）必须注销，防泄漏与误杀后续重试。 */
    public static void unregister(long stepId) {
        ACTIVE.remove(stepId);
    }

    /**
     * 请求终止：cancel 在跑的执行。
     *
     * @return true=找到了在跑的执行并已 cancel；false=该步骤当前没有在跑的执行
     *        （可能已自然完成——终止请求晚到，调用方按幂等处理）
     */
    public static boolean terminate(long stepId) {
        Future<?> f = ACTIVE.get(stepId);
        if (f == null) {
            return false;
        }
        f.cancel(true);
        return true;
    }
}
