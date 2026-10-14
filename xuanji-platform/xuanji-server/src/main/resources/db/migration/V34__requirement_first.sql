-- V34: 需求先行——需求项实体（讨论式开发的主干锚定物）
-- 需求项 = 线的锚定物：任何实施工作开始前必须能回答「为了哪个需求」；
-- confirmed 由用户确认（human，信任链第一环），PM 不得自行置 confirmed。
CREATE TABLE project_requirement (
    id BIGSERIAL PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    code VARCHAR(32) NOT NULL,                       -- REQ-001（项目内唯一，PM 命名）
    title VARCHAR(200) NOT NULL,
    description TEXT,
    acceptance_json JSONB,                           -- 验收标准（可判定，人确认后锁定）
    status VARCHAR(16) NOT NULL DEFAULT 'PROPOSED',  -- PROPOSED/CONFIRMED/IN_PROGRESS/DONE/DROPPED
    depends_on JSONB,                                -- ["REQ-001",...] 依赖的需求 code
    source VARCHAR(16) NOT NULL DEFAULT 'INCUBATION',-- INCUBATION=首次孵化 / APPEND=运行中追加
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP DEFAULT now(),
    UNIQUE (workspace_id, code)
);
CREATE INDEX idx_req_ws ON project_requirement (workspace_id);

-- 步骤与需求项的关联（步骤声明「为了哪个需求」；JSONB 数组存 code）
ALTER TABLE project_stage_step ADD COLUMN requirement_codes JSONB;
