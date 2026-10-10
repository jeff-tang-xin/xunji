package io.xuanji.core.api;

/**
 * 节点能力声明。节点在元数据里声明自己支持的能力，
 * 注册表在「定义发布校验」时据此拒绝超出能力的配置（如配了决策门但节点不支持）。
 * 能力是契约的一部分：声明了就必须兑现，引擎会按声明施策（重试/超时/决策门挂起）。
 */
public enum NodeCapability {
    /** 支持决策门：可挂起等人决策，决策后从该节点恢复 */
    SUPPORTS_DECISION_GATE,
    /** 支持重试：引擎按节点 retry 配置重试 NodeExecutionException(retryable=true) */
    SUPPORTS_RETRY,
    /** 支持超时：引擎按节点 timeout 配置置位取消令牌 */
    SUPPORTS_TIMEOUT,
    /** 幂等：重试不会产生副作用累积（引擎可放心重试） */
    IDEMPOTENT
}
