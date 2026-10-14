package io.xuanji.server.project.orchestrator;

/**
 * 小流程类型。
 *
 * <ul>
 *   <li>{@link #TASK} —— 任务型：单个 agent 执行，产出登记为交付物（分钟级，有超时）；</li>
 *   <li>{@link #DISCUSS} —— 讨论型：多方结构化群聊（第一轮并行收集独立观点、
 *       后续轮串行互见），纪要落 output_full，共识登记产物；</li>
 *   <li>{@link #DECISION} —— 决策型：主控向用户征询（request_decision），
 *       instruction=问题，output_summary=用户答复。决策与流程同时间线留档。</li>
 *   <li>{@link #CONSULT} —— 求助型：执行中 ask_peer 的问答留档（发起方+目的方+PM 锚，
 *       DONE 态即时落库，进黑板摘要供主控审计）。</li>
 * </ul>
 */
public enum StepType {
    TASK, DISCUSS, DECISION, CONSULT
}
