package io.xuanji.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.core.api.AgentCapabilityBinding;
import io.xuanji.core.api.AssetRef;
import io.xuanji.core.api.AssetType;
import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.NodeKind;
import io.xuanji.core.definition.DecisionGateDef;
import io.xuanji.core.definition.EdgeDef;
import io.xuanji.core.definition.MappingRule;
import io.xuanji.core.definition.NodeDef;
import io.xuanji.core.definition.RetryPolicy;
import io.xuanji.core.definition.TriggerDef;
import io.xuanji.core.definition.TriggerType;
import io.xuanji.core.definition.WorkflowDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画布 JSON 解析器——definition format v1（docs/detail/03 §1）→ {@link WorkflowDefinition}。
 *
 * <p>纯 Jackson 实现，不依赖任何引擎类型（core 禁止依赖 LiteFlow）。解析即校验
 * 结构合法性（缺字段/枚举值非法抛 IllegalArgumentException），图结构校验
 * （环/连通/单 START）由 {@link LiteFlowRuleCompiler} 编译期执行。</p>
 *
 * <p>capabilityBinding 的引用格式为 {@code asset:id@version}（如 skill:x@2、mcp:y@1），
 * 与 {@link AssetRef#toString()} 一致。</p>
 */
public final class CanvasJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 解析画布 JSON 文本 */
    public static WorkflowDefinition parse(String canvasJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(canvasJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("canvas json is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("canvas json must be a JSON object");
        }

        JsonNode meta = root.path("meta");
        String name = meta.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("meta.name is required");
        }

        List<TriggerDef> triggers = new ArrayList<>();
        for (JsonNode t : root.path("triggers")) {
            triggers.add(new TriggerDef(
                    TriggerType.valueOf(t.path("type").asText().toUpperCase()),
                    toMap(t.path("config"))));
        }

        List<NodeDef> nodes = new ArrayList<>();
        for (JsonNode n : root.path("nodes")) {
            nodes.add(parseNode(n));
        }

        List<EdgeDef> edges = new ArrayList<>();
        for (JsonNode e : root.path("edges")) {
            List<MappingRule> mapping = new ArrayList<>();
            for (JsonNode m : e.path("mapping")) {
                mapping.add(new MappingRule(m.path("from").asText(), m.path("to").asText()));
            }
            edges.add(new EdgeDef(
                    e.path("from").asText(),
                    e.path("to").asText(),
                    EdgeDef.EdgeType.valueOf(e.path("type").asText("SEQ").toUpperCase()),
                    mapping));
        }

        int version = root.path("definitionVersion").asInt(0);
        return new WorkflowDefinition(version, name, meta.path("description").asText(null),
                triggers, nodes, edges);
    }

    private static NodeDef parseNode(JsonNode n) {
        String instanceId = n.path("id").asText(null);
        String refId = n.path("refId").asText(null);
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("node.id is required");
        }
        NodeKind kind = NodeKind.valueOf(n.path("kind").asText("ATOMIC").toUpperCase());
        if (refId == null || refId.isBlank()) {
            // START/END 平台内置节点允许缺省 refId
            if (kind == NodeKind.START) {
                refId = NodeDef.START_REF;
            } else if (kind == NodeKind.END) {
                refId = NodeDef.END_REF;
            } else {
                throw new IllegalArgumentException("node.refId is required (id=" + instanceId + ")");
            }
        }

        JsonNode inputSchema = n.path("inputSchema");
        JsonNode outputSchema = n.path("outputSchema");

        AgentCapabilityBinding binding = null;
        JsonNode cb = n.path("capabilityBinding");
        if (cb.isObject()) {
            binding = new AgentCapabilityBinding(
                    parseRefs(cb.path("skills"), AssetType.SKILL),
                    parseRefs(cb.path("mcps"), AssetType.MCP));
        }

        JsonNode retry = n.path("retry");
        RetryPolicy policy = retry.isObject()
                ? new RetryPolicy(retry.path("maxAttempts").asInt(0), retry.path("intervalMs").asLong(0))
                : RetryPolicy.none();

        JsonNode dg = n.path("decisionGate");
        DecisionGateDef gate = dg.isObject() && dg.path("enabled").asBoolean(false)
                ? new DecisionGateDef(true,
                        DecisionGateDef.Interaction.valueOf(dg.path("interaction").asText("WORKSPACE").toUpperCase()),
                        dg.path("artifactRef").asText(null),
                        DecisionGateDef.RejectPolicy.valueOf(dg.path("onReject").asText("REDO").toUpperCase()),
                        dg.path("timeout").asText(null),
                        DecisionGateDef.TimeoutPolicy.valueOf(dg.path("onTimeout").asText("ESCALATE").toUpperCase()))
                : DecisionGateDef.disabled();

        return new NodeDef(instanceId, refId, kind, n.path("name").asText(instanceId),
                toMap(n.path("params")),
                inputSchema.isObject() ? new JsonSchema(inputSchema) : null,
                outputSchema.isObject() ? new JsonSchema(outputSchema) : null,
                binding, gate, policy,
                n.has("timeoutMs") && n.path("timeoutMs").isNumber() ? n.path("timeoutMs").asLong() : null);
    }

    /** 解析 "skill:id@version" 形式的引用数组 */
    private static List<AssetRef> parseRefs(JsonNode arr, AssetType fallbackType) {
        List<AssetRef> refs = new ArrayList<>();
        for (JsonNode r : arr) {
            String s = r.asText();
            // [type:]id@version —— type 缺省用 fallbackType
            AssetType type = fallbackType;
            String rest = s;
            int colon = s.indexOf(':');
            if (colon > 0) {
                type = AssetType.valueOf(s.substring(0, colon).toUpperCase());
                rest = s.substring(colon + 1);
            }
            int at = rest.lastIndexOf('@');
            if (at <= 0) {
                throw new IllegalArgumentException("asset ref must be [type:]id@version: " + s);
            }
            refs.add(new AssetRef(type, rest.substring(0, at), Integer.parseInt(rest.substring(at + 1))));
        }
        return refs;
    }

    private static Map<String, Object> toMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), MAPPER.convertValue(e.getValue(), Object.class)));
        return map;
    }

    private CanvasJsonParser() {
    }
}
