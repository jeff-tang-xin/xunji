package io.xuanji.server.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.core.api.CancellationToken;
import io.xuanji.core.api.Connector;
import io.xuanji.core.api.DecisionContext;
import io.xuanji.core.api.NodeContext;
import io.xuanji.core.api.NodeOutput;
import io.xuanji.core.api.Tool;
import io.xuanji.core.api.TraceContext;
import io.xuanji.core.api.UserInputFeed;
import io.xuanji.core.api.WorkflowNode;
import io.xuanji.core.definition.NodeDef;
import io.xuanji.server.registry.ToolNodeAdapter;
import io.xuanji.server.repository.RunRepository;
import com.yomahub.liteflow.core.NodeComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 节点分发组件——LiteFlow 与组件注册表之间的唯一桥（docs/detail/03 §2）。
 *
 * <p>单例 bean，EL 里以 {@code node_dispatch.tag("{instanceId}")} 出现——
 * tag 即画布节点实例 id，执行时从 {@link RunContext} 取定义路由到对应
 * {@link WorkflowNode}。START/END 平台内置（输入透传/输出固化），不经注册表。</p>
 *
 * <p>每次执行落 wf_node_run（RUNNING → SUCCESS/FAILED），画布回显与回放的数据源。</p>
 */
@Component("node_dispatch")
public class NodeDispatchComponent extends NodeComponent {

    private static final Logger log = LoggerFactory.getLogger(NodeDispatchComponent.class);

    /** refId → 节点实例（独立 bean + 连接器聚合节点），构造时构建 */
    private final Map<String, WorkflowNode> instances;

    /** AGENT 节点执行器（AgentRuntime 适配层；ObjectProvider 防装配顺序耦合） */
    private final ObjectProvider<AgentNodeExecutor> agentExecutorProvider;

    private final RunRepository repo;

    private final ObjectMapper objectMapper;

    public NodeDispatchComponent(ObjectProvider<WorkflowNode> nodeProvider,
                                 ObjectProvider<Connector> connectorProvider,
                                 ObjectProvider<Tool> toolProvider,
                                 ObjectProvider<AgentNodeExecutor> agentExecutorProvider,
                                 RunRepository repo,
                                 ObjectMapper objectMapper) {
        Map<String, WorkflowNode> map = new java.util.concurrent.ConcurrentHashMap<>();
        nodeProvider.orderedStream().forEach(n -> map.put(n.metadata().id(), n));
        connectorProvider.orderedStream()
                .forEach(c -> c.nodes().forEach(n -> map.put(n.metadata().id(), n)));
        // 工具 bean（Tool SPI）：包装为画布节点（「工具即节点」），refId = tool.{id}
        toolProvider.orderedStream().forEach(t -> {
            ToolNodeAdapter adapter = new ToolNodeAdapter(t);
            map.put(adapter.metadata().id(), adapter);
        });
        this.instances = Map.copyOf(map);
        this.agentExecutorProvider = agentExecutorProvider;
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 发布期引用校验：注册表是否含该 refId 的节点实例（DefinitionService.publish 用） */
    public boolean hasInstance(String refId) {
        return instances.containsKey(refId);
    }

    @Override
    public void process() {
        String instanceId = getTag();
        RunContext ctx = getContextBean(RunContext.class);
        NodeDef def = ctx.definition().node(instanceId);
        Map<String, Object> input = ctx.assembleInput(instanceId);

        boolean gate = def.decisionGate().enabled();
        // 产物评审门：AGENT 节点 + REDO 策略 = 先执行产出再挂起审批；其余 gate = 前置审批
        boolean reviewGate = gate && def.kind() == io.xuanji.core.api.NodeKind.AGENT
                && def.decisionGate().onReject() == io.xuanji.core.definition.DecisionGateDef.RejectPolicy.REDO;
        // ROLLBACK 重跑：前置门一次性放行，执行后重新挂起
        boolean reSuspend = gate && ctx.consumeReSuspendAfterRun(instanceId);

        // 前置审批门（非评审门、非一次性放行）：未批准 → 执行前挂起
        if (gate && !reviewGate && !reSuspend && !ctx.isGateApproved(instanceId)) {
            String nr0 = "nr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            int seq0 = ctx.nextSeq();
            insertNodeRun(nr0, ctx.runId(), def, seq0, input);
            repo.suspendNodeRun(nr0);
            ctx.markSuspended(instanceId);
            log.info("decision gate hit (pre-approve): run={} node={}", ctx.runId(), instanceId);
            throw new GateSuspendSignal(instanceId);
        }

        String nodeRunId = "nr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        int seq = ctx.nextSeq();

        // 驳回重生成：取出反馈（上一版产物 + 修改意见）合并进输入，AGENT 据此产出新版本
        Map<String, Object> feedback = gate ? ctx.consumeGateFeedback(instanceId) : null;
        if (feedback != null) {
            input.put("_previousOutput", feedback.get("previousOutput"));
            input.put("_revisionComment", feedback.get("comment"));
            input.put("_revisionRound", feedback.get("round"));
        }

        insertNodeRun(nodeRunId, ctx.runId(), def, seq, input);
        Instant started = Instant.now();
        try {
            NodeOutput out;
            if (def.isStart() || def.isEnd()) {
                // 平台内置：START 输入透传 / END 输出固化（Q10 决议）
                out = NodeOutput.of(input);
            } else if (def.kind() == io.xuanji.core.api.NodeKind.AGENT) {
                // AGENT 节点：委托 AgentRuntime（refId = agent:{id}@{version}）
                Map<String, Object> agentOut = agentExecutorProvider.getObject().execute(def, input);
                out = NodeOutput.of(agentOut);
            } else {
                WorkflowNode node = instances.get(def.refId());
                if (node == null) {
                    throw new IllegalStateException("no instance for refId=" + def.refId()
                            + "（发布期校验应已拦截）");
                }
                NodeContext nodeCtx = new NodeContext(ctx.runId(), nodeRunId, input, def.params(),
                        ctx.vars(), DecisionContext.empty(),
                        TraceContext.of(ctx.runId(), nodeRunId),
                        new CancellationToken() {
                            @Override
                            public boolean isCancelled() {
                                return false;
                            }
                        },
                        UserInputFeed.empty());
                out = node.execute(nodeCtx);
            }
            ctx.recordOutput(instanceId, out.output());
            finishNodeRun(nodeRunId, "SUCCESS", out.output(), null, started);
            log.info("node finished: run={} node={} [{}] -> {}", ctx.runId(), instanceId, def.refId(),
                    out.output().isEmpty() ? "(no output)" : out.output().keySet());

            // 评审门：产出后挂起等待审批——
            // ① 首次产出（无反馈、未放行）→ 挂起审 v1；② 驳回重生成（有反馈）→ 挂起审新版本
            if (reviewGate && feedback == null && !ctx.isGateApproved(instanceId)) {
                ctx.markSuspended(instanceId);
                log.info("review gate suspended after produce: run={} node={}", ctx.runId(), instanceId);
                throw new GateSuspendSignal(instanceId);
            }
            if (feedback != null) {
                ctx.markSuspended(instanceId);
                log.info("review gate re-suspended after revision: run={} node={} round={}",
                        ctx.runId(), instanceId, feedback.get("round"));
                throw new GateSuspendSignal(instanceId);
            }
            // ROLLBACK 一次性放行：产出后重新挂起
            if (reSuspend) {
                ctx.markSuspended(instanceId);
                log.info("gate re-suspended after rollback rerun: run={} node={}", ctx.runId(), instanceId);
                throw new GateSuspendSignal(instanceId);
            }
        } catch (Exception e) {
            if (e instanceof GateSuspendSignal) {
                throw e;
            }
            finishNodeRun(nodeRunId, "FAILED", null, e.getMessage(), started);
            log.error("node failed: run={} node={} [{}]", ctx.runId(), instanceId, def.refId(), e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("node execution failed: " + instanceId, e);
        }
    }

    private void insertNodeRun(String nodeRunId, String runId, NodeDef def, int seq, Map<String, Object> input) {
        repo.insertNodeRun(nodeRunId, runId, def.instanceId(), def.name(), seq, toJson(input), Timestamp.from(Instant.now()));
    }

    private void finishNodeRun(String nodeRunId, String status, Map<String, Object> output, String error, Instant started) {
        repo.finishNodeRun(nodeRunId, status, toJson(output), error, Timestamp.from(Instant.now()));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"unserializable output\"}";
        }
    }
}
