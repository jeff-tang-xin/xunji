package io.xuanji.plugin.basic;

import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.NodeCapability;
import io.xuanji.core.api.NodeContext;
import io.xuanji.core.api.NodeKind;
import io.xuanji.core.api.NodeMetadata;
import io.xuanji.core.api.NodeOutput;
import io.xuanji.core.api.WorkflowNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 看板渲染节点（主文档 1.5 工作报表场景）——把上游数据加工节点的产出
 * 转成看板配置 JSON（KPI 卡片 + 明细表），调度执行器在 run SUCCESS 后
 * 将该配置固化为报表快照（report_snapshot），报表详情页据此渲染。
 *
 * <p>P0 确定性模板渲染：输入即看板数据（title/kpis/rows），列定义从
 * rows 首行键推导；LLM 生成看板布局（自然语言→配置）作为后续增强，
 * 本节点契约不变。</p>
 *
 * <p>输入约定（上游 mapping 装配）：
 * {title?: string, kpis?: [{name, value, unit?}], rows?: [object...]}。</p>
 */
@Component
public class BoardRenderNode implements WorkflowNode {

    public static final String ID = "basic.board_render";

    @Override
    public NodeMetadata metadata() {
        return new NodeMetadata(
                ID, "看板渲染", NodeKind.ATOMIC, "basic", "bar-chart",
                "把上游数据渲染为看板配置（KPI 卡片 + 明细表），调度执行后固化为报表快照",
                JsonSchema.anyObject(),
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{
                           "board":{"type":"object","description":"看板配置：title/kpis/columns/rows"}},
                         "required":["board"]}"""),
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{
                           "title":{"type":"string"},
                           "kpis":{"type":"array","items":{"type":"object"}},
                           "rows":{"type":"array","items":{"type":"object"}},
                           "analysis":{"type":"string","description":"Agent 分析结论文本（可选，展示为分析区块）"}}}"""),
                Set.of(NodeCapability.IDEMPOTENT));
    }

    @Override
    public NodeOutput execute(NodeContext ctx) {
        String title = str(ctx.params().get("title"), ctx.input().get("title"), "运营看板");
        List<Map<String, Object>> kpis = listOf(ctx.input().get("kpis"));
        List<Map<String, Object>> rows = listOf(ctx.input().get("rows"));

        // 列定义从 rows 首行键推导（保持字段顺序）
        List<Map<String, Object>> columns = new ArrayList<>();
        if (!rows.isEmpty()) {
            for (Object k : rows.get(0).keySet()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("key", String.valueOf(k));
                col.put("title", String.valueOf(k));
                columns.add(col);
            }
        }

        Map<String, Object> board = new LinkedHashMap<>();
        board.put("title", title);
        board.put("kpis", kpis);
        board.put("columns", columns);
        board.put("rows", rows);
        Object analysis = ctx.input().get("analysis");
        if (analysis != null && !analysis.toString().isBlank()) {
            board.put("analysis", analysis.toString());
        }
        board.put("generatedAt", Instant.now().toString());
        return NodeOutput.of(Map.of("board", board));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object v) {
        if (v instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object e : l) {
                if (e instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    private static String str(Object primary, Object fallback, String def) {
        if (primary != null && !primary.toString().isBlank()) {
            return primary.toString();
        }
        if (fallback != null && !fallback.toString().isBlank()) {
            return fallback.toString();
        }
        return def;
    }
}
