package io.xuanji.core.api;

/**
 * 节点执行错误契约（docs/detail/02-spi-and-registry.md §3）。
 *
 * <ul>
 *   <li>{@code retryable=true} —— 引擎按节点 retry 配置重试（默认 0 次），耗尽后 run FAILED</li>
 *   <li>{@code retryable=false} —— 直接失败，写 wf_node_run.error</li>
 *   <li>其他 RuntimeException —— 视为不可重试失败（插件不应让受检异常逃逸）</li>
 * </ul>
 */
public class NodeExecutionException extends RuntimeException {

    private final boolean retryable;
    private final String errorCode;

    public NodeExecutionException(String errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode == null ? ErrorCode.INTERNAL : errorCode;
        this.retryable = retryable;
    }

    public NodeExecutionException(String errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? ErrorCode.INTERNAL : errorCode;
        this.retryable = retryable;
    }

    /** true=引擎按 retry 策略重试；false=直接失败 */
    public boolean isRetryable() {
        return retryable;
    }

    /** 分类错误码（见 {@link ErrorCode}），运行监控与告警按此聚合 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 可重试失败的便捷工厂 */
    public static NodeExecutionException retryable(String errorCode, String message) {
        return new NodeExecutionException(errorCode, true, message);
    }

    /** 不可重试失败的便捷工厂 */
    public static NodeExecutionException fatal(String errorCode, String message) {
        return new NodeExecutionException(errorCode, false, message);
    }
}
