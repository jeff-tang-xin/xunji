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
import java.util.Set;
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
    private static final int DEFAULT_MAX_ITERS = 300;

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

    /**
     * 会话级授权检查（server 实现 ToolApprovalStore，依赖倒置）：shell 两级授权的
     * 「项目内当前 session 授权」侧。可空（纯 agent-scope 测试环境无实现）。
     */
    private final ObjectProvider<ToolApprovalChecker> approvalChecker;

    /** shell 命令白名单（逗号分隔，application.yml xuanji.agent.shell-allowed 可覆盖）；
     * node/npm/npx 供 developer/tester 跑构建与测试自证（2026-10-19 责任链改造） */
    @org.springframework.beans.factory.annotation.Value("${xuanji.agent.shell-allowed:git,mvn,java,ls,cat,pwd,echo,tail,head,grep,find,docker,systemctl,node,npm,npx}")
    private String shellAllowed;

    /**
     * ASK 挂起实例缓存：sessionId → 等待用户确认的 agent + 本次 ASKING 的工具调用块。
     * 工具调用被权限引擎判 ASK 时流以 agent.confirm 事件结束，实例不能销毁
     * （pending ASKING 状态在实例内存里）；用户批准后 confirm() 取出恢复执行。
     * 会话粒度串行假设（同会话不并发对话）。
     */
    private record Suspended(AgentScopeHandle handle,
                             List<io.agentscope.core.message.ToolUseBlock> askingCalls) {
    }

    private final Map<String, Suspended> suspended = new java.util.concurrent.ConcurrentHashMap<>();

    public AgentScopeRuntime(ObjectProvider<Tool> toolProvider, JdbcTemplate jdbcTemplate,
                             ObjectProvider<ToolApprovalChecker> approvalChecker) {
        this.toolProvider = toolProvider;
        this.approvalChecker = approvalChecker;
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
        // 工具装配（docs/detail/04 §1）：① 数据工具 OPENAPI → HttpAgentTool；② 代码工具 SPI → ToolAgentAdapter；
        // ③ 内置工具（AgentScope 自带 fs/shell，按 agent_config 白名单启用，baseDir 硬限项目目录）
        List<io.agentscope.core.tool.AgentTool> agentTools = collectAgentTools(spec);
        io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
        for (io.agentscope.core.tool.AgentTool at : agentTools) {
            toolkit.registerAgentTool(at);
        }
        registerBuiltinTools(toolkit, spec);
        builder.toolkit(toolkit);
        // 权限上下文（HITL ask 机制）：永久授权（白名单）与 session 授权 → ALLOW 规则；
        // 无规则匹配（如未授权的 shell）→ DEFAULT mode 默认 ASK → RequireUserConfirmEvent 单次授权
        builder.permissionContext(permissionContext(spec));
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
        String withTools = appendToolSection(spec, base);
        if (spec.skills().isEmpty()) {
            return withTools;
        }
        base = withTools;
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
     * 系统提示词追加「可用工具」段：平台工具（resolvedTools）+ 内置工具（白名单启用的
     * fs/shell）逐个列 name + 说明，并写明授权纪律——LLM 只有看到工具说明才会用、
     * 只有知道「未授权会询问用户」才不会把 DENIED 当故障。
     */
    private String appendToolSection(AgentSpec spec, String base) {
        List<ToolDef> tools = spec.resolvedTools();
        boolean hasBuiltin = !spec.builtinTools().isEmpty();
        if (tools.isEmpty() && !hasBuiltin) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base);
        sb.append("\n\n## 可用工具（Tools）\n");
        sb.append("除对话外，你可以调用以下工具完成任务。工具由系统执行，参数按定义传入。\n");
        for (ToolDef t : tools) {
            sb.append("- ").append(t.name());
            if (t.description() != null && !t.description().isBlank()) {
                sb.append("：").append(truncateLine(t.description()));
            }
            sb.append("\n");
        }
        if (hasBuiltin) {
            sb.append("\n### 内置工具\n");
            for (String b : spec.builtinTools()) {
                String bare = b.endsWith(":always") ? b.substring(0, b.length() - 7) : b;
                switch (bare) {
                    case "fs.read" -> sb.append("- view_text_file / list_directory：查看项目目录内的文件内容与目录结构（含行号范围）\n");
                    case "fs.write" -> sb.append("- write_text_file / insert_text_file：在项目目录内写入/插入文件内容\n");
                    case "shell" -> sb.append("- execute_shell_command：在项目目录内执行 shell 命令（白名单命令）\n");
                    default -> { }
                }
            }
            sb.append("\n【工具纪律】\n");
            // 提示里必须给绝对路径（与内置工具 baseDir 的 toAbsolutePath 口径一致）：
            // DB 落库的 root_path 是相对路径，LLM 照抄会拼出 ./workspaces/... 传参，
            // 而工具按 baseDir.resolve 相对解析成不存在的嵌套路径（实测踩坑）
            String absRoot = spec.workspaceRoot() == null ? null
                    : java.nio.file.Path.of(spec.workspaceRoot()).toAbsolutePath().normalize().toString();
            sb.append("1. 所有文件操作严格限定在项目目录内").append(
                    absRoot == null ? "" : "（" + absRoot + "）").append("，越界会被拒绝；\n");
            sb.append("2. shell 命令执行前系统会向用户请求确认——你发起调用后若收到等待确认的提示，请告知用户「已在界面上发起授权请求」，不要重复调用同一命令；\n");
            sb.append("3. 用户拒绝或未授权时，不要绕过（如用其他工具拼等效操作），改为向用户说明需要该操作的原因；\n");
            sb.append("4. 文件写入前先读原文件确认现状，产出文件放项目目录内的规范路径；\n");
            sb.append("5. 文件/目录路径参数一律相对于项目根目录：列根目录本身传 \".\"，子路径直接写相对路径（如 \"docs/PRD.md\"），不要带 \"./workspaces/...\" 前缀。\n");
        }
        return sb.toString();
    }

    private String truncateLine(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
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
            // ASK 挂起中的实例不销毁——pending ASKING 状态在实例内存里，confirm() 恢复要用
            String sid = h.spec().sessionId();
            boolean awaiting = sid != null && suspended.values().stream()
                    .anyMatch(s -> s.handle() == h);
            if (awaiting) {
                log.info("skip close, agent suspended awaiting confirm: session={}", sid);
                return;
            }
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
        boolean[] confirmFlag = new boolean[1];
        java.util.List<io.agentscope.core.message.ToolUseBlock>[] confirmCalls =
                new java.util.List[1];
        try {
            h.agent().streamEvents(List.of(toUserMessage(msg)))
                    .doOnNext(evt -> {
                        try {
                            if (evt instanceof io.agentscope.core.event.RequireUserConfirmEvent e) {
                                confirmFlag[0] = true;
                                confirmCalls[0] = e.getToolCalls();
                                List<Map<String, Object>> calls = new java.util.ArrayList<>();
                                for (io.agentscope.core.message.ToolUseBlock tc : e.getToolCalls()) {
                                    calls.add(Map.of(
                                            "toolCallId", str(tc.getId()),
                                            "tool", str(tc.getName()),
                                            "input", tc.getInput() == null ? Map.of() : tc.getInput()));
                                }
                                sink.accept(AgentStreamEvent.of("agent.confirm", Map.of(
                                        "sessionId", str(h.spec().sessionId()),
                                        "replyId", str(e.getReplyId()),
                                        "toolCalls", calls)));
                                return;
                            }
                            if (evt instanceof io.agentscope.core.event.AgentStartEvent e) {
                                sink.accept(AgentStreamEvent.of("agent.start", Map.of(
                                        "sessionId", str(e.getSessionId()), "replyId", str(e.getReplyId()),
                                        "name", str(e.getName()))));
                            } else if (evt instanceof io.agentscope.core.event.ModelCallStartEvent) {
                                // 每轮 LLM 调用开始：qwen 生成 tool_call 参数期间没有任何文本 delta，
                                // 不发这个事件前端会干等 1-3 分钟不知道 AI 在干嘛（实测反馈）
                                sink.accept(AgentStreamEvent.of("llm.start", Map.of()));
                            } else if (evt instanceof io.agentscope.core.event.ThinkingBlockDeltaEvent e) {
                                // 思考文本流（thinking 模型才有）：思考过程可视化
                                sink.accept(AgentStreamEvent.of("thinking.delta", Map.of("delta", str(e.getDelta()))));
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
        } finally {
            // 流结束：若存在未确认的 ASKING 工具调用（本轮出现过 agent.confirm），实例挂起缓存
            // 等待 confirm() 恢复；否则正常（调用方 close 释放）。
            if (confirmFlag[0] && h.spec().sessionId() != null && !h.spec().sessionId().isBlank()) {
                suspended.put(h.spec().sessionId(), new Suspended(
                        h, confirmCalls[0] == null ? List.of() : confirmCalls[0]));
                log.info("agent suspended awaiting confirm: session={}", h.spec().sessionId());
            }
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
        // 模型执行配置：AgentScope MODEL_DEFAULTS 超时 5 分钟（ExecutionConfig 字节码实锤），
        // qwen 慢生成单次可超 5 分钟 → Flux.timeout 触发 cancel → interrupt 任务线程 →
        // 步骤被误标「执行被中断」（「勘探完成产物未落盘」×5 实锤）。
        // 2026-10-20 用户定调「没有时间限制」：24h 为防句柄泄漏的工程上限，实际等于不设限。
        b.executionConfig(io.agentscope.core.model.ExecutionConfig.builder()
                .timeout(java.time.Duration.ofHours(24))
                .maxAttempts(2)
                .initialBackoff(java.time.Duration.ofSeconds(2))
                .maxBackoff(java.time.Duration.ofSeconds(30))
                .backoffMultiplier(2.0)
                .retryOn(io.agentscope.core.model.ExecutionConfig.RETRYABLE_ERRORS)
                .build());
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

    // 300 轮统一上限（2026-10-20 用户定调「步数统一 300 最大，没有时间限制」）：
    // 10 轮曾把 architect 复杂任务掐成半成品（seq4「因迭代上限中断」实锤）；所有 agent 统一走此默认
    //（无调用方设置 maxIterations），用户可随时人为终止，步数是唯一护栏
    private int maxIters(AgentSpec spec) {
        Object v = spec.runtime().get("maxIterations");
        return v instanceof Number n ? n.intValue() : DEFAULT_MAX_ITERS;
    }

    /**
     * HITL 确认回填：从挂起缓存取实例，把用户决定包装为框架 ConfirmResult
     * （经 Msg.METADATA_CONFIRM_RESULTS 元数据二次 call 恢复执行），事件继续推 sink。
     * rememberSession=true 时同步写会话级授权（经 checker 实现，跨实例生效）。
     */
    @Override
    public void confirm(AgentHandle handle, List<io.xuanji.core.runtime.AgentConfirm> confirms,
                        java.util.function.Consumer<io.xuanji.core.runtime.AgentStreamEvent> sink) {
        if (!(handle instanceof AgentScopeHandle h)) {
            throw new IllegalArgumentException("unknown handle type: " + handle.getClass());
        }
        String sessionId = h.spec().sessionId();
        Suspended pending = sessionId == null ? null : suspended.remove(sessionId);
        if (pending == null || pending.handle() != h) {
            throw new IllegalStateException("no suspended agent awaiting confirm: session=" + sessionId);
        }
        List<io.agentscope.core.event.ConfirmResult> results = new java.util.ArrayList<>();
        for (io.xuanji.core.runtime.AgentConfirm c : confirms) {
            io.agentscope.core.message.ToolUseBlock tc = pending.askingCalls().stream()
                    .filter(x -> c.toolCallId() != null && c.toolCallId().equals(x.getId()))
                    .findFirst().orElse(null);
            if (tc == null) {
                continue;
            }
            // rememberSession 的落库由 server 侧 confirm API 负责（跨实例持久语义在平台侧），
            // 这里只负责恢复执行。
            results.add(new io.agentscope.core.event.ConfirmResult(c.approved(), tc));
        }
        if (results.isEmpty()) {
            throw new IllegalArgumentException("no valid confirm targets in: " + confirms);
        }
        Msg resume = Msg.builder()
                .role(io.agentscope.core.message.MsgRole.USER)
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
        try {
            pending.handle().agent().streamEvents(List.of(resume))
                    .doOnNext(evt -> mapEvent(evt, h, sink))
                    .blockLast();
        } catch (RuntimeException e) {
            sink.accept(io.xuanji.core.runtime.AgentStreamEvent.error(rootMessage(e)));
            throw e;
        }
    }

    /**
     * 按会话恢复（平台 confirm API 入口）：confirm 请求是独立 HTTP 请求，平台拿不到原
     * AgentHandle——按 sessionId 从挂起缓存取回。无挂起实例时抛状态错（前端提示已失效）。
     */
    @Override
    public void confirmBySession(String sessionId, List<io.xuanji.core.runtime.AgentConfirm> confirms,
                                 java.util.function.Consumer<io.xuanji.core.runtime.AgentStreamEvent> sink) {
        Suspended pending = sessionId == null ? null : suspended.remove(sessionId);
        if (pending == null) {
            throw new IllegalStateException("no suspended agent awaiting confirm: session=" + sessionId);
        }
        confirm(pending.handle(), confirms, sink);
    }

    /** stream/confirm 共用的事件映射（confirm 事件在 stream 入口单独处理）。 */
    private void mapEvent(io.agentscope.core.event.AgentEvent evt, AgentScopeHandle h,
                          java.util.function.Consumer<io.xuanji.core.runtime.AgentStreamEvent> sink) {
        try {
            if (evt instanceof io.agentscope.core.event.AgentStartEvent e) {
                sink.accept(io.xuanji.core.runtime.AgentStreamEvent.of("agent.start", Map.of(
                        "sessionId", str(e.getSessionId()), "replyId", str(e.getReplyId()),
                        "name", str(e.getName()))));
            } else if (evt instanceof io.agentscope.core.event.TextBlockDeltaEvent e) {
                sink.accept(io.xuanji.core.runtime.AgentStreamEvent.delta(e.getDelta()));
            } else if (evt instanceof io.agentscope.core.event.AgentResultEvent e) {
                sink.accept(io.xuanji.core.runtime.AgentStreamEvent.result(
                        e.getResult() == null ? null : e.getResult().getTextContent()));
            } else if (evt instanceof io.agentscope.core.event.ToolCallStartEvent e) {
                sink.accept(io.xuanji.core.runtime.AgentStreamEvent.of("tool.call", Map.of(
                        "toolCallId", str(e.getToolCallId()),
                        "tool", str(e.getToolCallName()))));
            } else if (evt instanceof io.agentscope.core.event.ToolResultEndEvent e) {
                sink.accept(io.xuanji.core.runtime.AgentStreamEvent.of("tool.result", Map.of(
                        "toolCallId", str(e.getToolCallId()),
                        "tool", str(e.getToolCallName()),
                        "state", e.getState() == null ? "" : e.getState().name())));
            } else if (evt instanceof io.agentscope.core.event.AgentEndEvent) {
                sink.accept(io.xuanji.core.runtime.AgentStreamEvent.of("agent.end", Map.of()));
            }
        } catch (RuntimeException ex) {
            log.warn("event map failed: {}", ex.toString());
        }
    }

    /**
     * 注册内置工具（AgentScope 2.0.4 自带，注解式 @Tool）：按 spec.builtinTools 白名单启用。
     * baseDir 一律 = spec.workspaceRoot()（项目目录硬边界——所有工具都不能超过项目目录）；
     * shell 用命令白名单构造器。workspaceRoot 为空（无项目上下文）时不注册任何内置工具。
     */
    private void registerBuiltinTools(io.agentscope.core.tool.Toolkit toolkit, AgentSpec spec) {
        if (spec.builtinTools().isEmpty()) {
            return;
        }
        if (spec.workspaceRoot() == null || spec.workspaceRoot().isBlank()) {
            log.warn("builtin tools requested but no workspaceRoot, skipped: {}", spec.builtinTools());
            return;
        }
        java.nio.file.Path root = java.nio.file.Path.of(spec.workspaceRoot()).normalize();
        for (String entry : spec.builtinTools()) {
            String bare = entry.endsWith(":always") ? entry.substring(0, entry.length() - 7) : entry;
            switch (bare) {
                case "fs.read" -> toolkit.registerTool(new io.agentscope.core.tool.file.ReadFileTool(root.toString()));
                case "fs.write" -> toolkit.registerTool(new io.agentscope.core.tool.file.WriteFileTool(root.toString()));
                case "shell" -> {
                    Set<String> allowed = new java.util.HashSet<>();
                    for (String cmd : shellAllowed.split(",")) {
                        if (!cmd.isBlank()) {
                            allowed.add(cmd.trim());
                        }
                    }
                    toolkit.registerTool(new io.agentscope.core.tool.coding.ShellCommandTool(
                            root.toString(), allowed, null));
                }
                default -> log.warn("unknown builtin tool, skipped: {}", entry);
            }
        }
        log.info("builtin tools registered: {} root={}", spec.builtinTools(), root);
    }

    /**
     * 权限上下文（HITL）：fs 工具 baseDir 硬限项目目录（安全）→ 白名单启用即 ALLOW；
     * shell 危险 → "shell:always"（永久授权）ALLOW；"shell" 无规则 → DEFAULT mode 默认 ASK
     * （单次授权，用户批准一次执行一次）；server 侧会话级授权（ToolApprovalStore）→ ALLOW。
     */
    /** ALLOW 规则工厂：PermissionRule.toolName 必填且须与外层 key 一致（框架 requireNonNull 校验） */
    private static io.agentscope.core.permission.PermissionRule allowRule(String tool, String source) {
        return new io.agentscope.core.permission.PermissionRule(
                tool, null, io.agentscope.core.permission.PermissionBehavior.ALLOW, source);
    }

    private io.agentscope.core.permission.PermissionContextState permissionContext(AgentSpec spec) {
        io.agentscope.core.permission.PermissionContextState.Builder b =
                io.agentscope.core.permission.PermissionContextState.builder()
                        .mode(io.agentscope.core.permission.PermissionMode.DEFAULT);
        for (String entry : spec.builtinTools()) {
            String bare = entry.endsWith(":always") ? entry.substring(0, entry.length() - 7) : entry;
            switch (bare) {
                case "fs.read" -> {
                    b.addAllowRule("view_text_file", allowRule("view_text_file", "agentConfig"));
                    b.addAllowRule("list_directory", allowRule("list_directory", "agentConfig"));
                }
                case "fs.write" -> {
                    b.addAllowRule("write_text_file", allowRule("write_text_file", "agentConfig"));
                    b.addAllowRule("insert_text_file", allowRule("insert_text_file", "agentConfig"));
                }
                case "shell" -> {
                    if (entry.endsWith(":always")) {
                        b.addAllowRule("execute_shell_command", allowRule("execute_shell_command", "agentConfig"));
                    } else {
                        ToolApprovalChecker checker = approvalChecker == null ? null : approvalChecker.getIfAvailable();
                        if (checker != null && spec.workspaceId() != null
                                && checker.sessionGranted(spec.workspaceId(), "shell")) {
                            b.addAllowRule("execute_shell_command",
                                    allowRule("execute_shell_command", "session"));
                        }
                        // 无规则 → DEFAULT mode 默认 ASK（单次授权）
                    }
                }
                default -> { }
            }
        }
        return b.build();
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
