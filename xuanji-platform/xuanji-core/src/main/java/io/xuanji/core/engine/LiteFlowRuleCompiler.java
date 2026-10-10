package io.xuanji.core.engine;

import io.xuanji.core.api.NodeKind;
import io.xuanji.core.definition.EdgeDef;
import io.xuanji.core.definition.NodeDef;
import io.xuanji.core.definition.WorkflowDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LiteFlow 规则编译器——{@link WorkflowDefinition} → LiteFlow EL 字符串。
 *
 * <p>产物是纯文本 EL（core 不依赖 LiteFlow 类），由 server 侧以
 * {@code execute2RespWithEL} 动态执行。每个画布节点编译为分发组件的
 * 一个 tag 实例：{@code {componentName}.tag("{instanceId}")}——组件单例、
 * tag 区分节点，避免按定义动态注册 bean。</p>
 *
 * <p>P0 支持 SEQ 线性链（START → … → END，每节点至多一条 SEQ 出边）；
 * COND（SWITCH）/ PAR（WHEN）为 P1，出现即编译失败并明确提示。</p>
 */
public final class LiteFlowRuleCompiler {

    private final String componentName;

    public LiteFlowRuleCompiler(String componentName) {
        this.componentName = componentName;
    }

    /**
     * 编译为 THEN 链 EL。
     *
     * @throws IllegalArgumentException 图结构不满足 P0 线性链约束
     */
    public String compile(WorkflowDefinition definition) {
        return compileFrom(definition, definition.startNode().instanceId());
    }

    /**
     * 从指定节点起编译剩余段 THEN 链（决策门恢复执行，docs/detail/03 §5：
     * 「以当前节点为起点启动下一段 LiteFlow 执行」）。
     *
     * @throws IllegalArgumentException fromInstanceId 不在线性链上
     */
    public String compileFrom(WorkflowDefinition definition, String fromInstanceId) {
        List<NodeDef> chain = topologicalChain(definition);
        int idx = -1;
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).instanceId().equals(fromInstanceId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalArgumentException("node not in linear chain: " + fromInstanceId);
        }
        List<NodeDef> sub = chain.subList(idx, chain.size());
        StringBuilder el = new StringBuilder("THEN(");
        for (int i = 0; i < sub.size(); i++) {
            if (i > 0) {
                el.append(", ");
            }
            el.append(componentName).append(".tag(\"").append(sub.get(i).instanceId()).append("\")");
        }
        return el.append(")").toString();
    }

    /**
     * 从指定节点的【后继】起编译剩余段（评审门 APPROVE 恢复：gate 产物已通过，
     * 不重跑 gate 本身，直接复用其已通过的输出，只执行下游）。
     *
     * @throws IllegalArgumentException nodeInstanceId 不在线性链上
     */
    public String compileAfter(WorkflowDefinition definition, String nodeInstanceId) {
        List<NodeDef> chain = topologicalChain(definition);
        int idx = -1;
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).instanceId().equals(nodeInstanceId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalArgumentException("node not in linear chain: " + nodeInstanceId);
        }
        List<NodeDef> sub = chain.subList(Math.min(idx + 1, chain.size()), chain.size());
        StringBuilder el = new StringBuilder("THEN(");
        for (int i = 0; i < sub.size(); i++) {
            if (i > 0) {
                el.append(", ");
            }
            el.append(componentName).append(".tag(\"").append(sub.get(i).instanceId()).append("\")");
        }
        return el.append(")").toString();
    }

    /**
     * 校验并抽取执行链：全图拓扑排序线性化（P0 串行执行语义）。
     * 允许多上游汇聚/多下游分支（DAG）——拓扑序保证每个节点执行时其所有上游已完成，
     * 输入装配按入边回溯（WorkflowDefinition.incomingEdges）即可正确取数。
     * 线性链是 DAG 特例（拓扑序唯一），对既有线性流程完全兼容。
     */
    private List<NodeDef> topologicalChain(WorkflowDefinition definition) {
        List<NodeDef> nodes = definition.nodes();
        if (nodes.stream().filter(NodeDef::isStart).count() != 1) {
            throw new IllegalArgumentException("definition must have exactly one START node: " + definition.name());
        }
        if (definition.endNodes().isEmpty()) {
            throw new IllegalArgumentException("definition must have at least one END node: " + definition.name());
        }

        // P1 结构提前拒绝：COND/PAR 边
        for (EdgeDef e : definition.edges()) {
            if (e.type() != EdgeDef.EdgeType.SEQ) {
                throw new IllegalArgumentException("only SEQ edges supported in P0 (found " + e.type()
                        + ": " + e.from() + "->" + e.to() + "); COND/PAR is P1");
            }
        }

        Map<String, NodeDef> byId = new java.util.LinkedHashMap<>();
        nodes.forEach(n -> byId.put(n.instanceId(), n));

        for (EdgeDef e : definition.edges()) {
            if (!byId.containsKey(e.from()) || !byId.containsKey(e.to())) {
                throw new IllegalArgumentException("edge references unknown node: " + e.from() + "->" + e.to());
            }
        }

        // Kahn 拓扑排序：入度归零即就绪；结束时仍有节点未入链 = 存在环或 START 不可达节点
        Map<String, Integer> indegree = new java.util.HashMap<>();
        for (EdgeDef e : definition.edges()) {
            indegree.merge(e.to(), 1, Integer::sum);
        }
        List<NodeDef> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        java.util.Deque<String> ready = new java.util.ArrayDeque<>();
        ready.add(definition.startNode().instanceId());
        while (!ready.isEmpty()) {
            String id = ready.poll();
            if (!seen.add(id)) {
                continue;
            }
            chain.add(byId.get(id));
            for (EdgeDef e : definition.outgoingEdges(id)) {
                int left = indegree.merge(e.to(), -1, Integer::sum);
                if (left == 0) {
                    ready.add(e.to());
                }
            }
        }
        if (chain.size() != nodes.size()) {
            throw new IllegalArgumentException(
                    "definition graph has a cycle or nodes unreachable from START: " + definition.name()
                            + " (compiled " + chain.size() + "/" + nodes.size() + ")");
        }
        return chain;
    }
}
