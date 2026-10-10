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
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (definitionId != null) {
            conds.add("definition_id = ?");
            args.add(definitionId);
        }
        if (status != null && !status.isBlank()) {
            conds.add("status = ?");
            args.add(status);
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, definition_id, version, trigger_type, status, app_id, started_at, finished_at, error FROM wf_run");
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
        sql.append(" ORDER BY started_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
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

    /* ---------- wf_decision ---------- */

    public void insertDecision(String runId, String nodeId, String decision, String decidedBy, String remark) {
        jdbc.update("INSERT INTO wf_decision (run_id, node_id, decision_type, decision, decided_by, remark) "
                        + "VALUES (?, ?, 'NODE_GATE', ?, ?, ?)",
                runId, nodeId, decision, decidedBy, remark);
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