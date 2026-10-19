package io.xuanji.server.project.orchestrator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 测试用例仓储（V36 需求交付树）：测试设计步骤落档、执行结果回写。
 *
 * <p>状态机：ACTIVE（落档）→ PASSED / FAILED / BLOCKED（settle_test_cases 回写）。
 * requirement_code 挂需求项（可空=全局用例）；step_id 记产出步骤（追溯）。</p>
 *
 * <p>手写 JdbcTemplate（持久层分层治理约定，不上 JPA）。</p>
 */
@Repository
public class TestCaseRepository {

    private static final RowMapper<TestCase> MAPPER = (rs, i) -> new TestCase(
            rs.getLong("id"),
            rs.getString("workspace_id"),
            rs.getString("code"),
            rs.getString("title"),
            rs.getString("status"),
            rs.getString("requirement_code"),
            rs.getObject("step_id") == null ? null : rs.getLong("step_id"),
            rs.getString("content_md"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public TestCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TestCase(Long id, String workspaceId, String code, String title,
                           String status, String requirementCode, Long stepId,
                           String contentMd, String createdBy,
                           java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt) {
    }

    public List<TestCase> findByWorkspace(String workspaceId) {
        return jdbc.query("""
                SELECT id, workspace_id, code, title, status, requirement_code, step_id,
                       content_md, created_by, created_at, updated_at
                FROM project_test_case WHERE workspace_id = ? ORDER BY code
                """, MAPPER, workspaceId);
    }

    /** 按需求聚合用（需求交付树）：requirement_code 为空的用例不进任何需求链。 */
    public List<TestCase> findByRequirement(String workspaceId, String requirementCode) {
        return jdbc.query("""
                SELECT id, workspace_id, code, title, status, requirement_code, step_id,
                       content_md, created_by, created_at, updated_at
                FROM project_test_case WHERE workspace_id = ? AND requirement_code = ? ORDER BY code
                """, MAPPER, workspaceId, requirementCode);
    }

    public Optional<TestCase> findByCode(String workspaceId, String code) {
        List<TestCase> rows = jdbc.query("""
                SELECT id, workspace_id, code, title, status, requirement_code, step_id,
                       content_md, created_by, created_at, updated_at
                FROM project_test_case WHERE workspace_id = ? AND code = ?
                """, MAPPER, workspaceId, code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 落用例（幂等：同 code 覆盖标题/内容/关联，状态不动——重跑测试设计不丢执行结果）。 */
    public void upsert(TestCase tc) {
        jdbc.update("""
                INSERT INTO project_test_case (workspace_id, code, title, status, requirement_code, step_id, content_md, created_by)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                ON CONFLICT (workspace_id, code) DO UPDATE SET
                    title = EXCLUDED.title, requirement_code = EXCLUDED.requirement_code,
                    step_id = EXCLUDED.step_id, content_md = EXCLUDED.content_md, updated_at = now()
                """,
                tc.workspaceId(), tc.code(), tc.title(), tc.requirementCode(), tc.stepId(), tc.contentMd(), tc.createdBy());
    }

    /** 执行结果回写（settle_test_cases）。 */
    public void updateStatus(String workspaceId, String code, String status) {
        jdbc.update("UPDATE project_test_case SET status = ?, updated_at = now() WHERE workspace_id = ? AND code = ?",
                status, workspaceId, code);
    }
}
