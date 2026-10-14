package io.xuanji.server.project.orchestrator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 小流程台账仓库（黑板持久层）——只做 CRUD，编排业务在 StageOrchestrator。
 *
 * <p>台账按 (workspace_id, stage, iteration) 隔离，seq 为阶段内顺序。
 * 全部参数化查询；行映射收敛到 {@link #MAPPER}。</p>
 */
@Repository
public class StageStepRepository {

    private final JdbcTemplate jdbc;

    public StageStepRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<StageStep> MAPPER = (rs, i) -> new StageStep(
            rs.getLong("id"),
            rs.getString("workspace_id"),
            rs.getString("stage"),
            rs.getInt("iteration"),
            rs.getInt("seq"),
            rs.getString("name"),
            StepType.valueOf(rs.getString("step_type")),
            rs.getString("agent"),
            StageStep.parseParticipants(rs.getString("participants")),
            StepStatus.valueOf(rs.getString("status")),
            rs.getString("instruction"),
            rs.getString("output_summary"),
            rs.getString("output_full"),
            (Long) rs.getObject("artifact_id"),
            rs.getString("skip_reason"),
            rs.getString("error"),
            toLocal(rs.getTimestamp("created_at")),
            toLocal(rs.getTimestamp("started_at")),
            toLocal(rs.getTimestamp("finished_at")),
            rs.getString("verified_by"),
            rs.getString("acceptance_json"),
            rs.getString("deviation_json"));

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public List<StageStep> findByStage(String workspaceId, String stage, int iteration) {
        return jdbc.query("""
                SELECT * FROM project_stage_step
                WHERE workspace_id = ? AND stage = ? AND iteration = ?
                ORDER BY seq, id
                """, MAPPER, workspaceId, stage, iteration);
    }

    public Optional<StageStep> findById(long id) {
        return jdbc.query("SELECT * FROM project_stage_step WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 本项目是否存在任何台账记录——新旧内核双轨判据。 */
    public boolean hasAnyStep(String workspaceId) {
        Boolean has = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM project_stage_step WHERE workspace_id = ?)",
                Boolean.class, workspaceId);
        return Boolean.TRUE.equals(has);
    }

    /** 是否存在活跃（执行中/等待用户）步骤——收口校验与状态推导用。 */
    public boolean hasActiveStep(String workspaceId, String stage, int iteration) {
        Boolean has = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM project_stage_step
                WHERE workspace_id = ? AND stage = ? AND iteration = ? AND status IN ('EXECUTING','WAITING'))
                """, Boolean.class, workspaceId, stage, iteration);
        return Boolean.TRUE.equals(has);
    }

    /** 规划落库：替换本阶段本迭代的全部 PLANNED 行（重规划语义），已产出行保留。 */
    public void replacePlanned(String workspaceId, String stage, int iteration, List<StageStep> steps) {
        jdbc.update("DELETE FROM project_stage_step WHERE workspace_id = ? AND stage = ? AND iteration = ? AND status = 'PLANNED'",
                workspaceId, stage, iteration);
        for (StageStep s : steps) {
            insert(s);
        }
    }

    public long insert(StageStep s) {
        // RETURNING id 同连接同语句取主键——原先 INSERT 后另查 currval 是两次独立调用，
        // 无事务绑定时可能拿到不同连接（currval 是会话级），并发下报错或拿错 id
        // JSONB 列传 String 须显式 CAST（否则 PG 报类型不匹配）；verified_by 空时落默认 self
        Long id = jdbc.queryForObject("""
                INSERT INTO project_stage_step (workspace_id, stage, iteration, seq, name, step_type,
                    agent, participants, status, instruction, output_summary, output_full,
                    artifact_id, skip_reason, error, started_at, finished_at,
                    verified_by, acceptance_json, deviation_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                RETURNING id
                """,
                Long.class,
                s.workspaceId(), s.stage(), s.iteration(), s.seq(), s.name(),
                s.stepType().name(), s.agent(), StageStep.joinParticipants(s.participants()),
                s.status().name(), s.instruction(), s.outputSummary(), s.outputFull(),
                s.artifactId(), s.skipReason(), s.error(),
                s.startedAt() == null ? null : Timestamp.valueOf(s.startedAt()),
                s.finishedAt() == null ? null : Timestamp.valueOf(s.finishedAt()),
                s.verifiedBy(), s.acceptanceJson(), s.deviationJson());
        return id == null ? 0L : id;
    }

    /** 状态迁移落库（含产出/理由/错误等伴随字段）；信任链三字段原值保持。 */
    public void updateStatus(StageStep s) {
        jdbc.update("""
                UPDATE project_stage_step SET status = ?, output_summary = ?, output_full = ?,
                    artifact_id = ?, skip_reason = ?, error = ?, started_at = ?, finished_at = ?,
                    verified_by = ?, acceptance_json = CAST(? AS jsonb), deviation_json = CAST(? AS jsonb)
                WHERE id = ?
                """,
                s.status().name(), s.outputSummary(), s.outputFull(),
                s.artifactId(), s.skipReason(), s.error(),
                s.startedAt() == null ? null : Timestamp.valueOf(s.startedAt()),
                s.finishedAt() == null ? null : Timestamp.valueOf(s.finishedAt()),
                s.verifiedBy(), s.acceptanceJson(), s.deviationJson(),
                s.id());
    }

    /** 意图锚落库（规划级，一次规划一份，重规划覆盖）：执行链只读，主控每轮 prompt 强制携带。 */
    public void saveAnchor(String workspaceId, String stage, int iteration, String anchorText) {
        jdbc.update("""
                INSERT INTO project_stage_anchor (workspace_id, stage, iteration, anchor_text, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (workspace_id, stage, iteration)
                DO UPDATE SET anchor_text = EXCLUDED.anchor_text, updated_at = now()
                """, workspaceId, stage, iteration, anchorText);
    }

    /** 意图锚读取：无则返回 null（旧规划无锚，guidance 降级为不展示）。 */
    public String findAnchorText(String workspaceId, String stage, int iteration) {
        List<String> rows = jdbc.query("""
                SELECT anchor_text FROM project_stage_anchor
                WHERE workspace_id = ? AND stage = ? AND iteration = ?
                """, (rs, i) -> rs.getString("anchor_text"), workspaceId, stage, iteration);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 启动自愈：遗留 EXECUTING 标 FAILED（服务重启中断），返回处理行数。 */
    public int failOrphanExecuting(String reason) {
        return jdbc.update("""
                UPDATE project_stage_step SET status = 'FAILED', error = ?, finished_at = now()
                WHERE status = 'EXECUTING'
                """, reason);
    }

    /**
     * 讨论进行中逐条发言追加（AI 过程可见化）：只往 output_full 追加，不动状态与摘要。
     * 讨论完成时 withOutput 会用完整纪要整体覆盖，中间追加只是让轮询方实时看到进展。
     */
    public void appendSpeech(long id, String text) {
        jdbc.update("UPDATE project_stage_step SET output_full = COALESCE(output_full, '') || ? WHERE id = ?",
                text, id);
    }
}
