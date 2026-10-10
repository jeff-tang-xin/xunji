package io.xuanji.core.definition;

/**
 * 数据流转规则（edges[].mapping 项）——「下一个节点引入上一个阶段的输出作为输入」。
 *
 * <p>JSONPath 表达式约定（docs/detail/03 §1）：</p>
 * <ul>
 *   <li>{@code from} 源：{@code $.{nodeId}.output.*}（上游节点输出）或 {@code $.vars.*}（run 变量区）</li>
 *   <li>{@code to} 目标：{@code $.input.*}（下游节点输入）或 {@code $.vars.*}</li>
 * </ul>
 *
 * <p>引擎在执行节点前按 mapping 装配 {@code NodeContext.input}（不进 LiteFlow EL），
 * 装配结果按组件 inputSchema 校验——数据契约在发布期与执行期双重把关。</p>
 *
 * @param from 源表达式（上游输出/变量区）
 * @param to   目标表达式（下游输入/变量区）
 */
public record MappingRule(String from, String to) {

    public MappingRule {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("mapping from must not be blank");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("mapping to must not be blank");
        }
    }
}
