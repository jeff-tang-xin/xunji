package io.xuanji.core.runtime;

/**
 * Agent 输入消息（P0 文本形态；多模态随工作区附件能力扩展）。
 *
 * @param text      用户输入 / 上游节点输出装配的提示文本
 * @param sessionId 会话 id（多轮记忆；null = 单轮无记忆）
 */
public record AgentMessage(String text, String sessionId) {

    public AgentMessage {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("AgentMessage.text must not be blank");
        }
    }

    public static AgentMessage of(String text) {
        return new AgentMessage(text, null);
    }
}
