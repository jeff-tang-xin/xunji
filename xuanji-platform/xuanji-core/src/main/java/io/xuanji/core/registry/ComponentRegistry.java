package io.xuanji.core.registry;

import io.xuanji.core.api.NodeCapability;
import io.xuanji.core.api.NodeMetadata;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 组件注册表——「一切可插拔」的运行时枢纽（行为规格见 docs/detail/02 §6）。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>登记：启动时由装配方（server 的 Spring 扫描）把容器中全部
 *       WorkflowNode / WebhookParser / Connector 实现登记进来（upsert）</li>
 *   <li>查询：面板按 category 分组；执行期按 nodeId O(1) 定位</li>
 *   <li>校验：定义发布时校验 nodeId 存在且 enabled、capabilities 覆盖定义配置——
 *       让「引用不存在的节点」在发布期失败，运行期永不发生（前置校验原则）</li>
 *   <li>热插拔：register/unregister 运行时接口本期不开放，接口先留</li>
 * </ul>
 *
 * <p>实现必须线程安全（启动登记 + 运行期并发查询）。</p>
 */
public interface ComponentRegistry {

    /** 登记/更新一个组件（upsert，按 nodeId 覆盖） */
    void register(RegisteredComponent component);

    /** 注销（热插拔预留，本期不开放调用） */
    void unregister(String nodeId);

    /** 按 nodeId 定位（执行期 O(1) 语义） */
    Optional<RegisteredComponent> findByNode(String nodeId);

    /**
     * 执行期强制定位：未命中抛 IllegalStateException。
     * 运行期不发生（发布期已前置校验），发生即平台 bug，快速失败。
     */
    default RegisteredComponent requireByNode(String nodeId) {
        return findByNode(nodeId).orElseThrow(() ->
                new IllegalStateException("component not registered: " + nodeId
                        + "（发布期校验应已拦截，出现此错误属平台缺陷）"));
    }

    /** 面板查询：全量 enabled 组件（调用方按 category 分组） */
    List<RegisteredComponent> listEnabled();

    /**
     * 发布校验：节点存在且 enabled，且其 capabilities 覆盖定义所需能力。
     *
     * @param nodeId            定义中引用的节点 id
     * @param requiredCapabilities 该节点定义配置所需的能力（如配了决策门 → SUPPORTS_DECISION_GATE）
     * @return 校验问题清单；空 = 通过
     */
    List<String> validateNodeUsage(String nodeId, Set<NodeCapability> requiredCapabilities);
}
