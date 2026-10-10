package io.xuanji.plugin.basic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.xuanji.core.api.ErrorCode;
import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.NodeCapability;
import io.xuanji.core.api.NodeContext;
import io.xuanji.core.api.NodeExecutionException;
import io.xuanji.core.api.NodeKind;
import io.xuanji.core.api.NodeMetadata;
import io.xuanji.core.api.NodeOutput;
import io.xuanji.core.api.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 数据校验节点（内置示例）——演示插件节点的标准写法：
 * 自描述元数据 + 无状态 execute + 错误契约。
 *
 * <p>行为：按配置的 requiredFields 检查输入，缺失即抛不可重试失败
 * （SCHEMA_VIOLATION）。P0 用简单字段检查演示契约；真实校验节点可换
 * JSON Schema 断言，节点契约不变。</p>
 */
@Component
public class ValidateNode implements WorkflowNode {

    public static final String ID = "basic.validate";

    @Override
    public NodeMetadata metadata() {
        ObjectNode requiredField = JsonNodeFactory.instance.objectNode()
                .put("type", "array")
                .put("description", "必须存在的输入字段名列表");
        requiredField.set("items", JsonNodeFactory.instance.objectNode().put("type", "string"));

        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        properties.set("requiredFields", requiredField);

        ObjectNode paramSchema = JsonNodeFactory.instance.objectNode();
        paramSchema.put("type", "object");
        paramSchema.set("properties", properties);
        paramSchema.set("required", JsonNodeFactory.instance.arrayNode().add("requiredFields"));

        return new NodeMetadata(
                ID, "数据校验", NodeKind.ATOMIC, "basic", "check-circle",
                "按配置检查输入字段完整性，缺失则节点失败",
                JsonSchema.anyObject(),
                JsonSchema.anyObject(),
                new JsonSchema(paramSchema),
                Set.of(NodeCapability.SUPPORTS_RETRY, NodeCapability.IDEMPOTENT));
    }

    @Override
    public NodeOutput execute(NodeContext ctx) throws NodeExecutionException {
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) ctx.params().getOrDefault(
                "requiredFields", java.util.List.of());

        var missing = required.stream()
                .filter(f -> !ctx.input().containsKey(f) || ctx.input().get(f) == null)
                .toList();
        if (!missing.isEmpty()) {
            throw NodeExecutionException.fatal(ErrorCode.SCHEMA_VIOLATION,
                    "输入缺少必填字段: " + missing + "（nodeRunId=" + ctx.nodeRunId() + "）");
        }
        return NodeOutput.of(Map.of("valid", true, "checked", required));
    }
}
