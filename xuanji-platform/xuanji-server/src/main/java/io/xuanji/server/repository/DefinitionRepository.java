package io.xuanji.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * wf_definition / wf_definition_version 数据访问层（B 档分层整改）。
 *
 * <p>职责边界：本层只做 SQL 与结果读取，<b>不含业务规则</b>——画布解析、图校验、
 * 编译、归属判断仍在 {@code DefinitionService}。持久层沿用 JdbcTemplate（项目选型，
 * 见 backend-architecture：JSONB 密集 + 规格表驱动通用 CRUD 场景下 ORM 收益低）。</p>
 */
@Repository
public class DefinitionRepository {

    private final JdbcTemplate jdbc;

    public DefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入草稿，RETURNING id。 */
    public long insertDraft(String name, String description, String canvasJson, Long orgId, String createdBy) {
        Long id = jdbc.queryForObject(
                "INSERT INTO wf_definition (name, description, canvas_json, status, org_id, created_by) "
                        + "VALUES (?, ?, ?::jsonb, 'DRAFT', ?, ?) RETURNING id",
                Long.class, name, description, canvasJson, orgId, createdBy);
        return id;
    }

    public void updateDraft(long id, String name, String description, String canvasJson, Timestamp updatedAt) {
        jdbc.update("UPDATE wf_definition SET name = ?, description = ?, canvas_json = ?::jsonb, "
                        + "updated_at = ? WHERE id = ?",
                name, description, canvasJson, updatedAt, id);
    }

    public String findStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM wf_definition WHERE id = ?", String.class, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM wf_definition WHERE id = ?", id);
    }

    /** 存在则返回含 created_by/org_id 的行，不存在返回空列表（供归属/可见性校验）。 */
    public List<Map<String, Object>> findCreatedBys(long id) {
        return jdbc.queryForList("SELECT created_by, org_id FROM wf_definition WHERE id = ?", id);
    }

    /** 发布所需：name + canvas_json（::text 规避 PGobject）+ current_version。 */
    public Map<String, Object> findForPublish(long definitionId) {
        return jdbc.queryForMap(
                "SELECT name, canvas_json::text AS canvas_json, current_version FROM wf_definition WHERE id = ?",
                definitionId);
    }

    public void insertVersion(long definitionId, int version, String canvasJson, String ruleContent) {
        jdbc.update("INSERT INTO wf_definition_version (definition_id, version, canvas_json, rule_content, status, published_by) "
                        + "VALUES (?, ?, ?::jsonb, ?, 'PUBLISHED', 'admin')",
                definitionId, version, canvasJson, ruleContent);
    }

    public void markPublished(long definitionId, int version, Timestamp updatedAt) {
        jdbc.update("UPDATE wf_definition SET current_version = ?, status = 'PUBLISHED', updated_at = ? WHERE id = ?",
                version, updatedAt, definitionId);
    }

    public Map<String, Object> findDetail(long definitionId) {
        return jdbc.queryForMap(
                "SELECT id, name, description, canvas_json::text AS canvas_json, current_version, status, created_at, updated_at "
                        + "FROM wf_definition WHERE id = ?",
                definitionId);
    }

    public List<Map<String, Object>> findVersions(long definitionId) {
        return jdbc.queryForList(
                "SELECT version, status, published_at FROM wf_definition_version "
                        + "WHERE definition_id = ? ORDER BY version", definitionId);
    }

    /** 校验某定义某版本已发布（WORKFLOW 子工作流节点引用校验用）。 */
    public Integer countPublishedVersion(long definitionId, int version) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM wf_definition_version "
                        + "WHERE definition_id = ? AND version = ? AND status = 'PUBLISHED'",
                Integer.class, definitionId, version);
    }

    /** 数据权限过滤后的列表（whereClause/args 由 Service 经 DataScopeFilter 生成）。 */
    public List<Map<String, Object>> findByScope(String whereClause, Object[] args) {
        return jdbc.queryForList(
                "SELECT id, name, description, current_version, status, org_id, created_by, updated_at "
                        + "FROM wf_definition WHERE " + whereClause + " ORDER BY id",
                args);
    }
}