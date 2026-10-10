package io.xuanji.core.definition;

import java.util.List;

/**
 * 工作流定义（画布 JSON definition format v1 的 Java 视图，docs/detail/03 §1）。
 *
 * <p>定义即数据：可视化编辑器产出本结构，发布时冻结为不可变版本（版本钉住），
 * 引擎按绑定版本执行。本 record 是定义的内存形态；序列化形态 = 画布 JSON
 * （camelCase），持久化形态 = wf_definition_version.canvas_json。</p>
 *
 * <p>图结构约束（发布校验执行，本类型只承载数据 + 便捷派生）：
 * 有向无环、恰好一个 START、至少一个 END、单入度（COND 节点可多出边）。</p>
 *
 * @param version     定义版本号（草稿期 0；发布后递增）
 * @param name        流程名
 * @param description 说明
 * @param triggers    触发绑定（CHAT_INPUT/MANUAL/API/SCHEDULE/WEBHOOK）
 * @param nodes       节点定义（含平台内置 START/END）
 * @param edges       边定义（含 mapping 数据流转）
 */
public record WorkflowDefinition(
        int version,
        String name,
        String description,
        List<TriggerDef> triggers,
        List<NodeDef> nodes,
        List<EdgeDef> edges
) {

    public WorkflowDefinition {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** 开始节点（决议 Q10：恰好一个；发布校验强制） */
    public NodeDef startNode() {
        return nodes.stream().filter(NodeDef::isStart).findFirst()
                .orElseThrow(() -> new IllegalStateException("definition has no START node: " + name));
    }

    /** 结束节点集合（至少一个；发布校验强制） */
    public List<NodeDef> endNodes() {
        return nodes.stream().filter(NodeDef::isEnd).toList();
    }

    /** 流程级输入 schema（开始节点声明；工作区场景 = 用户输入内容契约） */
    public io.xuanji.core.api.JsonSchema inputSchema() {
        return startNode().inputSchema();
    }

    /** 按 instanceId 定位节点（引擎执行期路由用） */
    public NodeDef node(String instanceId) {
        return nodes.stream().filter(n -> n.instanceId().equals(instanceId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("node not in definition: " + instanceId));
    }

    /** 某节点的全部出边（mapping 数据流转的查找入口） */
    public List<EdgeDef> outgoingEdges(String instanceId) {
        return edges.stream().filter(e -> e.from().equals(instanceId)).toList();
    }

    /** 某节点的全部入边（输入装配时按此回溯上游输出） */
    public List<EdgeDef> incomingEdges(String instanceId) {
        return edges.stream().filter(e -> e.to().equals(instanceId)).toList();
    }
}
