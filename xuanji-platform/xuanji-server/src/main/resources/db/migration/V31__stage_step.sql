-- V31: 阶段小流程台账（AI 驱动内核的世界状态/黑板）
-- 主控 agent 的规划、讨论纪要、任务产出、跳过、决策全部留档于此；
-- stage-execution 状态从本表推导。决策不单独建表（type=DECISION 的行）。
DROP TABLE IF EXISTS project_stage_step;
CREATE TABLE project_stage_step (
    id             BIGSERIAL PRIMARY KEY,
    workspace_id   VARCHAR(64)  NOT NULL,
    stage          VARCHAR(32)  NOT NULL,
    iteration      INT          NOT NULL DEFAULT 1,
    seq            INT          NOT NULL,
    name           VARCHAR(200) NOT NULL,
    step_type      VARCHAR(16)  NOT NULL DEFAULT 'TASK',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PLANNED',
    agent          VARCHAR(64),
    participants   TEXT,
    instruction    TEXT,
    output_summary TEXT,
    output_full    TEXT,
    artifact_id    BIGINT,
    skip_reason    TEXT,
    error          TEXT,
    created_at     TIMESTAMP    DEFAULT now(),
    started_at     TIMESTAMP,
    finished_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pss_ws_stage
    ON project_stage_step(workspace_id, stage, iteration, seq);
