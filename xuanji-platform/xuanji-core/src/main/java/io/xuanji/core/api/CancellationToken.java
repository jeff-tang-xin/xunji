package io.xuanji.core.api;

/**
 * 取消令牌——run 取消/超时的协作式取消信号。
 *
 * <p>引擎在 run 被取消或节点超时置位；长任务节点必须周期性轮询
 * {@link #isCancelled()}，收到信号后尽快回滚副作用并抛
 * {@code NodeExecutionException(errorCode=CANCELLED)}。</p>
 */
public interface CancellationToken {

    /** 取消/超时是否已置位 */
    boolean isCancelled();

    /**
     * 便捷检查：已取消则直接抛 CANCELLED 异常（长任务在循环中调用）。
     *
     * @throws NodeExecutionException errorCode=CANCELLED
     */
    default void throwIfCancelled() throws NodeExecutionException {
        if (isCancelled()) {
            throw new NodeExecutionException(ErrorCode.CANCELLED, false, "run cancelled or timed out");
        }
    }
}
