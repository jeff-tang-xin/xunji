package io.xuanji.core.api;

/**
 * run 输入端口——「用户随时接入」的引擎入口（引擎实现，server 的 SSE 网关/REST 调用）。
 *
 * <p>两条通路：</p>
 * <ul>
 *   <li><b>触发</b>：工作区中用户输入内容 → 创建 run（触发类型 CHAT_INPUT，
 *       输入内容作为开始节点的流程输入）——只有输入才触发流程</li>
 *   <li><b>接入</b>：run 运行中用户再输入 → {@link #inject} 写入运行中 run 的输入流，
 *       当前执行节点协作式消费；若 run 处于 SUSPENDED（决策门），输入进入
 *       决策工作区上下文，作为批注/补充材料参与决策</li>
 * </ul>
 */
public interface RunInputPort {

    /**
     * 向运行中/挂起中的 run 注入用户输入。
     *
     * @param runId 目标 run
     * @param input 用户输入
     * @throws IllegalArgumentException run 不存在或已终态（FAILED/CANCELLED/COMPLETED 不可再接入）
     */
    void inject(String runId, UserInput input);
}
