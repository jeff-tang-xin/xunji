package io.xuanji.core.registry;

import io.xuanji.core.api.NodeCapability;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 注册表的纯 Java 内存实现（无 Spring 依赖，可独立单测）。
 *
 * <p>并发策略：ConcurrentHashMap 承载，登记用 compute 原子 upsert；
 * 查询无锁。P0 单机形态直接可用；多副本形态下各副本启动时独立扫描登记
 * （来源是代码而非数据，天然一致），无需共享存储。</p>
 */
public class SimpleComponentRegistry implements ComponentRegistry {

    private final ConcurrentMap<String, RegisteredComponent> components = new ConcurrentHashMap<>();

    @Override
    public void register(RegisteredComponent component) {
        components.compute(component.nodeId(), (id, existing) -> {
            if (existing != null && !existing.metadata().equals(component.metadata())) {
                // 同 nodeId 不同元数据 = 插件冲突（如两个插件撞名），启动期快速失败
                throw new IllegalStateException("duplicate nodeId with different metadata: " + id
                        + " existing=" + existing.pluginModule() + " incoming=" + component.pluginModule());
            }
            return component;
        });
    }

    @Override
    public void unregister(String nodeId) {
        components.remove(nodeId);
    }

    @Override
    public Optional<RegisteredComponent> findByNode(String nodeId) {
        return Optional.ofNullable(components.get(nodeId));
    }

    @Override
    public List<RegisteredComponent> listEnabled() {
        return components.values().stream()
                .filter(RegisteredComponent::enabled)
                .sorted(Comparator.comparing(c -> c.metadata().id()))
                .toList();
    }

    @Override
    public List<String> validateNodeUsage(String nodeId, Set<NodeCapability> requiredCapabilities) {
        Optional<RegisteredComponent> found = findByNode(nodeId);
        if (found.isEmpty()) {
            return List.of("节点未注册: " + nodeId);
        }
        RegisteredComponent c = found.get();
        if (!c.enabled()) {
            return List.of("节点已停用: " + nodeId);
        }
        Set<NodeCapability> missing = (requiredCapabilities == null ? Set.<NodeCapability>of() : requiredCapabilities);
        var problems = new java.util.ArrayList<String>();
        for (NodeCapability need : missing) {
            if (!c.capabilities().contains(need)) {
                problems.add("节点 " + nodeId + " 不支持所需能力: " + need);
            }
        }
        return List.copyOf(problems);
    }

    /** 当前登记总数（启动摘要日志用） */
    public int size() {
        return components.size();
    }

    /** 按 category 分组（面板查询） */
    public Map<String, List<RegisteredComponent>> groupByCategory() {
        return listEnabled().stream()
                .collect(java.util.stream.Collectors.groupingBy(c -> c.metadata().category()));
    }
}
