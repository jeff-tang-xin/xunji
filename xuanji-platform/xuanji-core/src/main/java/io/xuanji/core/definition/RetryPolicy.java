package io.xuanji.core.definition;

/**
 * 重试策略（画布 JSON nodes[].retry）。
 *
 * @param maxAttempts 最大尝试次数（0 = 不重试；仅当节点 capabilities 含 SUPPORTS_RETRY 生效）
 * @param intervalMs  重试间隔毫秒
 */
public record RetryPolicy(int maxAttempts, long intervalMs) {

    public RetryPolicy {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0: " + maxAttempts);
        }
        if (intervalMs < 0) {
            throw new IllegalArgumentException("intervalMs must be >= 0: " + intervalMs);
        }
    }

    /** 默认策略：不重试 */
    public static RetryPolicy none() {
        return new RetryPolicy(0, 0);
    }
}
