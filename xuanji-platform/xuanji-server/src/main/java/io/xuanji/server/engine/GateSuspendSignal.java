package io.xuanji.server.engine;

/**
 * 决策门挂起信号——NodeDispatchComponent 在决策门拦截时抛出，
 * LiteFlow 捕获进 LiteflowResponse（run 不判 FAILED）。
 *
 * <p>挂起动作（node_run=SUSPENDED + ctx.markSuspended）在抛出前完成；
 * RunService 以 ctx.suspendedGate() 判定 SUSPENDED 分支并落快照
 * （docs/detail/03 §5）。</p>
 */
public class GateSuspendSignal extends RuntimeException {

    private final String gateNodeId;

    public GateSuspendSignal(String gateNodeId) {
        super("decision gate suspended at node: " + gateNodeId);
        this.gateNodeId = gateNodeId;
    }

    public String gateNodeId() {
        return gateNodeId;
    }
}
