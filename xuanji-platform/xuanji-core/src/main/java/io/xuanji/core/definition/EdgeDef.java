package io.xuanji.core.definition;

import java.util.List;

/**
 * 画布边定义（画布 JSON edges[] 项）。
 *
 * @param from    源节点实例 id
 * @param to      目标节点实例 id
 * @param type    边类型：SEQ（顺序）/ COND（条件，P1）/ PAR（并行分支标记，P1）
 * @param mapping 数据流转规则（源输出 → 目标输入；空 = 仅控制流不传数据）
 */
public record EdgeDef(String from, String to, EdgeType type, List<MappingRule> mapping) {

    public EdgeDef {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("edge from must not be blank");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("edge to must not be blank");
        }
        type = type == null ? EdgeType.SEQ : type;
        mapping = mapping == null ? List.of() : List.copyOf(mapping);
    }

    /** 边类型 */
    public enum EdgeType { SEQ, COND, PAR }
}
