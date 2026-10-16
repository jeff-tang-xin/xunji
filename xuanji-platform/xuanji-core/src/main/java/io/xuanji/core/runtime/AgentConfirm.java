package io.xuanji.core.runtime;

/**
 * 工具调用确认输入（平台形态，Runtime 实现方内部转框架对象）。
 *
 * @param toolCallId      被确认的工具调用 id（agent.confirm 事件里带回）
 * @param approved        true=批准本次执行；false=拒绝（工具收到 DENIED 结果）
 * @param rememberSession true=同时记入会话级授权（本项目会话内该工具后续调用不再询问）
 */
public record AgentConfirm(String toolCallId, boolean approved, boolean rememberSession) {
}
