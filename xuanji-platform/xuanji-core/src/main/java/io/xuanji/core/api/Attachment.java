package io.xuanji.core.api;

/**
 * 输入附件——工作区输入支持的附件类型（决议 Q11）。
 *
 * <p>交互约定：附件先上传至工作区目录（路径动态生成），输入引用其工作区相对路径；
 * 图片支持 Ctrl+V 粘贴直传（前端交互），md 文档以文件上传。</p>
 *
 * @param name 文件名（含扩展名，如 截图.png / 需求说明.md）
 * @param kind 附件类别
 * @param ref  工作区相对路径（如 order-sys/inputs/2026-10-08/截图.png）
 */
public record Attachment(String name, AttachmentKind kind, String ref) {

    public Attachment {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("attachment name must not be blank");
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("attachment ref must not be blank");
        }
    }

    /** 附件类别 */
    public enum AttachmentKind {
        /** 图片（支持粘贴上传） */
        IMAGE,
        /** 文档（md 等） */
        DOCUMENT
    }
}
