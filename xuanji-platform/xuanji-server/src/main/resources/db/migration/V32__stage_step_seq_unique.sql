-- V32: 阶段步骤 seq 唯一约束（竞态兜底）
-- 背景：OrchestrationTools.nextStep 的 synchronized 只锁每请求实例，跨请求并发时
-- max(seq)+1 仍可能撞号（2026-10-13 实测 ws_f1127d9f 出现 3 行 seq=2）。
-- 应用层已修（单例锁），本迁移在 DB 层兜底：先清历史重复（竞态 bug 产物，保留每组最小 id），
-- 再建唯一索引——此后撞号直接 DB 报错，不再静默产生重复台账行。
DELETE FROM project_stage_step s
USING project_stage_step t
WHERE s.workspace_id = t.workspace_id
  AND s.stage = t.stage
  AND s.iteration = t.iteration
  AND s.seq = t.seq
  AND s.id > t.id;
DROP INDEX IF EXISTS idx_pss_ws_stage;
CREATE UNIQUE INDEX uk_pss_ws_stage_iter_seq
    ON project_stage_step(workspace_id, stage, iteration, seq);
