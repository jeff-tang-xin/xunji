-- V33: 信任链第一批——verified_by 三态 + acceptance 断言 + deviation 申报 + 意图锚
-- 设计依据（2026-10-14 责任边界讨论）：
--   verified_by: self=AI 自述完成（默认，AI 工具只能写这个值）/ tool=机器判定证实 / human=人确认
--   acceptance_json: 步骤验收断言 [{type: test|artifact|api, target, expect}]，规划批准时锁定
--   deviation_json: 偏差申报 {reason, fallback, declared_at}——降级唯一合法通道，静默降级由判定器抓
--   project_stage_anchor: 意图锚（规划级，一次规划一份），执行链不可改写，每步 prompt 强制携带
ALTER TABLE project_stage_step ADD COLUMN IF NOT EXISTS verified_by VARCHAR(16) NOT NULL DEFAULT 'self';
ALTER TABLE project_stage_step ADD COLUMN IF NOT EXISTS acceptance_json JSONB;
ALTER TABLE project_stage_step ADD COLUMN IF NOT EXISTS deviation_json JSONB;

CREATE TABLE IF NOT EXISTS project_stage_anchor (
    id           BIGSERIAL PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    stage        VARCHAR(32) NOT NULL,
    iteration    INT         NOT NULL DEFAULT 1,
    anchor_text  TEXT,
    updated_at   TIMESTAMP   DEFAULT now(),
    CONSTRAINT uq_psa_ws_stage_iter UNIQUE (workspace_id, stage, iteration)
);
