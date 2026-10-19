package io.xuanji.server.project.orchestrator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * bug 仓储（V36 需求交付树）：测试执行步骤上报、修复流转。
 *
 * <p>状态机：OPEN（上报）→ FIXING → FIXED → CLOSED / REJECTED（误报）。
 * test_case_code 记发现自哪个用例（可空）；requirement_code 归属需求（可空）。</p>
 *
 * <p>手写 JdbcTemplate（持久层分层治理约定，不上 JPA）。</p>
 */
@Repository
public class BugRepository {

    private static final RowMapper<Bug> MAPPER = (rs, i) -> new Bug(
            rs.getLong("id"),
            rs.getString("workspace_id"),
            rs.getString("code"),
            rs.getString("title"),
            rs.getString("severity"),
            rs.getString("status"),
            rs.getString("requirement_code"),
            rs.getString("test_case_code"),
            rs.getObject("step_id") == null ? null : rs.getLong("step_id"),
            rs.getString("content_md"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public BugRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Bug(Long id, String workspaceId, String code, String title,
                      String severity, String status, String requirementCode,
                      String testCaseCode, Long stepId, String contentMd, String createdBy,
                      java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt) {
        /** 未了结（需求 DONE 门禁用）：OPEN/FIXING 都算未收敛。 */
        public boolean open() {
            return "OPEN".equals(status) || "FIXING".equals(status);
        }
    }

    public List<Bug> findByWorkspace(String workspaceId) {
        return jdbc.query("""
                SELECT id, workspace_id, code, title, severity, status, requirement_code,
                       test_case_code, step_id, content_md, created_by, created_at, updated_at
                FROM project_bug WHERE workspace_id = ? ORDER BY code
                """, MAPPER, workspaceId);
    }

    public List<Bug> findByRequirement(String workspaceId, String requirementCode) {
        return jdbc.query("""
                SELECT id, workspace_id, code, title, severity, status, requirement_code,
                       test_case_code, step_id, content_md, created_by, created_at, updated_at
                FROM project_bug WHERE workspace_id = ? AND requirement_code = ? ORDER BY code
                """, MAPPER, workspaceId, requirementCode);
    }

    public Optional<Bug> findByCode(String workspaceId, String code) {
        List<Bug> rows = jdbc.query("""
                SELECT id, workspace_id, code, title, severity, status, requirement_code,
                       test_case_code, step_id, content_md, created_by, created_at, updated_at
                FROM project_bug WHERE workspace_id = ? AND code = ?
                """, MAPPER, workspaceId, code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 上报 bug（幂等：同 code 覆盖标题/严重度/关联/正文，状态不动）。 */
    public void upsert(Bug b) {
        jdbc.update("""
                INSERT INTO project_bug (workspace_id, code, title, severity, status, requirement_code, test_case_code, step_id, content_md, created_by)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, code) DO UPDATE SET
                    title = EXCLUDED.title, severity = EXCLUDED.severity,
                    requirement_code = EXCLUDED.requirement_code, test_case_code = EXCLUDED.test_case_code,
                    step_id = EXCLUDED.step_id, content_md = EXCLUDED.content_md, updated_at = now()
                """,
                b.workspaceId(), b.code(), b.title(), b.severity(), b.requirementCode(),
                b.testCaseCode(), b.stepId(), b.contentMd(), b.createdBy());
    }

    /** 状态流转（settle_bugs）。 */
    public void updateStatus(String workspaceId, String code, String status) {
        jdbc.update("UPDATE project_bug SET status = ?, updated_at = now() WHERE workspace_id = ? AND code = ?",
                status, workspaceId, code);
    }
}
