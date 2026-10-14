package io.xuanji.server.engine;

/**
 * run 终态事件（Spring ApplicationEvent）：finishRun 落库后发布。
 *
 * <p>为什么不用 RunEventBus：那是按 runId 订阅的 SSE 通道（前端看板用），
 * 服务内跨模块监听走 Spring 事件更干净——engine 不依赖 project，
 * ProjectService 监听后自行判断这条 run 是不是阶段 run（查 exec_definition_id）。</p>
 *
 * @param runId        run 标识
 * @param definitionId 工作流定义 id
 * @param status       终态：SUCCESS / FAILED
 */
public record RunFinishedEvent(String runId, long definitionId, String status) {
}
