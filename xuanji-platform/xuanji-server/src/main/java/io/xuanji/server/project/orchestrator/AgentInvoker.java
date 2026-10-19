package io.xuanji.server.project.orchestrator;

import io.xuanji.core.runtime.AgentHandle;
import io.xuanji.core.runtime.AgentMessage;
import io.xuanji.core.runtime.AgentRuntime;
import io.xuanji.core.runtime.AgentSpec;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 同步调用底座——TaskRunner（任务执行）与 DiscussionEngine（讨论发言）共用。
 *
 * <p>AgentRuntime.stream 是阻塞调用（blockLast，返回即对话完成），本类只做
 * 「构建 handle → 调用 → 收集最终文本」的收敛，把流式回调变成一次产出文本。
 * 超时控制不在本类：调用方（TaskRunner/DiscussionEngine）用 Future.get(timeout)
 * 在线程层控制，避免在阻塞调用里做时间管理。</p>
 *
 * <p>无状态组件：每次调用独立，多项目并发安全。</p>
 */
@Component
public class AgentInvoker {

    private final AgentRuntime runtime;

    public AgentInvoker(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * 同步调用一个 agent，返回最终文本（阻塞直至完成）。
     *
     * @param spec    agent 规格（stateless 场景 sessionId 传 null）
     * @param message 输入消息（议题包/任务卡）
     * @return 最终文本；调用失败或空产出抛 IllegalStateException（调用方转 FAILED 留档）
     */
    public String invoke(AgentSpec spec, String message) {
        return invoke(spec, message, null);
    }

    /**
     * 同步调用并透传执行过程事件（任务步骤过程可视化的数据源）。
     *
     * <p>onEvent 收到 agent.delta（文本增量）/ tool.call（工具调用）等中间事件——
     * 调用方（TaskRunner）据此做过程落库与 SSE 广播；传 null 即纯收敛调用。
     * agent.result 仍以返回值为准，onEvent 只是旁观，不影响收敛语义。</p>
     */
    public String invoke(AgentSpec spec, String message, java.util.function.Consumer<io.xuanji.core.runtime.AgentStreamEvent> onEvent) {
        AgentHandle handle = runtime.createAgent(spec);
        AtomicReference<String> finalText = new AtomicReference<>();
        try {
            runtime.stream(handle, new AgentMessage(message, null, null), evt -> {
                if ("agent.result".equals(evt.type()) && evt.text() != null) {
                    finalText.set(evt.text());
                }
                if (onEvent != null && !"agent.result".equals(evt.type())) {
                    try {
                        onEvent.accept(evt);
                    } catch (Exception ignore) {
                        // 过程旁观者异常不阻断执行
                    }
                }
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("agent 调用失败: " + e.getMessage(), e);
        }
        String text = finalText.get();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("agent 无产出: " + spec.name());
        }
        return text;
    }
}
