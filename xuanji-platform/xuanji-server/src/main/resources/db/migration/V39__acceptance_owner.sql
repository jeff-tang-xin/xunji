-- V39: 验收责任链（P0）——每步声明验收人，human 接入信任链
-- acceptance_owner: 'tool'=机器验收（artifact/shell 断言，默认）| 'human'=用户验收（执行完进 WAITING_ACCEPT，用户通过才 DONE）
ALTER TABLE project_stage_step ADD COLUMN IF NOT EXISTS acceptance_owner VARCHAR(16) NOT NULL DEFAULT 'tool';
