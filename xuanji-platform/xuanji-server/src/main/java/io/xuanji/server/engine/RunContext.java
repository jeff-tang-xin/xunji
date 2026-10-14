package io.xuanji.server.engine;

import io.xuanji.core.api.WorkflowVars;
import io.xuanji.core.definition.EdgeDef;
import io.xuanji.core.definition.MappingRule;
import io.xuanji.core.definition.NodeDef;
import io.xuanji.core.definition.WorkflowDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LiteFlow 执行上下文（一次 run 一个实例，组件间共享）。
 *
 * <p>承载：定义快照、run 输入、变量区、各节点输出。mapping 装配在此求值——
 * 表达式约定（docs/detail/03 §1）：</p>
 * <ul>
 *   <li>from：{@code $.{nodeId}.output[.field]} 或 {@code $.vars.{key}}</li>
 *   <li>to：{@code $.input.{field}} 或 {@code $.vars.{key}}</li>
 * </ul>
 * <p>P0 用轻量点分求值（不引 JSONPath 库）；嵌套取值等复杂表达式随 P1 引入。</p>
 */
public class RunContext {

    private final String runId;
    private final WorkflowDefinition definition;
    private final Map<String, Object> runInput;
    private final WorkflowVars vars;
    private final Map<String, Map<String, Object>> nodeOutputs = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    /** 已放行的决策门节点（恢复执行时注入，跳过再次挂起） */
    private final java.util.Set<String> gateApproved = new java.util.HashSet<>();

    /** 决策门挂起位置（null = 未挂起；挂起时 = gate 节点 instanceId） */
    private String suspendedGate;

    /** 一次性「执行后重新挂起」标记（ROLLBACK 重跑：放行执行一次，产出后再次挂起） */
    private final java.util.Set<String> reSuspendAfterRun = new java.util.HashSet<>();

    /** 驳回反馈：gate 节点重生成时注入的「上一版产物 + 修改意见」（按节点 instanceId 存） */
    private final Map<String, Map<String, Object>> gateFeedback = new LinkedHashMap<>();

    /** 驳回轮次：gate 节点被重生成的次数（v1→v2…，用于版本展示与审计） */
    private final Map<String, Integer> gateRound = new LinkedHashMap<>();

    public RunContext(String runId, WorkflowDefinition definition, Map<String, Object> runInput, WorkflowVars vars) {
        this.runId = runId;
        this.definition = definition;
        this.runInput = runInput == null ? Map.of() : runInput;
        this.vars = vars;
    }

    public String runId() {
        return runId;
    }

    public WorkflowDefinition definition() {
        return definition;
    }

    public WorkflowVars vars() {
        return vars;
    }

    public int nextSeq() {
        return seq.incrementAndGet();
    }

    /** 按入边 mapping 装配节点输入；START 节点 = run 启动输入（工作区场景即用户输入内容） */
    public Map<String, Object> assembleInput(String instanceId) {
        NodeDef def = definition.node(instanceId);
        if (def.isStart()) {
            return runInput;
        }
        var incoming = definition.incomingEdges(instanceId);
        if (incoming.isEmpty()) {
            return runInput;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        for (EdgeDef edge : incoming) {
            for (MappingRule rule : edge.mapping()) {
                Object value = evalFrom(rule.from());
                applyTo(rule.to(), value, input);
            }
        }
        return input;
    }

    /** 记录节点输出（供下游 mapping 引用与 run 输出固化） */
    public void recordOutput(String instanceId, Map<String, Object> output) {
        nodeOutputs.put(instanceId, output == null ? Map.of() : output);
    }

    /** 清除节点输出（ROLLBACK 重跑时用：旧产物移除，重跑当作首次产出） */
    public void clearNodeOutput(String instanceId) {
        nodeOutputs.remove(instanceId);
    }

    /** END 节点输出即 run 输出 */
    public Map<String, Object> runOutput(String endInstanceId) {
        return nodeOutputs.getOrDefault(endInstanceId, Map.of());
    }

    // ---------- 决策门：挂起快照与恢复（docs/detail/03 §5） ----------

    /** 决策门是否已放行（恢复段执行时跳过再次挂起） */
    public boolean isGateApproved(String instanceId) {
        return gateApproved.contains(instanceId);
    }

    /** 放行决策门（恢复执行前注入） */
    public void approveGate(String instanceId) {
        gateApproved.add(instanceId);
    }

    /** 标记挂起位置（NodeDispatchComponent 决策门拦截时调用） */
    public void markSuspended(String gateNodeId) {
        this.suspendedGate = gateNodeId;
    }

    /** 挂起位置（null = 未挂起） */
    public String suspendedGate() {
        return suspendedGate;
    }

    /**
     * 记录驳回反馈（REJECT 时调用）：上一版产物 + 修改意见 + 轮次，
     * gate 节点重生成时经 {@link #consumeGateFeedback} 取出注入。
     */
    public void pushGateFeedback(String gateNodeId, Map<String, Object> previousOutput,
                                 String comment, int round) {
        Map<String, Object> fb = new LinkedHashMap<>();
        fb.put("previousOutput", previousOutput);
        fb.put("comment", comment);
        fb.put("round", round);
        gateFeedback.put(gateNodeId, fb);
    }

    /** 取出并清除驳回反馈（gate 节点重生成装配输入时调用；无反馈返回 null） */
    public Map<String, Object> consumeGateFeedback(String gateNodeId) {
        return gateFeedback.remove(gateNodeId);
    }

    /** gate 节点当前驳回轮次（无记录返回 0） */
    public int gateRound(String gateNodeId) {
        return gateRound.getOrDefault(gateNodeId, 0);
    }

    /** 递增 gate 节点驳回轮次，返回新轮次（首次驳回 → 1） */
    public int bumpGateRound(String gateNodeId) {
        int next = gateRound.getOrDefault(gateNodeId, 0) + 1;
        gateRound.put(gateNodeId, next);
        return next;
    }

    /** 标记 gate「放行执行一次后重新挂起」（ROLLBACK 重跑用） */
    public void markReSuspendAfterRun(String gateNodeId) {
        reSuspendAfterRun.add(gateNodeId);
    }

    /** 取出并清除「执行后重新挂起」标记（gate 节点执行完判断） */
    public boolean consumeReSuspendAfterRun(String gateNodeId) {
        return reSuspendAfterRun.remove(gateNodeId);
    }

    /**
     * 挂起快照：vars + 已完成节点输出 + run 输入 + seq 基值 + gate 位置。
     * 序列化后落 wf_run.context_snapshot（JSONB），恢复时经 {@link #restore} 重建。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("gateNodeId", suspendedGate);
        snap.put("vars", vars.snapshot());
        snap.put("nodeOutputs", nodeOutputs);
        snap.put("runInput", runInput);
        snap.put("seq", seq.get());
        snap.put("gateRound", gateRound);
        return snap;
    }

    /** 从快照重建上下文（恢复段执行：gate 节点及其后继继续，已完成输出可被 mapping 引用） */
    @SuppressWarnings("unchecked")
    public static RunContext restore(String runId, WorkflowDefinition definition, Map<String, Object> snap) {
        MapWorkflowVars vars = new MapWorkflowVars();
        Object varsRaw = snap.get("vars");
        if (varsRaw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> vars.put(String.valueOf(k), v));
        }
        Object inputRaw = snap.get("runInput");
        Map<String, Object> runInput = inputRaw instanceof Map ? (Map<String, Object>) inputRaw : Map.of();
        RunContext ctx = new RunContext(runId, definition, runInput, vars);
        Object outputsRaw = snap.get("nodeOutputs");
        if (outputsRaw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (v instanceof Map<?, ?> vm) {
                    ctx.nodeOutputs.put(String.valueOf(k), (Map<String, Object>) vm);
                }
            });
        }
        Object seqRaw = snap.get("seq");
        if (seqRaw instanceof Number n) {
            ctx.seq.set(n.intValue());
        }
        Object roundRaw = snap.get("gateRound");
        if (roundRaw instanceof Map<?, ?> rm) {
            rm.forEach((k, v) -> {
                if (v instanceof Number rn) {
                    ctx.gateRound.put(String.valueOf(k), rn.intValue());
                }
            });
        }
        return ctx;
    }

    /** from 表达式求值：$.{nodeId}.output[.field[.subfield...]] | $.vars.{key} */
    private Object evalFrom(String expr) {
        String[] parts = expr.replaceFirst("^\\$\\.", "").split("\\.");
        if (parts.length >= 2 && "vars".equals(parts[0])) {
            return vars.get(parts[1]);
        }
        Map<String, Object> out = nodeOutputs.get(parts[0]);
        if (out == null) {
            return null;
        }
        if (parts.length >= 2 && "output".equals(parts[1])) {
            if (parts.length == 2) {
                return Map.copyOf(out);                     // $.n.output 整体
            }
            Object cur = out.get(parts[2]);                 // $.n.output.field
            for (int i = 3; i < parts.length && cur instanceof Map<?, ?> m; i++) {
                cur = m.get(parts[i]);                      // 深层取值 $.n.output.field.sub
            }
            return cur;
        }
        return out;                                         // $.n（无 output 后缀）
    }

    /** to 表达式回写：$.input[.field] | $.vars.{key}；$.input 整体映射 = 上游输出合并进输入 */
    @SuppressWarnings("unchecked")
    private void applyTo(String expr, Object value, Map<String, Object> input) {
        // mapping 引用不存在的路径时求值为 null：不能写进 input——NodeOutput.of 内部 Map.copyOf
        // 拒绝 null value，抛出的 NPE 连 message 都是 null，把整条 run 打成 FAILED 且日志查不到线索
        // （实测 END 节点引用 $.start.output.tasks.end 就踩中）。跳过即可，下游按「字段缺省」语义处理。
        if (value == null) {
            return;
        }
        String[] parts = expr.replaceFirst("^\\$\\.", "").split("\\.");
        if (parts.length >= 2 && "vars".equals(parts[0])) {
            vars.put(parts[1], value);
        } else if ("input".equals(parts[0])) {
            if (parts.length >= 2) {
                input.put(parts[1], value);
            } else if (value instanceof Map) {
                input.putAll((Map<String, Object>) value);
            }
        }
    }
}
