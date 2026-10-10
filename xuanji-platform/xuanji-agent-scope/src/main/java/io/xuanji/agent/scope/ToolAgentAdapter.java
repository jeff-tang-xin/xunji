package io.xuanji.agent.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.xuanji.core.api.Tool;
import io.xuanji.core.api.ToolRequest;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 工具 → AgentScope {@link AgentTool} 适配器（「一次实现两处可用」的 Agent 侧，
 * docs/detail/04 §1 工具集成）。
 *
 * <p>把平台自定义 {@link Tool} 包装为 AgentScope 的 {@code AgentTool}——
 * {@link Tool#metadata()} 的 name/description/parameters 即 LLM 可见的函数签名，
 * {@link Tool#invoke(ToolRequest)} 即函数体。Agent 经 ReAct 循环发起 tool_calls，
 * 本类调用真实的工具实现并回填 {@link ToolResultBlock}。</p>
 *
 * <p>Agent 场景 params 恒为空：画布上「属性抽屉配置的参数」在 Agent 调用时由
 * LLM 以函数参数（input）形式提供，无静态配置语义。</p>
 */
class ToolAgentAdapter implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Tool tool;

    ToolAgentAdapter(Tool tool) {
        this.tool = tool;
    }

    @Override
    public String getName() {
        return tool.metadata().name();
    }

    @Override
    public String getDescription() {
        return tool.metadata().description();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getParameters() {
        // core 侧 JsonSchema（JsonNode）→ AgentScope 期望的 Map 形态（隔离边界转换）
        return MAPPER.convertValue(tool.metadata().parameters().document(), Map.class);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
                    var result = tool.invoke(ToolRequest.of(param.getInput(), Map.of()));
                    return ToolResultBlock.text(toJson(result.output()));
                })
                .onErrorResume(e -> Mono.just(
                        ToolResultBlock.error(tool.metadata().name(), rootMessage(e))));
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.toString() : cur.getMessage();
    }
}