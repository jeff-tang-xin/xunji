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
    /**
     * 入参 schema 覆盖（可空）。会话级编排工具（StageOrchestrator/OrchestrationTools）的
     * 详细 JSON Schema 放在 ToolDef.parameters 里，而 Tool.metadata() 只有 anyObject()——
     * 不覆盖的话 LLM 看到的函数签名没有任何参数定义，传参全靠猜，工具必失败。
     */
    private final Map<String, Object> parametersOverride;

    ToolAgentAdapter(Tool tool) {
        this(tool, null);
    }

    ToolAgentAdapter(Tool tool, Map<String, Object> parametersOverride) {
        this.tool = tool;
        this.parametersOverride = parametersOverride == null || parametersOverride.isEmpty()
                ? null : parametersOverride;
    }

    @Override
    public String getName() {
        // LLM function-calling 的函数名必须匹配 ^[a-zA-Z0-9_-]+$（OpenAI 兼容 API 硬约束，
        // 中文名会被网关/上游 400 InvalidParameter——实测步骤 8/9 全挂）。
        // 显示名（中文）合法时直接用（编排/内置工具全是英文名，不受影响）；
        // 非法时从 id 派生安全名（datasource.query -> datasource_query），中文 label 挪进 description 保语义。
        String name = tool.metadata().name();
        if (name.matches("[a-zA-Z0-9_-]+")) {
            return name;
        }
        return tool.metadata().id().replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public String getDescription() {
        String desc = tool.metadata().description();
        String name = tool.metadata().name();
        if (!name.matches("[a-zA-Z0-9_-]+") && !desc.startsWith("【")) {
            // 函数名被替换成英文 id 时，把中文显示名带进描述，LLM 仍能按中文名理解工具用途
            return "【" + name + "】" + desc;
        }
        return desc;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getParameters() {
        if (parametersOverride != null) {
            return parametersOverride;
        }
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