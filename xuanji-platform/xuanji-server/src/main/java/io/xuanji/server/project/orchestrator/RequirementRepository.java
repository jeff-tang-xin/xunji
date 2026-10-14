package io.xuanji.server.project.orchestrator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 需求项仓储（V34 需求先行）：讨论式开发的主干锚定物。
 *
 * <p>状态机：PROPOSED（PM 落项，可改）→ CONFIRMED（用户确认，锁定——改动走新 proposed 项）
 * → IN_PROGRESS（有关联步骤执行中）→ DONE（全部关联步骤完成，自动回写）/ DROPPED（废弃）。
 * depends_on 为需求 code 数组（DAG，建依赖时防环）。</p>
 *
 * <p>手写 JdbcTemplate（持久层分层治理约定，不上 JPA）；JSONB 列传 String 须显式 CAST。</p>
 */
@Repository
public class RequirementRepository {

    private static final RowMapper<Requirement> MAPPER = (rs, i) -> new Requirement(
            rs.getLong("id"),
            rs.getString("workspace_id"),
            rs.getString("code"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("acceptance_json"),
            rs.getString("status"),
            rs.getString("depends_on"),
            rs.getString("source"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public RequirementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Requirement(Long id, String workspaceId, String code, String title,
                              String description, String acceptanceJson, String status,
                              String dependsOn, String source, String createdBy,
                              java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt) {
        public boolean active() {
            return "IN_PROGRESS".equals(status) || "CONFIRMED".equals(status);
        }
    }

    public List<Requirement> findByWorkspace(String workspaceId) {
        return jdbc.query("""
                SELECT id, workspace_id, code, title, description, acceptance_json::text,
                       status, depends_on::text, source, created_by, created_at, updated_at
                FROM project_requirement WHERE workspace_id = ? ORDER BY code
                """, MAPPER, workspaceId);
    }

    public Optional<Requirement> findByCode(String workspaceId, String code) {
        List<Requirement> rows = jdbc.query("""
                SELECT id, workspace_id, code, title, description, acceptance_json::text,
                       status, depends_on::text, source, created_by, created_at, updated_at
                FROM project_requirement WHERE workspace_id = ? AND code = ?
                """, MAPPER, workspaceId, code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** PM 落项（PROPOSED）。code 项目内唯一，重复返回空由调用方报错。 */
    public void insert(Requirement r) {
        jdbc.update("""
                INSERT INTO project_requirement (workspace_id, code, title, description,
                    acceptance_json, status, depends_on, source, created_by)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?)
                """,
                r.workspaceId(), r.code(), r.title(), r.description(),
                r.acceptanceJson(), r.status(), r.dependsOn(), r.source(), r.createdBy());
    }

    /** 更新描述/验收/依赖——仅 PROPOSED 态可改（CONFIRMED 后改动走新 proposed 项，append-only）。 */
    public void updateDraft(Requirement r) {
        jdbc.update("""
                UPDATE project_requirement SET title = ?, description = ?,
                    acceptance_json = CAST(? AS jsonb), depends_on = CAST(? AS jsonb), updated_at = now()
                WHERE workspace_id = ? AND code = ? AND status = 'PROPOSED'
                """,
                r.title(), r.description(), r.acceptanceJson(), r.dependsOn(),
                r.workspaceId(), r.code());
    }

    /** 状态迁移（confirm/drop/in-progress/done 共用；updated_at 刷新）。 */
    public void updateStatus(String workspaceId, String code, String status) {
        jdbc.update("""
                UPDATE project_requirement SET status = ?, updated_at = now()
                WHERE workspace_id = ? AND code = ?
                """, status, workspaceId, code);
    }

    /** 依赖计数：某需求被多少 active 需求依赖（drop 前检查用）。 */
    public int countDependents(String workspaceId, String code) {
        List<Integer> rows = jdbc.query("""
                SELECT count(*) FROM project_requirement
                WHERE workspace_id = ? AND status <> 'DROPPED' AND depends_on::text LIKE ?
                """, (rs, i) -> rs.getInt(1), workspaceId, "%" + code + "%");
        return rows.isEmpty() ? 0 : rows.get(0);
    }
}
