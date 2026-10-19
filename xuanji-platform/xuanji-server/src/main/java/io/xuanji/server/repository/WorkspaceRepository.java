package io.xuanji.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
                    + "authorization_json, org_id, created_by, created_at, updated_at, status, "
                    + "template_code, current_stage, project_mode";

    private final JdbcTemplate jdbc;

    public WorkspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findByScope(String whereClause, Object[] args) {
        return jdbc.queryForList(
                "SELECT " + SELECT_COLS + " FROM workspace WHERE " + whereClause
                        + " AND binding_type != 'PROJECT' ORDER BY created_at DESC", args);
    }

    /** 项目列表：仅 PROJECT 类型（workspace 即项目）。 */
    public List<Map<String, Object>> findProjectsByScope(String whereClause, Object[] args) {
        return jdbc.queryForList(
                "SELECT " + SELECT_COLS + " FROM workspace WHERE " + whereClause
                        + " AND binding_type = 'PROJECT' ORDER BY created_at DESC", args);
    }

    public List<Map<String, Object>> findById(String id) {
        return jdbc.queryForList("SELECT " + SELECT_COLS + " FROM workspace WHERE id = ?", id);
    }

    /**
     * 查项目来源模板 code（节点对话回查阶段通用知识用）。
     * 独立方法而非扩 SELECT_COLS：避免影响 workspace 既有返回结构与前端消费方。
     */
    public String findTemplateCode(String id) {
        List<String> rows = jdbc.queryForList(
                "SELECT template_code FROM workspace WHERE id = ?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(String id, String name, String rootPath, String type, long assetId, Integer pinned,
                       String authJson, Long orgId, String createdBy) {
        jdbc.update("INSERT INTO workspace (" + String.join(", ", WS_COLS) + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                id, name, rootPath, type, assetId, pinned, authJson, orgId, createdBy);
    }

    /**
     * 项目容器创建：绑定字段置空（不绑定单一资产），status=ACTIVE；
     * template_code 记录来源模板（活引用），current_stage 记录所处大阶段
     * （新项目 INITIATIVE / 老项目维护直接 OPERATE）。
     */
    public void insertProject(String id, String name, String rootPath, Long orgId, String createdBy,
                              String templateCode, String currentStage, String projectMode) {
        jdbc.update("INSERT INTO workspace (id, name, root_path, binding_type, bound_asset_id, "
                        + "bound_asset_version, authorization_json, org_id, created_by, status, template_code, "
                        + "current_stage, project_mode) "
                        + "VALUES (?, ?, ?, 'PROJECT', NULL, NULL, NULL, ?, ?, 'ACTIVE', ?, "
                        + "COALESCE(?, 'REQUIREMENT'), COALESCE(?, 'NEW'))",
                id, name, rootPath, orgId, createdBy, templateCode, currentStage, projectMode);
    }

    /** 推进项目大阶段（单向，调用方负责校验合法性）。 */
    public void updateCurrentStage(String id, String stage) {
        jdbc.update("UPDATE workspace SET current_stage = ?, updated_at = now() WHERE id = ?", stage, id);
    }

    public void update(String id, String name, String rootPath, String type, long assetId, Integer pinned) {
        jdbc.update("UPDATE workspace SET name = ?, root_path = ?, binding_type = ?, "
                        + "bound_asset_id = ?, bound_asset_version = ?, updated_at = now() WHERE id = ?",
                name, rootPath, type, assetId, pinned, id);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM workspace WHERE id = ?", id);
    }

    /** 项目改名：只改显示名，root_path 保持不变（目录已存在，改名不动目录避免路径错乱）。 */
    public void rename(String id, String name) {
        jdbc.update("UPDATE workspace SET name = ?, updated_at = now() WHERE id = ?", name, id);
    }

    /**
     * 项目级联删除：项目域全部关联数据 + workspace 主表（目录文件由调用方负责删除）。
     * 引擎侧 wf_run/wf_node_run/wf_decision 不删——运行历史是审计数据，且与 workspace 无外键。
     */
    @Transactional
    public void deleteProjectCascade(String id) {
        jdbc.update("DELETE FROM project_stage_step WHERE workspace_id = ?", id);
        jdbc.update("DELETE FROM project_stage_anchor WHERE workspace_id = ?", id);
        jdbc.update("DELETE FROM project_requirement WHERE workspace_id = ?", id);
        jdbc.update("DELETE FROM project_artifact WHERE workspace_id = ?", id);
        jdbc.update("DELETE FROM project_stage_capability WHERE workspace_id = ?", id);
        jdbc.update("DELETE FROM project_stage_summary WHERE workspace_id = ?", id);
        jdbc.update("DELETE FROM project_stage_plan WHERE workspace_id = ?", id);
        jdbc.update("DELETE FROM workspace WHERE id = ?", id);
    }

    public List<Map<String, Object>> findWorkflowBinding(long assetId) {
        return jdbc.queryForList("SELECT status, current_version FROM wf_definition WHERE id = ?", assetId);
    }

    public List<Map<String, Object>> findAgentBinding(long assetId) {
        return jdbc.queryForList("SELECT status, version FROM agent_config WHERE id = ?", assetId);
    }
}