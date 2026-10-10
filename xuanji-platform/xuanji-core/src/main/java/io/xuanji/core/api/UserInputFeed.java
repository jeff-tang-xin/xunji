package io.xuanji.core.api;

import java.util.List;

/**
 * 运行中用户输入的订阅视图——节点执行期间协作式拉取（与 {@link CancellationToken} 同构）。
 *
 * <p>语义：用户在流程运行中随时接入的输入，经引擎写入 run 的输入流；
 * 长任务节点（尤其 Agent 节点）在执行循环中周期性 {@link #drain()}，
 * 把新输入采纳进当前阶段工作（如 Agent 将追加指令并入本轮生成）。
 * 未被当前节点消费的输入保留在 run 上下文，供后续节点与决策工作区使用。</p>
 */
public interface UserInputFeed {

    /**
     * 取走当前累积的全部新输入（取走即从待消费队列移除，但历史仍留存 run 上下文）。
     *
     * @return 自上次取走以来的新输入，按注入时间升序；无新输入返回空列表
     */
    List<UserInput> drain();

    /** 自 run 启动以来累计的用户输入条数（含已消费） */
    int totalReceived();

    /** 空订阅（无用户输入场景的默认值，如 API/SCHEDULE 触发的 run） */
    static UserInputFeed empty() {
        return new UserInputFeed() {
            @Override
            public List<UserInput> drain() {
                return List.of();
            }

            @Override
            public int totalReceived() {
                return 0;
            }
        };
    }
}
