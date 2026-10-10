package io.xuanji.server.engine;

import io.xuanji.core.api.WorkflowVars;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * run 变量区的内存实现（P0）。线程安全（WHEN 并行分支预留）；
 * SUSPENDED 快照持久化时随 run 上下文整体序列化。
 */
public class MapWorkflowVars implements WorkflowVars {

    private final Map<String, Object> values = new ConcurrentHashMap<>();

    @Override
    public Object get(String key) {
        return values.get(key);
    }

    @Override
    public void put(String key, Object value) {
        values.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type, T defaultValue) {
        Object v = values.get(key);
        return type.isInstance(v) ? (T) v : defaultValue;
    }

    @Override
    public Map<String, Object> snapshot() {
        return new java.util.HashMap<>(values);
    }
}
