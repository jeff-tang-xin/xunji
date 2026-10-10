package io.xuanji.core.api;

/**
 * run 级变量区——跨节点共享的可读写状态。
 *
 * <p>引擎负责其持久化：SUSPENDED（决策门挂起）时随上下文快照落库，
 * 重启后恢复，保证「检查点分段执行」语义成立。实现方必须线程安全
 * （WHEN 并行分支可能并发读写）。</p>
 */
public interface WorkflowVars {

    Object get(String key);

    void put(String key, Object value);

    /** 读取带默认值（值不存在或类型不符时返回默认） */
    <T> T get(String key, Class<T> type, T defaultValue);

    /**
     * 导出全部变量（决策门挂起快照用，docs/detail/03 §5）。
     * 默认空实现兼容未支持快照的实现；可持久化实现应返回可序列化副本。
     */
    default java.util.Map<String, Object> snapshot() {
        return java.util.Map.of();
    }
}
