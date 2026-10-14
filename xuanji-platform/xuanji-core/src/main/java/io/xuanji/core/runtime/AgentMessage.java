package io.xuanji.core.runtime;

import java.util.List;

/**
 * Agent 输入消息（P0 文本形态；V29 起支持对话附件——图片走多模态、文本文件走内容拼接）。
 *
 * @param text        用户输入 / 上游节点输出装配的提示文本
 * @param sessionId   会话 id（多轮记忆；null = 单轮无记忆）
 * @param attachments 对话附件（图片/文本文件；null = 无附件）
 */
public record AgentMessage(String text, String sessionId, List<Attachment> attachments) {

    public AgentMessage {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("AgentMessage.text must not be blank");
        }
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** 兼容 P0 调用形态（无附件） */
    public AgentMessage(String text, String sessionId) {
        this(text, sessionId, List.of());
    }

    public static AgentMessage of(String text) {
        return new AgentMessage(text, null);
    }

    /**
     * 对话附件：图片带 mediaType + dataBase64（多模态 ImageBlock）；
     * 文本文件带 content（原样拼进提示文本）。二进制非图片文件不支持。
     */
    public record Attachment(String name, String mediaType, String dataBase64, String content) {
        public boolean isImage() {
            return mediaType != null && mediaType.startsWith("image/") && dataBase64 != null && !dataBase64.isBlank();
        }

        public boolean isTextFile() {
            return content != null && !content.isBlank();
        }
    }
}
