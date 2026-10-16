package io.xuanji.agent.scope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.xuanji.core.runtime.AgentMessage;
import io.xuanji.core.runtime.AgentResult;
import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.core.runtime.ToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * agent.invoke 对等互调工具（tools_json type=AGENT 的 AgentScope 适配）：
 * LLM 经 ReAct 循环发起 tool_calls，本工具用 {@link ToolDef#payload()} 携带的被调
 * {@link AgentSpec} 起一个子 Agent，把 query 交给它跑一轮对话（阻塞取最终文本），
 * 回填 {@link ToolResultBlock}。
 *
 * <p>被调 Agent 是「模型+提示词+技能」的对话体——server 侧 resolveAgentTools 已不展开其
 * 自身工具（resolvedTools 空），以杜绝 A→B→A 递归。runtime 由 {@link AgentScopeRuntime}
 * 自身提供（同模块，直接调 createAgent/call/close）。</p>
 */
class AgentInvokeTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(AgentInvokeTool.class);

    private final ToolDef def;
    private final AgentSpec targetSpec;
    private final AgentScopeRuntime runtime;

    AgentInvokeTool(ToolDef def, AgentScopeRuntime runtime) {
        this.def = def;
        this.runtime = runtime;
        this.targetSpec = def.payload() instanceof AgentSpec s ? s : null;
    }

    @Override
    public String getName() {
        String name = def.name();
        // LLM function-calling 函数名硬约束 ^[a-zA-Z0-9_-]+$（中文名被上游 400 InvalidParameter——
        // ToolAgentAdapter 实测踩坑），非法名 sanitize 成下划线
        return name.matches("[a-zA-Z0-9_-]+") ? name : name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public String getDescription() {
        return def.description() == null ? def.name() : def.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        return def.parameters();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> invoke(param.getInput()))
                .onErrorResume(e -> {
                    log.warn("agent.invoke {} failed: {}", def.name(), e.toString());
                    return Mono.just(ToolResultBlock.error(def.name(), rootMessage(e)));
                });
    }

    private ToolResultBlock invoke(Map<String, Object> input) {
        if (targetSpec == null) {
            return ToolResultBlock.error(def.name(), "被调 AgentSpec 缺失");
        }
        String query = extractQuery(input);
        var handle = runtime.createAgent(targetSpec);
        try {
            AgentResult r = runtime.call(handle, new AgentMessage(query, null));
            String out = r.text() == null ? "" : r.text();
            log.info("agent.invoke {} → 被调 Agent 输出 {} 字", def.name(), out.length());
            return ToolResultBlock.text(out.isBlank() ? "(被调 Agent 无输出)" : truncate(out));
        } finally {
            try {
                runtime.close(handle);
            } catch (Exception ignore) {
                // 释放失败不影响结果
            }
        }
    }

    private String extractQuery(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Object q = input.get("query");
        if (q != null) {
            return String.valueOf(q);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }

    private String truncate(String s) {
        return s.length() <= 4000 ? s : s.substring(0, 4000) + "...(truncated)";
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.toString() : cur.getMessage();
    }
}