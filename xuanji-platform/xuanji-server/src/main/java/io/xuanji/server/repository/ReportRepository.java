package io.xuanji.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * report / report_snapshot 表数据访问层（B 档分层整改）。列集合与 INSERT 动态拼接从
 * {@code ReportService} 抽离；cron 校验、调度注册/注销、归属校验等业务仍留 Service。
 *
 * <p>SQL 与原实现逐字一致，仅搬移位置不改行为。</p>
 */
@Repository
public class ReportRepository {

    private static final List<String> REPORT_COLS = List.of(
            "id", "name", "definition_id", "pinned_version", "cron_expr", "enabled",
            "org_id", "created_by");

    private static final String SELECT_COLS =
            "id, name, definition_id, pinned_version, cron_expr, enabled, "
                    + "org_id, created_by, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findByScope(String whereClause, Object[] args) {
        return jdbc.queryForList(
                "SELECT " + SELECT_COLS + " FROM report WHERE " + whereClause + " ORDER BY created_at DESC", args);
    }

    public List<Map<String, Object>> findById(String id) {
        return jdbc.queryForList("SELECT " + SELECT_COLS + " FROM report WHERE id = ?", id);
    }

    public void insert(String id, String name, long definitionId, Integer pinned, String cronExpr,
                       boolean enabled, Long orgId, String createdBy) {
        jdbc.update("INSERT INTO report (" + String.join(", ", REPORT_COLS) + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, definitionId, pinned, cronExpr, enabled, orgId, createdBy);
    }

    public void deleteSnapshots(String reportId) {
        jdbc.update("DELETE FROM report_snapshot WHERE report_id = ?", reportId);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM report WHERE id = ?", id);
    }

    public void updateName(String id, String name, Timestamp updatedAt) {
        jdbc.update("UPDATE report SET name = ?, updated_at = ? WHERE id = ?", name, updatedAt, id);
    }

    public void updateCron(String id, String cronExpr, Timestamp updatedAt) {
        jdbc.update("UPDATE report SET cron_expr = ?, updated_at = ? WHERE id = ?", cronExpr, updatedAt, id);
    }

    public void updateEnabled(String id, boolean enabled, Timestamp updatedAt) {
        jdbc.update("UPDATE report SET enabled = ?, updated_at = ? WHERE id = ?", enabled, updatedAt, id);
    }

    public Map<String, Object> findLatestForSchedule(String id) {
        return jdbc.queryForMap("SELECT id, definition_id, pinned_version, cron_expr FROM report WHERE id = ?", id);
    }

    public List<Map<String, Object>> findSnapshots(String reportId) {
        return jdbc.queryForList(
                "SELECT id, run_id, snapshot_json::text AS snapshot_json, created_at "
                        + "FROM report_snapshot WHERE report_id = ? ORDER BY created_at DESC LIMIT 50", reportId);
    }

    public List<Map<String, Object>> findWorkflowForValidate(long definitionId) {
        return jdbc.queryForList("SELECT status, current_version FROM wf_definition WHERE id = ?", definitionId);
    }
}