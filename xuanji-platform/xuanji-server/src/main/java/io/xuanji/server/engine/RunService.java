package io.xuanji.server.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import io.xuanji.core.api.WorkflowVars;
import io.xuanji.core.engine.CanvasJsonParser;
import io.xuanji.core.engine.LiteFlowRuleCompiler;
import io.xuanji.core.definition.NodeDef;
import io.xuanji.core.definition.WorkflowDefinition;
import io.xuanji.server.auth.DataScopeFilter;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.repository.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * run 生命周期（P0 同步执行形态 + 决策门分段执行）。
 *
 * <p>执行链路：读已发布版本 canvas_json → 解析定义 → 编译 EL →
 * {@code execute2RespWithEL} 动态执行（无需预注册 chain）→ run/节点落库。
 * START 节点输入 = run 启动输入；END 节点输出 = run 输出（Q10 决议）。</p>
 *
 * <p>决策门分段执行（docs/detail/03 §5）：节点决策门 enabled → 挂起
 * （context_snapshot 落库 + run=SUSPENDED）→ 决策到达（APPROVE）→
 * 从快照重建上下文、以 gate 节点为起点续跑下一段；REJECT → run 保持
 * SUSPENDED（批注入 wf_decision，主循环=批注→再决策）。</p>
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    @Autowired
    private RunRepository repo;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FlowExecutor flowExecutor;

    @Autowired
    private RunEventBus eventBus;

    private final LiteFlowRuleCompiler compiler = new LiteFlowRuleCompiler("node_dispatch");

    /** 触发 run：绑定已发布版本，同步执行到终态 */
    public Map<String, Object> createRun(long definitionId, Map<String, Object> input, String triggerType) {
        return createRun(definitionId, repo.findCurrentVersion(definitionId), input, triggerType);
    }

    /** 触发 run（版本钉住）：App 按绑定版本执行（引用钉住语义，主文档 4.9） */
    public Map<String, Object> createRun(long definitionId, Integer versionNo, Map<String, Object> input, String triggerType) {
        WorkflowDefinition definition = loadDefinition(definitionId, versionNo);
        String runId = "r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        repo.insertRun(runId, definitionId, versionNo, triggerType, toJson(input), Timestamp.from(Instant.now()));
        return executeRun(runId, definitionId, versionNo, definition, input);
    }

    /**
     * 异步触发（工作区交互场景）：立即返回 runId（RUNNING），执行在虚拟线程跑——
     * AGENT 节点（LLM）耗时 30s+，同步等待会让前端长时间停在「已开始执行」无反馈；
     * 前端经 GET /api/runs/{id}/events（RunEventBus SSE）订阅 suspended/finished 实时渲染。
     */
    public Map<String, Object> createRunAsync(long definitionId, Integer versionNo, Map<String, Object> input, String triggerType) {
        WorkflowDefinition definition = loadDefinition(definitionId, versionNo);
        String runId = "r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        repo.insertRun(runId, definitionId, versionNo, triggerType, toJson(input), Timestamp.from(Instant.now()));
        Thread.startVirtualThread(() -> {
            try {
                executeRun(runId, definitionId, versionNo, definition, input);
            } catch (Exception e) {
                log.error("async run failed: {} ({})", runId, e.toString());
                repo.updateFailed(runId, truncate(e.getMessage()), Timestamp.from(Instant.now()));
                eventBus.publish(runId, "run.finished", Map.of("status", "FAILED"));
                eventBus.complete(runId);
            }
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("status", "RUNNING");
        return result;
    }

    /** 执行段（同步/异步共用）：编译 EL → 执行 → 挂起落库或终态落库 */
    private Map<String, Object> executeRun(String runId, long definitionId, Integer versionNo,
                                           WorkflowDefinition definition, Map<String, Object> input) {
        RunContext ctx = new RunContext(runId, definition, input, new MapWorkflowVars());
        String el = compiler.compile(definition);
        log.info("run started: {} definition={} v{} el=[{}]", runId, definitionId, versionNo, el);

        // 传入已有 ctx 实例（Object... 重载）；Class... 重载语义是反射新建空上下文，不适用
        LiteflowResponse resp = flowExecutor.execute2RespWithEL(el, null, runId, ctx);

        // 决策门挂起：快照落库（vars+节点输出+位置），run 等待决策（docs/detail/03 §5）
        if (ctx.suspendedGate() != null) {
            Map<String, Object> snap = ctx.snapshot();
            String gateNodeId = ctx.suspendedGate();
            snap.put("gateNodeName", definition.node(gateNodeId).name());
            persistSuspend(runId, snap, gateNodeId, definition);
            log.info("run suspended at gate: {} node={}", runId, gateNodeId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", runId);
            result.put("status", "SUSPENDED");
            result.put("gateNodeId", gateNodeId);
            return result;
        }
        return finishRun(runId, definition, ctx, resp);
    }

    /**
     * 决策恢复（docs/detail/03 §5）：写 wf_decision 审计 →
     * APPROVE：从快照重建上下文、以 gate 节点为起点续跑下一段；
     * REJECT：run 保持 SUSPENDED（批注已入审计，等下一轮决策）。
     */
    public Map<String, Object> resume(String runId, String nodeId, String decision, String comment, String decidedBy) {
        Map<String, Object> run;
        try {
            run = repo.findRunForResume(runId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId);
        }
        if (!"SUSPENDED".equals(run.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "run is not SUSPENDED: " + runId + " (status=" + run.get("status") + ")");
        }
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be APPROVE or REJECT");
        }

        // 先加载定义读 gate 的拒绝策略（REDO/TERMINATE/ROLLBACK），REJECT 按此分流
        WorkflowDefinition preDef = loadDefinition(((Number) run.get("definition_id")).longValue(),
                ((Number) run.get("version")).intValue());
        NodeDef gateNode;
        try {
            gateNode = preDef.node(nodeId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gate 节点不存在: " + nodeId);
        }
        var rejectPolicy = gateNode.decisionGate().enabled()
                ? gateNode.decisionGate().onReject()
                : io.xuanji.core.definition.DecisionGateDef.RejectPolicy.REDO;

        repo.insertDecision(runId, nodeId, decision, decidedBy, truncate(comment));

        if ("REJECT".equals(decision)) {
            return switch (rejectPolicy) {
                case TERMINATE -> terminateRun(runId, comment, decidedBy);
                case ROLLBACK -> rollbackRun(runId, nodeId, comment, decidedBy, run, gateNode);
                case REDO -> rejectAndRegenerate(runId, nodeId, comment, decidedBy, run, gateNode);
            };
        }

        Map<String, Object> snap;
        try {
            snap = objectMapper.readValue((String) run.get("snap"), new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "corrupt context snapshot: " + runId);
        }
        WorkflowDefinition definition = preDef;
        RunContext ctx = RunContext.restore(runId, definition, snap);
        ctx.approveGate(nodeId);
        repo.updateRunning(runId);
        eventBus.publish(runId, "run.resumed", Map.of("gateNodeId", nodeId, "decision", "APPROVE"));

        // 评审门（AGENT+REDO）：产物已通过，不重跑 gate，从其后继续跑、复用已通过输出；
        // 前置门：gate 节点尚未执行，从 gate 本身起编译
        boolean reviewGate = gateNode.kind() == io.xuanji.core.api.NodeKind.AGENT
                && gateNode.decisionGate().onReject() == io.xuanji.core.definition.DecisionGateDef.RejectPolicy.REDO;
        String el = reviewGate ? compiler.compileAfter(definition, nodeId)
                : compiler.compileFrom(definition, nodeId);
        log.info("run resumed: {} from node={} reviewGate={} el=[{}]", runId, nodeId, reviewGate, el);
        LiteflowResponse resp = flowExecutor.execute2RespWithEL(el, null, runId, ctx);
        return finishRun(runId, definition, ctx, resp);
    }

    /**
     * 驳回重生成（决策门主循环，docs/detail/03 §5）：批注非空校验 → 从快照恢复 →
     * 记录反馈（上一版产物 + 修改意见）→ 只重跑 gate 节点产出新版本 → 再次 SUSPENDED。
     *
     * <p>gate 节点须为 AGENT（评审产物由 Agent 生成）；非 AGENT gate 无法重生成，
     * 退化为保持挂起（批注已入审计）。</p>
     */
    private Map<String, Object> rejectAndRegenerate(String runId, String nodeId, String comment,
                                                    String decidedBy, Map<String, Object> run,
                                                    NodeDef gateNode) {
        if (comment == null || comment.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回必须填写批注意见");
        }
        WorkflowDefinition definition = loadDefinition(((Number) run.get("definition_id")).longValue(),
                ((Number) run.get("version")).intValue());
        if (gateNode.kind() != io.xuanji.core.api.NodeKind.AGENT) {
            // 非 AGENT gate：无产物可重生成，保持挂起（诚实降级）
            log.info("non-AGENT gate rejected, stay suspended: run={} node={}", runId, nodeId);
            return Map.of("runId", runId, "status", "SUSPENDED", "gateNodeId", nodeId, "regenerated", false);
        }

        Map<String, Object> snap;
        try {
            snap = objectMapper.readValue((String) run.get("snap"), new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "corrupt context snapshot: " + runId);
        }
        RunContext ctx = RunContext.restore(runId, definition, snap);

        // 上一版产物 = gate 节点最近一次输出（首次驳回时来自挂起前的输出快照）
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) snap.getOrDefault("nodeOutputs", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> previousOutput = outputs.get(nodeId) instanceof Map<?, ?> po
                ? (Map<String, Object>) po : Map.of();
        int round = ctx.bumpGateRound(nodeId);
        ctx.pushGateFeedback(nodeId, previousOutput, comment, round);

        repo.updateRunning(runId);
        log.info("reject regenerate: run={} node={} round={} by={}", runId, nodeId, round, decidedBy);

        // 只重跑 gate 节点（compileFrom 从该节点截取；反馈注入后该节点执行完会再次挂起）
        String el = compiler.compileFrom(definition, nodeId);
        flowExecutor.execute2RespWithEL(el, null, runId, ctx);

        // 落新快照（含新版本输出 + 更新后的轮次），run 回到 SUSPENDED
        Map<String, Object> newSnap = ctx.snapshot();
        newSnap.put("gateNodeName", gateNode.name());
        persistSuspend(runId, newSnap, nodeId, definition);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("status", "SUSPENDED");
        result.put("gateNodeId", nodeId);
        result.put("round", round);
        result.put("regenerated", true);
        return result;
    }


    /**
     * 终止（onReject=TERMINATE）：驳回即把 run 按失败结束，记录终止原因，不再可恢复。
     */
    private Map<String, Object> terminateRun(String runId, String comment, String decidedBy) {
        String reason = "评审驳回，流程终止" + (comment == null || comment.isBlank() ? "" : "：" + comment);
        repo.updateFailed(runId, truncate(reason), Timestamp.from(Instant.now()));
        log.info("run terminated by reject: {} by={}", runId, decidedBy);
        eventBus.publish(runId, "run.finished", Map.of("status", "FAILED", "terminated", true));
        eventBus.complete(runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("status", "FAILED");
        result.put("terminated", true);
        return result;
    }

    /**
     * 回退重跑（onReject=ROLLBACK）：P0 无独立回退目标配置，语义=清空 gate 反馈后
     * 从 gate 节点【重新执行】（相当于让 Agent 不带历史包袱重做一次），产出后再次挂起。
     * 与 REDO 的区别：不注入上一版产物，要求全新产出。
     */
    private Map<String, Object> rollbackRun(String runId, String nodeId, String comment,
                                            String decidedBy, Map<String, Object> run, NodeDef gateNode) {
        WorkflowDefinition definition = loadDefinition(((Number) run.get("definition_id")).longValue(),
                ((Number) run.get("version")).intValue());
        Map<String, Object> snap;
        try {
            snap = objectMapper.readValue((String) run.get("snap"), new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "corrupt context snapshot: " + runId);
        }
        RunContext ctx = RunContext.restore(runId, definition, snap);
        // 移除该 gate 的旧产物，重跑时当作首次产出（不注入 previousOutput）
        ctx.clearNodeOutput(nodeId);
        // 标记：前置门放行执行一次，产出后重新挂起（不真正 approve）
        ctx.markReSuspendAfterRun(nodeId);
        int round = ctx.bumpGateRound(nodeId);
        repo.updateRunning(runId);
        log.info("rollback rerun: run={} node={} round={} by={}", runId, nodeId, round, decidedBy);

        String el = compiler.compileFrom(definition, nodeId);
        flowExecutor.execute2RespWithEL(el, null, runId, ctx);

        Map<String, Object> newSnap = ctx.snapshot();
        newSnap.put("gateNodeName", gateNode.name());
        persistSuspend(runId, newSnap, nodeId, definition);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("status", "SUSPENDED");
        result.put("gateNodeId", nodeId);
        result.put("round", round);
        result.put("rolledBack", true);
        return result;
    }

    /** App 开放端触发 run 后回写归属（appkey → app → run 审计链路，wf_run.app_id）。 */
    public void attachApp(String runId, String appId) {
        repo.updateAppId(runId, appId);
    }

    /**
     * 挂起落库（三处挂起共用）：status=SUSPENDED + 快照 + 超时截止时间（gate_deadline）
     * + gate 节点 id。gate 的 timeout 为空/非法则不设 deadline（永不自动超时）。
     */
    private void persistSuspend(String runId, Map<String, Object> snap, String gateNodeId,
                                WorkflowDefinition def) {
        Instant deadline = computeDeadline(def.node(gateNodeId).decisionGate().timeout());
        repo.updateSuspended(runId, toJson(snap), deadline == null ? null : Timestamp.from(deadline), gateNodeId);
        eventBus.publish(runId, "run.suspended", Map.of(
                "gateNodeId", gateNodeId,
                "gateNodeName", String.valueOf(snap.getOrDefault("gateNodeName", ""))));
    }

    /** 决策超时字符串（"30s"/"15m"/"24h"/"7d"）→ 截止时间 Instant；null/空/非法 = 不超时 */
    static Instant computeDeadline(String timeout) {
        if (timeout == null || timeout.isBlank()) {
            return null;
        }
        String s = timeout.trim().toLowerCase();
        char unit = s.charAt(s.length() - 1);
        long n;
        try {
            n = Long.parseLong(s.substring(0, s.length() - 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        long seconds = switch (unit) {
            case 's' -> n;
            case 'm' -> n * 60;
            case 'h' -> n * 3600;
            case 'd' -> n * 86400;
            default -> -1;
        };
        return seconds > 0 ? Instant.now().plusSeconds(seconds) : null;
    }

    /**
     * 决策超时扫描（GateTimeoutScheduler 周期调用）：找出挂起且已过 gate_deadline 的 run，
     * 原子认领（清 deadline 防重复处置/竞态）后按该 gate 的 onTimeout 策略自动处置——
     * APPROVE/REJECT 复用 resume（decidedBy=system-timeout，审计可区分人工），ESCALATE 告警保持挂起
     * （升级通知待连接器实装）。返回本轮自动处置数。
     */
    public int handleGateTimeouts() {
        List<Map<String, Object>> due = repo.findDueTimeouts();
        int handled = 0;
        for (Map<String, Object> r : due) {
            String runId = (String) r.get("id");
            String gateNodeId = (String) r.get("gate_node_id");
            if (gateNodeId == null || gateNodeId.isBlank()) {
                continue;
            }
            int claimed = repo.claimTimeout(runId);
            if (claimed == 0) {
                continue;   // 已被人工决策或其他轮次认领
            }
            try {
                WorkflowDefinition def = loadDefinition(((Number) r.get("definition_id")).longValue(),
                        ((Number) r.get("version")).intValue());
                var policy = def.node(gateNodeId).decisionGate().onTimeout();
                switch (policy) {
                    case APPROVE -> {
                        resume(runId, gateNodeId, "APPROVE", "决策超时自动通过", "system-timeout");
                        log.info("gate timeout auto-APPROVED: run={} node={}", runId, gateNodeId);
                        handled++;
                    }
                    case REJECT -> {
                        resume(runId, gateNodeId, "REJECT", "决策超时自动驳回", "system-timeout");
                        log.info("gate timeout auto-REJECTED: run={} node={}", runId, gateNodeId);
                        handled++;
                    }
                    case ESCALATE -> log.warn(
                            "gate timeout ESCALATED（等待上级/人工，通知待连接器）: run={} node={}", runId, gateNodeId);
                }
            } catch (Exception e) {
                log.warn("gate timeout handle failed: run={} node={} ({})", runId, gateNodeId, e.toString());
            }
        }
        if (handled > 0) {
            log.info("gate timeout scan: auto-handled {} run(s)", handled);
        }
        return handled;
    }

    public List<Map<String, Object>> pendingDecisions(LoginUser user) {
        var c = DataScopeFilter.of(user, "d.org_id", "d.created_by");
        return repo.findPendingDecisions(c.sql(), c.args().toArray());
    }

    /** 决策历史（审计回放） */
    public List<Map<String, Object>> decisionHistory(String runId) {
        return repo.findDecisionHistory(runId);
    }

    /** 读已发布版本定义（createRun/resume 共用） */
    private WorkflowDefinition loadDefinition(long definitionId, int versionNo) {
        return CanvasJsonParser.parse((String) repo.findVersionCanvas(definitionId, versionNo).get("canvas_json"));
    }

    /** 终态落库 + 返回结果（createRun/resume 共用尾段） */
    private Map<String, Object> finishRun(String runId, WorkflowDefinition definition, RunContext ctx, LiteflowResponse resp) {
        String endNodeId = definition.endNodes().get(0).instanceId();
        Map<String, Object> output = ctx.runOutput(endNodeId);
        String error = null;
        if (resp.isSuccess()) {
            repo.updateSuccess(runId, toJson(output), Timestamp.from(Instant.now()));
            log.info("run success: {} output={}", runId, output.keySet());
        } else {
            error = resp.getCause() != null ? resp.getCause().getMessage() : resp.getMessage();
            repo.updateFailed(runId, truncate(error), Timestamp.from(Instant.now()));
            log.error("run failed: {} error={}", runId, error);
        }

        eventBus.publish(runId, "run.finished", Map.of("status", resp.isSuccess() ? "SUCCESS" : "FAILED"));
        eventBus.complete(runId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("status", resp.isSuccess() ? "SUCCESS" : "FAILED");
        result.put("output", output);
        if (error != null) {
            result.put("error", truncate(error));
        }
        return result;
    }

    /** run 详情：主记录 + 节点执行序列（画布回显数据源） */
    public Map<String, Object> getRun(String runId) {
        Map<String, Object> run = repo.findRunDetail(runId);
        run.put("nodes", repo.findNodeRuns(runId));
        return run;
    }

    /** run 列表（运行监控数据源）：按 started_at 倒序（id 为 varchar 不可排序依赖） */
    public List<Map<String, Object>> listRuns(Long definitionId, String status, int limit) {
        return repo.listRuns(definitionId, status, Math.min(Math.max(limit, 1), 200));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
