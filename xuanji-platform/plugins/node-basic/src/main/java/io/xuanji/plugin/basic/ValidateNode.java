package io.xuanji.plugin.basic;

import io.xuanji.core.api.ErrorCode;
import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.NodeExecutionException;
import io.xuanji.core.api.Tool;
import io.xuanji.core.api.ToolMetadata;
import io.xuanji.core.api.ToolRequest;
import io.xuanji.core.api.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数据校验工具（内置示例）——演示工具的标准写法：
 * 自描述元数据 + 无状态 invoke + 错误契约。
 *
 * <p>行为：按配置的 requiredFields 检查输入，缺失即抛不可重试失败
 * （SCHEMA_VIOLATION）。P0 用简单字段检查演示契约；真实校验工具可换
 * JSON Schema 断言，工具契约不变。</p>
 *
 * <p>以 Tool SPI 实现（{@code @Component} 由 RegistryConfiguration 自动扫描），
 * 画布 refId = {@code tool.validate}，同时作为 Agent 可调用函数。</p>
 */
@Component
public class ValidateNode implements Tool {

    public static final String ID = "validate";

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ID, "数据校验",
                "按配置检查输入字段完整性，缺失则节点失败",
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{
                           "requiredFields":{"type":"array","items":{"type":"string"},"description":"必须存在的输入字段名列表"}},
                         "required":["requiredFields"]}"""),
                JsonSchema.anyObject());
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws NodeExecutionException {
        @SuppressWarnings("unchecked")
        var required = (List<String>) request.params().getOrDefault(
                "requiredFields", List.of());

        var missing = required.stream()
                .filter(f -> !request.input().containsKey(f) || request.input().get(f) == null)
                .toList();
        if (!missing.isEmpty()) {
            throw NodeExecutionException.fatal(ErrorCode.SCHEMA_VIOLATION,
                    "输入缺少必填字段: " + missing);
        }
        return ToolResult.of(Map.of("valid", true, "checked", required));
    }
}
