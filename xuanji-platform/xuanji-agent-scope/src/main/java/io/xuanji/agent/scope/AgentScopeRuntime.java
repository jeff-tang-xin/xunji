package io.xuanji.agent.scope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.xuanji.core.api.Tool;
import io.xuanji.core.runtime.AgentHandle;
import io.xuanji.core.runtime.AgentMessage;
import io.xuanji.core.runtime.AgentResult;
import io.xuanji.core.runtime.AgentRuntime;
import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.core.runtime.AgentStreamEvent;
import io.xuanji.core.runtime.ToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AgentRuntime 的 AgentScope 实现（docs/detail/04 §1）——{@code io.agentscope}
 * 在平台内的唯一落点。
 *
 * <p>P0 范围（阻塞形态闭环）：</p>
 * <ul>
 *   <li>模型装配：OPENAI_PROTOCOL（Q5 双轨之一）经 model-openai 扩展构建
 *       {@link OpenAIChatModel}，覆盖 OpenAI 官方与一切兼容端点（dashscope 兼容模式/
 *       DeepSeek/Moonshot/vLLM 等）；ANTHROPIC_PROTOCOL 待 model-anthropic 扩展接入</li>
 *   <li>Agent 构建：{@link ReActAgent}（轻量形态；HarnessAgent 随 workspace/记忆/技能
 *       开关在 P3+ 评估）</li>
 *   <li>执行：{@code call} 阻塞聚合（内部 ReAct 循环至最终回复）；流式 stream 与
 *       权限回调（HITL）随 chat SSE 集成增补</li>
 *   <li>工具：P0 不注册（toolkit 空）；tool_registry/MCP/agent.invoke 三来源注册随
 *       工具集成阶段落地</li>
 * </ul>
 */
@Component
public class AgentScopeRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeRuntime.class);

    private static final String PROTOCOL_OPENAI = "OPENAI_PROTOCOL";
    private static final int DEFAULT_MAX_ITERS = 10;

    /**
     * 会话状态存储（多轮记忆，docs/detail/04 §1）：所有 Agent 实例共享——
     * 实例按 spec.sessionId() 挂接，执行后 AgentState（含对话上下文）持久化于此，
     * 新实例以同 sessionId 构建时恢复历史。P0 用 PostgreSQL 实现（V21__agent_state.sql），
     * 跨重启/多副本记忆不丢；userId 匿名（AgentScopeRuntime 不传 userId）时归一到
     * "__anon__" 命名空间。
     */
    private final AgentStateStore stateStore;

    /** 代码工具（Tool SPI bean）提供者——全局注册为 Agent 可调用能力（平台级，P0 简化） */
    private final ObjectProvider<Tool> toolProvider;

    public AgentScopeRuntime(ObjectProvider<Tool> toolProvider, JdbcTemplate jdbcTemplate) {
        this.toolProvider = toolProvider;
        this.stateStore = new PostgresAgentStateStore(jdbcTemplate);
    }

    @Override
    public AgentHandle createAgent(AgentSpec spec) {
        if (!PROTOCOL_OPENAI.equals(spec.model().protocol())) {
            throw new UnsupportedOperationException(
                    "model protocol not supported yet: " + spec.model().protocol()
                            + "（ANTHROPIC_PROTOCOL 随 model-anthropic 扩展接入）");
        }
        OpenAIChatModel model = OpenAIChatModel.builder()
                .baseUrl(spec.model().baseUrl())
                .apiKey(spec.model().apiKey())
                .modelName(spec.model().model())
                .generateOptions(generateOptions(spec))
                .build();
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(spec.name())
                .sysPrompt(composeSystemPrompt(spec))
                .model(model)
                .maxIters(maxIters(spec));
        if (spec.sessionId() != null && !spec.sessionId().isBlank()) {
            // 多轮记忆：状态外置——实例无状态化，记忆跨实例持久于 stateStore
            builder.stateStore(stateStore).defaultSessionId(spec.sessionId());
        }
        // 工具装配（docs/detail/04 §1）：① 数据工具 OPENAPI → HttpAgentTool；② 代码工具 SPI → ToolAgentAdapter
        List<io.agentscope.core.tool.AgentTool> agentTools = collectAgentTools(spec);
        if (!agentTools.isEmpty()) {
            io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
            for (io.agentscope.core.tool.AgentTool at : agentTools) {
                toolkit.registerAgentTool(at);
            }
            builder.toolkit(toolkit);
        }
        ReActAgent agent = builder.build();
        log.info("agent created: config={} model={}/{} session={}", spec.agentConfigId(),
                spec.model().protocol(), spec.model().model(),
                spec.sessionId() == null ? "(stateless)" : spec.sessionId());
        return new AgentScopeHandle(spec, agent);
    }

    /**
     * 组装最终 system prompt：基础 system_prompt + 已绑定 skill 内容
     * （Anthropic Agent Skills 形态 P0——引用即整体注入，非按需渐进披露）。
     */
    private String composeSystemPrompt(AgentSpec spec) {
        String base = spec.systemPrompt() == null ? "" : spec.systemPrompt();
        if (spec.skills().isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base);
        sb.append("\n\n## 可用技能（Skills）\n")
                .append("当任务匹配以下技能的适用场景时，严格遵循其操作指引完成任务。\n");
        for (io.xuanji.core.runtime.SkillDef s : spec.skills()) {
            sb.append("\n### 技能：").append(s.name()).append("\n");
            if (s.description() != null && !s.description().isBlank()) {
                sb.append(s.description()).append("\n\n");
            }
            if (s.content() != null && !s.content().isBlank()) {
                sb.append(s.content().trim()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * AgentMessage → AgentScope UserMessage：无附件走纯文本（原路径不变）；
     * 图片附件转 ImageBlock(Base64Source) 多模态块，文本文件内容拼进提示文本。
     * 模型侧需支持视觉（如 qwen-vl 系列），不支持时由模型端点报错透出。
     */
    private UserMessage toUserMessage(AgentMessage msg) {
        List<AgentMessage.Attachment> atts = msg.attachments() == null ? List.of() : msg.attachments();
        if (atts.isEmpty()) {
            return new UserMessage(msg.text());
        }
        StringBuilder text = new StringBuilder(msg.text());
        List<ContentBlock> blocks = new java.util.ArrayList<>();
        for (AgentMessage.Attachment a : atts) {
            if (a.isImage()) {
                blocks.add(new ImageBlock(new Base64Source(a.mediaType(), a.dataBase64())));
                text.append("\n\n[用户附带图片: ").append(a.name() == null ? "image" : a.name()).append(']');
            } else if (a.isTextFile()) {
                text.append("\n\n[附件: ").append(a.name()).append("]\n").append(a.content());
            }
        }
        blocks.add(0, TextBlock.builder().text(text.toString()).build());
        return new UserMessage(blocks);
    }

    @Override
    public AgentResult call(AgentHandle handle, AgentMessage msg) {
        if (!(handle instanceof AgentScopeHandle h)) {
            throw new IllegalArgumentException("unknown handle type: " + handle.getClass());
        }
        Msg reply = h.agent().call(List.of(toUserMessage(msg))).block();
        if (reply == null) {
            throw new IllegalStateException("agent returned no reply: " + h.spec().agentConfigId());
        }
        String text = reply.getTextContent();
        Map<String, Object> meta = Map.of(
                "agentId", h.agent().getAgentId(),
                "sessionId", msg.sessionId() == null ? "" : msg.sessionId());
        return new AgentResult(text, Map.of(), meta);
    }

    @Override
    public void close(AgentHandle handle) {
        if (handle instanceof AgentScopeHandle h) {
            h.agent().close();
        }
    }

    /**
     * 流式执行：AgentScope streamEvents 事件流 → 平台 AgentStreamEvent 映射。
     * 映射关系：AgentStart→agent.start、TextBlockDelta→agent.delta、
     * AgentResult→agent.result、AgentEnd→agent.end、ToolCallStart→tool.call、
     * ToolResultEnd→tool.result；thinking/permission 等其余事件类型随 HITL
     * 启用时增补，当前忽略。
     * 阻塞至流结束（调用方负责在异步线程执行，见 ChatService 虚拟线程）。
     */
    @Override
    public void stream(AgentHandle handle, AgentMessage msg, java.util.function.Consumer<AgentStreamEvent> sink) {
        if (!(handle instanceof AgentScopeHandle h)) {
            throw new IllegalArgumentException("unknown handle type: " + handle.getClass());
        }
        try {
            h.agent().streamEvents(List.of(toUserMessage(msg)))
                    .doOnNext(evt -> {
                        try {
                            if (evt instanceof io.agentscope.core.event.AgentStartEvent e) {
                                sink.accept(AgentStreamEvent.of("agent.start", Map.of(
                                        "sessionId", str(e.getSessionId()), "replyId", str(e.getReplyId()),
                                        "name", str(e.getName()))));
                            } else if (evt instanceof io.agentscope.core.event.TextBlockDeltaEvent e) {
                                sink.accept(AgentStreamEvent.delta(e.getDelta()));
                            } else if (evt instanceof io.agentscope.core.event.AgentResultEvent e) {
                                sink.accept(AgentStreamEvent.result(
                                        e.getResult() == null ? null : e.getResult().getTextContent()));
                            } else if (evt instanceof io.agentscope.core.event.ToolCallStartEvent e) {
                                sink.accept(AgentStreamEvent.of("tool.call", Map.of(
                                        "toolCallId", str(e.getToolCallId()),
                                        "tool", str(e.getToolCallName()))));
                            } else if (evt instanceof io.agentscope.core.event.ToolResultEndEvent e) {
                                sink.accept(AgentStreamEvent.of("tool.result", Map.of(
                                        "toolCallId", str(e.getToolCallId()),
                                        "tool", str(e.getToolCallName()),
                                        "state", e.getState() == null ? "" : e.getState().name())));
                            } else if (evt instanceof io.agentscope.core.event.AgentEndEvent) {
                                sink.accept(AgentStreamEvent.of("agent.end", Map.of()));
                            }
                        } catch (RuntimeException ex) {
                            log.warn("event map failed: {}", ex.toString());
                        }
                    })
                    .blockLast();
        } catch (RuntimeException e) {
            sink.accept(AgentStreamEvent.error(rootMessage(e)));
            throw e;
        }
    }

    private String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.toString() : cur.getMessage();
    }

    private GenerateOptions generateOptions(AgentSpec spec) {
        GenerateOptions.Builder b = GenerateOptions.builder();
        Map<String, Object> params = spec.model().params();
        if (params != null) {
            Object t = params.get("temperature");
            if (t instanceof Number n) {
                b.temperature(n.doubleValue());
            }
            Object mt = params.get("maxTokens");
            if (mt instanceof Number n) {
                b.maxTokens(n.intValue());
            }
            Object tp = params.get("topP");
            if (tp instanceof Number n) {
                b.topP(n.doubleValue());
            }
        }
        return b.build();
    }

    private int maxIters(AgentSpec spec) {
        Object v = spec.runtime().get("maxIterations");
        return v instanceof Number n ? n.intValue() : DEFAULT_MAX_ITERS;
    }

    /**
     * 收集 Agent 可调用工具（docs/detail/04 §1）：
     * ① 数据工具——spec.resolvedTools 里的 OPENAPI（tool_registry 资产，钉版本）；
     * ② 代码工具——Tool SPI bean（平台级能力，P0 全局注册，后续可按 agent_config 声明过滤）。
     */
    private List<io.agentscope.core.tool.AgentTool> collectAgentTools(AgentSpec spec) {
        List<io.agentscope.core.tool.AgentTool> tools = new java.util.ArrayList<>();
        for (ToolDef tool : spec.resolvedTools()) {
            switch (tool.sourceType()) {
                case "OPENAPI" -> tools.add(new HttpAgentTool(tool));
                case "MCP" -> tools.add(new McpAgentTool(tool));
                case "AGENT" -> tools.add(new AgentInvokeTool(tool, this));
                case "BEAN" -> {
                    // 会话级编排工具（StageOrchestrator）：payload 携带 Tool 实例，只挂给主控。
                    // schema 用 ToolDef.parameters（详细 JSON Schema）——Tool.metadata() 只有 anyObject()，
                    // 不覆盖 LLM 就看不到参数定义（实测 plan_steps 连续返回「steps 不能为空」）。
                    if (tool.payload() instanceof io.xuanji.core.api.Tool t) {
                        tools.add(new ToolAgentAdapter(t, tool.parameters()));
                    } else {
                        log.warn("BEAN tool payload is not a Tool instance, skipped: {}", tool.name());
                    }
                }
                default -> log.warn("tool source_type={} not supported yet, skipped: {}",
                        tool.sourceType(), tool.name());
            }
        }
        for (Tool tool : toolProvider.stream().toList()) {
            tools.add(new ToolAgentAdapter(tool));
        }
        return tools;
    }

    /** 不透明句柄：平台其余代码不得解包（AgentHandle 契约） */
    record AgentScopeHandle(AgentSpec spec, ReActAgent agent) implements AgentHandle {
    }
}
