package io.xuanji.plugin.basic;

import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.NodeExecutionException;
import io.xuanji.core.api.Tool;
import io.xuanji.core.api.ToolMetadata;
import io.xuanji.core.api.ToolRequest;
import io.xuanji.core.api.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据聚合工具（数据加工节点）——对数据源查询输出做 KPI 聚合。
 *
 * <p>输入 {@code {rows:[{...}]}}（数据源查询输出），参数
 * {@code {kpis:[{name, field, agg: SUM|COUNT|AVG|MAX|MIN}]}}，
 * 输出 {@code {kpis:[{name, value}], rows:[...]}}（kpis 聚合结果，rows 透传）。
 * 供看板渲染节点消费（看板渲染需要 kpis + rows）。</p>
 *
 * <p>以 Tool SPI 实现（{@code @Component} 由 RegistryConfiguration 自动扫描），
 * 画布 refId = {@code tool.aggregate}，同时作为 Agent 可调用函数。</p>
 */
@Component
public class AggregateNode implements Tool {

    public static final String ID = "aggregate";

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ID, "数据聚合",
                "对数据源查询输出按 KPI 定义做聚合（SUM/COUNT/AVG/MAX/MIN），"
                        + "输出 kpis 聚合结果并透传 rows，供看板渲染消费",
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{
                           "kpis":{"type":"array","items":{
                             "type":"object",
                             "properties":{
                               "name":{"type":"string","description":"KPI 名称"},
                               "field":{"type":"string","description":"参与聚合的 rows 字段名"},
                               "agg":{"type":"string","enum":["SUM","COUNT","AVG","MAX","MIN"],"description":"聚合方式"}},
                             "required":["name","field","agg"]}}},
                         "required":["kpis"]}"""),
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{
                           "kpis":{"type":"array","items":{"type":"object","description":"{name, value}"}},
                           "rows":{"type":"array","items":{"type":"object"}}},
                         "required":["kpis","rows"]}"""));
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws NodeExecutionException {
        List<Map<String, Object>> rows = rowsOf(request.input().get("rows"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kpiDefs = (List<Map<String, Object>>) request.params()
                .getOrDefault("kpis", List.of());

        List<Map<String, Object>> kpis = new ArrayList<>();
        for (Map<String, Object> def : kpiDefs) {
            String name = String.valueOf(def.getOrDefault("name", ""));
            String field = String.valueOf(def.getOrDefault("field", ""));
            String agg = String.valueOf(def.getOrDefault("agg", "COUNT")).toUpperCase();
            double value = aggregate(agg, field, rows);
            Map<String, Object> kpi = new LinkedHashMap<>();
            kpi.put("name", name);
            kpi.put("value", value);
            kpis.add(kpi);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpis", kpis);
        out.put("rows", rows);
        return ToolResult.of(out);
    }

    private static double aggregate(String agg, String field, List<Map<String, Object>> rows) {
        switch (agg) {
            case "COUNT":
                return rows.size();
            case "SUM":
                return rows.stream().mapToDouble(r -> num(r.get(field))).sum();
            case "AVG":
                return rows.isEmpty() ? 0
                        : rows.stream().mapToDouble(r -> num(r.get(field))).average().orElse(0);
            case "MAX":
                return rows.stream().mapToDouble(r -> num(r.get(field))).max().orElse(0);
            case "MIN":
                return rows.stream().mapToDouble(r -> num(r.get(field))).min().orElse(0);
            default:
                throw NodeExecutionException.fatal("AGGREGATE_UNSUPPORTED", "不支持的聚合类型: " + agg);
        }
    }

    private static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(v.toString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Object v) {
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
}