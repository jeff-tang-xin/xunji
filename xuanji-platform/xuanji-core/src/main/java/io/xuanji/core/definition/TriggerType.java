package io.xuanji.core.definition;

/**
 * 触发类型（决议 Q9：工作区场景只有用户输入内容才触发流程）。
 *
 * <ul>
 *   <li>{@code CHAT_INPUT} —— 工作区用户输入触发：用户在工作区输入内容（文本/图片/md 附件）
 *       才创建 run，输入内容作为开始节点的流程输入；运行中再输入经 RunInputPort 注入</li>
 *   <li>{@code MANUAL} —— 控制台手动触发</li>
 *   <li>{@code API} —— 应用层 API 触发（AppKey 鉴权）</li>
 *   <li>{@code SCHEDULE} —— 定时触发（cron/错峰/并发策略）</li>
 *   <li>{@code WEBHOOK} —— 外部平台事件触发（连接器解析 → match 命中）</li>
 * </ul>
 */
public enum TriggerType {
    CHAT_INPUT, MANUAL, API, SCHEDULE, WEBHOOK
}
