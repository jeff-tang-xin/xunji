package io.xuanji.core.api;

import java.time.Instant;
import java.util.List;

/**
 * 用户输入——工作区「只有输入内容才触发流程 + 随时接入」的数据载体。
 *
 * <p>工作区执行模型（主文档决议 Q9）：用户输入内容触发流程（CHAT_INPUT 触发）；
 * 流程运行中用户可随时接入追加输入，经 {@link RunInputPort} 注入 run，
 * 当前执行节点通过 {@link UserInputFeed} 协作式消费。</p>
 *
 * <p>输入支持附件（决议 Q11）：图片（支持 Ctrl+V 粘贴上传）与 md 文档等；
 * 附件先上传至工作区目录，输入引用其工作区相对路径。</p>
 *
 * @param from        输入来源（用户标识；工作区场景为工作区成员）
 * @param text        输入文本（追加指令/批注/补充材料说明）
 * @param attachments 附件列表（图片/md 文档等，先上传后引用）
 * @param injectedAt  注入时间
 */
public record UserInput(String from, String text, List<Attachment> attachments, Instant injectedAt) {

    public UserInput {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        injectedAt = injectedAt == null ? Instant.now() : injectedAt;
    }
}
