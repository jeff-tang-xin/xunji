package io.xuanji.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * workspace 表数据访问层（B 档分层整改）。列集合与 INSERT 动态拼接从 {@code WorkspaceService}
 * 抽离；绑定校验的发布状态判断、归属校验、root_path 计算等业务仍留 Service。
 *
 * <p>SQL 与原实现逐字一致（含 authorization_json 未 ::text 的现状），仅搬移位置不改行为。</p>
 */
@Repository
public class WorkspaceRepository {

    private static final List<String> WS_COLS = List.of(
            "id", "name", "root_path", "binding_type", "bound_asset_id",
            "bound_asset_version", "authorization_json", "org_id", "created_by");

    private static final String SELECT_COLS =
            "id, name, root_path, binding_type, bound_asset_id, bound_asset_version, "
                    + "authorization_json, org_id, created_by, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public WorkspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findByScope(String whereClause, Object[] args) {
        return jdbc.queryForList(
                "SELECT " + SELECT_COLS + " FROM workspace WHERE " + whereClause + " ORDER BY created_at DESC", args);
    }

    public List<Map<String, Object>> findById(String id) {
        return jdbc.queryForList("SELECT " + SELECT_COLS + " FROM workspace WHERE id = ?", id);
    }

    public void insert(String id, String name, String rootPath, String type, long assetId, Integer pinned,
                       String authJson, Long orgId, String createdBy) {
        jdbc.update("INSERT INTO workspace (" + String.join(", ", WS_COLS) + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                id, name, rootPath, type, assetId, pinned, authJson, orgId, createdBy);
    }

    public void update(String id, String name, String rootPath, String type, long assetId, Integer pinned) {
        jdbc.update("UPDATE workspace SET name = ?, root_path = ?, binding_type = ?, "
                        + "bound_asset_id = ?, bound_asset_version = ?, updated_at = now() WHERE id = ?",
                name, rootPath, type, assetId, pinned, id);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM workspace WHERE id = ?", id);
    }

    public List<Map<String, Object>> findWorkflowBinding(long assetId) {
        return jdbc.queryForList("SELECT status, current_version FROM wf_definition WHERE id = ?", assetId);
    }

    public List<Map<String, Object>> findAgentBinding(long assetId) {
        return jdbc.queryForList("SELECT status, version FROM agent_config WHERE id = ?", assetId);
    }
}