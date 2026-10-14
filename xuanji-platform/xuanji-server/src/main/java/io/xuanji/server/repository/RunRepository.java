package io.xuanji.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * run 执行域数据访问层（B 档分层整改）：wf_run / wf_node_run / wf_decision /
 * wf_definition_version 的 SQL 从 {@code RunService} 与 {@code NodeDispatchComponent} 抽离。
 *
 * <p>边界：本层只做 SQL 与结果读取；决策门语义、超时策略分流、LiteFlow 执行、快照序列化、
 * 事件发布等业务仍在 Service/Component。JSONB 列查询统一 {@code ::text}（规避 PGobject 序列化），
 * 写入用 {@code ?::jsonb} 占位符。{@link #claimTimeout} 保留条件 UPDATE 的原子认领语义。</p>
 */
@Repository
public class RunRepository {

    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------- wf_definition / wf_definition_version ---------- */

    public Integer findCurrentVersion(long definitionId) {
        return jdbc.queryForObject(
                "SELECT current_version FROM wf_definition WHERE id = ?", Integer.class, definitionId);
    }

    /** 读已发布版本画布（canvas_json::text 规避 PGobject）。 */
    public Map<String, Object> findVersionCanvas(long definitionId, int versionNo) {
        return jdbc.queryForMap(
                "SELECT v.version, v.canvas_json::text AS canvas_json FROM wf_definition_version v "
                        + "WHERE v.definition_id = ? AND v.version = ?", definitionId, versionNo);
    }

    /* ---------- wf_run ---------- */

    public void insertRun(String runId, long definitionId, Integer version, String triggerType,
                          String inputJson, Timestamp startedAt) {
        jdbc.update("INSERT INTO wf_run (id, definition_id, version, trigger_type, status, input_json, started_at) "
                        + "VALUES (?, ?, ?, ?, 'RUNNING', ?::jsonb, ?)",
                runId, definitionId, version, triggerType, inputJson, startedAt);
    }

    /** 恢复所需：definition_id / version / status / context_snapshot（::text）。无记录抛 EmptyResultDataAccessException。 */
    public Map<String, Object> findRunForResume(String runId) {
        return jdbc.queryForMap("SELECT definition_id, version, status, context_snapshot::text AS snap "
                + "FROM wf_run WHERE id = ?", runId);
    }

    public void updateRunning(String runId) {
        jdbc.update("UPDATE wf_run SET status = 'RUNNING' WHERE id = ?", runId);
    }

    public void updateSuspended(String runId, String snapshotJson, Timestamp deadline, String gateNodeId) {
        jdbc.update("UPDATE wf_run SET status = 'SUSPENDED', context_snapshot = ?::jsonb, "
                        + "gate_deadline = ?, gate_node_id = ? WHERE id = ?",
                snapshotJson, deadline, gateNodeId, runId);
    }

    public void updateSuccess(String runId, String outputJson, Timestamp finishedAt) {
        jdbc.update("UPDATE wf_run SET status = 'SUCCESS', output_json = ?::jsonb, finished_at = ? WHERE id = ?",
                outputJson, finishedAt, runId);
    }

    public void updateFailed(String runId, String error, Timestamp finishedAt) {
        jdbc.update("UPDATE wf_run SET status = 'FAILED', error = ?, finished_at = ? WHERE id = ?",
                error, finishedAt, runId);
    }

    public void updateAppId(String runId, String appId) {
        jdbc.update("UPDATE wf_run SET app_id = ? WHERE id = ?", appId, runId);
    }

    /** 挂起且已过 gate_deadline 的 run（供超时扫描）。 */
    public List<Map<String, Object>> findDueTimeouts() {
        return jdbc.queryForList(
                "SELECT id, definition_id, version, gate_node_id FROM wf_run "
                        + "WHERE status = 'SUSPENDED' AND gate_deadline IS NOT NULL AND gate_deadline < now()");
    }

    /** 原子认领：仅当仍 SUSPENDED 且未清 deadline 时置空，返回影响行数（0=已被人工/其他轮认领）。 */
    public int claimTimeout(String runId) {
        return jdbc.update("UPDATE wf_run SET gate_deadline = NULL "
                        + "WHERE id = ? AND status = 'SUSPENDED' "
                        + "AND gate_deadline IS NOT NULL AND gate_deadline < now()",
                runId);
    }

    /** run 详情主记录（JSONB 列 ::text）。 */
    public Map<String, Object> findRunDetail(String runId) {
        return jdbc.queryForMap(
                "SELECT id, definition_id, version, trigger_type, status, app_id, gate_node_id, "
                        + "input_json::text AS input_json, output_json::text AS output_json, "
                        + "context_snapshot::text AS context_snapshot, error, "
                        + "started_at, finished_at FROM wf_run WHERE id = ?", runId);
    }

    public List<Map<String, Object>> listRuns(Long definitionId, String status, int limit) {
        return listRuns(definitionId, status, limit, "TRUE", new Object[0]);
    }

    /** run 列表（数据权限过滤）：join wf_definition 按 org_id/created_by 过滤（wf_run 无归属列）。 */
    public List<Map<String, Object>> listRuns(Long definitionId, String status, int limit,
                                              String whereClause, Object[] scopeArgs) {
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (definitionId != null) {
            conds.add("r.definition_id = ?");
            args.add(definitionId);
        }
        if (status != null && !status.isBlank()) {
            conds.add("r.status = ?");
            args.add(status);
        }
        conds.add(whereClause);
        args.addAll(List.of(scopeArgs));
        StringBuilder sql = new StringBuilder(
                "SELECT r.id, r.definition_id, r.version, r.trigger_type, r.status, r.app_id, r.started_at, r.finished_at, r.error "
                        + "FROM wf_run r JOIN wf_definition d ON d.id = r.definition_id");
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
        sql.append(" ORDER BY r.started_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 查 run 的 definition_id（供归属校验）；无记录抛 EmptyResultDataAccessException。 */
    public Long findRunDefinitionId(String runId) {
        return jdbc.queryForObject("SELECT definition_id FROM wf_run WHERE id = ?", Long.class, runId);
    }

    /** 查 definition 的 org_id/created_by（供数据权限可见性校验）。 */
    public Map<String, Object> findDefinitionScope(long definitionId) {
        return jdbc.queryForMap("SELECT org_id, created_by FROM wf_definition WHERE id = ?", definitionId);
    }

    /* ---------- wf_node_run ---------- */

    public List<Map<String, Object>> findNodeRuns(String runId) {
        return jdbc.queryForList(
                "SELECT id, node_id, node_name, seq, status, input_json::text AS input_json, "
                        + "output_json::text AS output_json, error, started_at, finished_at "
                        + "FROM wf_node_run WHERE run_id = ? ORDER BY seq", runId);
    }

    public void insertNodeRun(String nodeRunId, String runId, String nodeId, String nodeName, int seq,
                              String inputJson, Timestamp startedAt) {
        jdbc.update("INSERT INTO wf_node_run (id, run_id, node_id, node_name, seq, status, input_json, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'RUNNING', ?::jsonb, ?)",
                nodeRunId, runId, nodeId, nodeName, seq, inputJson, startedAt);
    }

    public void finishNodeRun(String nodeRunId, String status, String outputJson, String error, Timestamp finishedAt) {
        jdbc.update("UPDATE wf_node_run SET status = ?, output_json = ?::jsonb, error = ?, finished_at = ? WHERE id = ?",
                status, outputJson, error, finishedAt, nodeRunId);
    }

    public void suspendNodeRun(String nodeRunId) {
        jdbc.update("UPDATE wf_node_run SET status = 'SUSPENDED' WHERE id = ?", nodeRunId);
    }

    /**
     * SKIP（主控跳过）：把该节点挂起中的 node_run 标记为 SKIPPED——节点不执行、无输出，
     * 下游 mapping 引用其输出按「字段缺省」语义处理（RunContext.applyTo 跳过 null）。
     *
     * @return 影响行数（0 = 该节点没有挂起中的 node_run，调用方应视为非法跳过）
     */
    public int skipNodeRun(String runId, String nodeId) {
        return jdbc.update("UPDATE wf_node_run SET status = 'SKIPPED', finished_at = ? "
                + "WHERE run_id = ? AND node_id = ? AND status = 'SUSPENDED'",
                Timestamp.from(java.time.Instant.now()), runId, nodeId);
    }

    /* ---------- wf_decision ---------- */

    public void insertDecision(String runId, String nodeId, String decision, String decidedBy, String remark) {
        jdbc.update("INSERT INTO wf_decision (run_id, node_id, decision_type, decision, decided_by, remark) "
                        + "VALUES (?, ?, 'NODE_GATE', ?, ?, ?)",
                runId, nodeId, decision, decidedBy, remark);
    }

    /**
     * 清空一条 run 的全部落库数据（决策 → 节点 → run 本体，子表先删）。
     * 供阶段重跑回收脏执行：孤儿 RUNNING / FAILED 的 run 在重跑时整体废弃，
     * 留着只会让 wf_run 表里堆「永远执行中」的僵尸。
     */
    public void purgeRunData(String runId) {
        jdbc.update("DELETE FROM wf_decision WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM wf_node_run WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM wf_run WHERE id = ?", runId);
    }

    /**
     * 启动自愈：进程中断（kill/崩溃/部署重启）时正在执行的 run 与节点被直接杀死，
     * 状态永远停在 RUNNING——界面显示「执行中」却不会再推进。启动时统一标 FAILED
     * （error 注明原因），用户即可在阶段执行面板一键重跑恢复。
     *
     * @return int[]{run 行数, node_run 行数}
     */
    public int[] failOrphanRuns() {
        int nodes = jdbc.update("UPDATE wf_node_run SET status = 'FAILED', error = '服务重启中断' WHERE status = 'RUNNING'");
        int runs = jdbc.update("UPDATE wf_run SET status = 'FAILED', error = '服务重启中断', finished_at = now() WHERE status = 'RUNNING'");
        return new int[]{runs, nodes};
    }

    public List<Map<String, Object>> findDecisionHistory(String runId) {
        return jdbc.queryForList(
                "SELECT id, run_id, node_id, decision_type, decision, decided_by, remark, "
                        + "payload_json::text AS payload_json, decided_at "
                        + "FROM wf_decision WHERE run_id = ? ORDER BY decided_at", runId);
    }

    public List<Map<String, Object>> findPendingDecisions(String whereClause, Object[] args) {
        return jdbc.queryForList(
                "SELECT r.id, r.definition_id, r.version, r.trigger_type, r.started_at, "
                        + "d.name AS definition_name, "
                        + "r.context_snapshot->>'gateNodeId' AS gate_node_id, "
                        + "r.context_snapshot->>'gateNodeName' AS gate_node_name "
                        + "FROM wf_run r JOIN wf_definition d ON d.id = r.definition_id "
                        + "WHERE r.status = 'SUSPENDED' AND " + whereClause
                        + " ORDER BY r.started_at DESC", args);
    }
}