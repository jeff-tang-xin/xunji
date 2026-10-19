-- V36: 需求交付树——测试用例与 bug 实体 + 需求拆分层级
-- 用户拍板（2026-10-19）：拓扑从「需求×三阶段泳道」改为「需求为中心的交付树」：
-- 需求（原始→拆分点）→ 架构/开发任务 → 测试用例 → bug，一条需求纵向贯穿全部交付物。
-- 用例/bug 是机器可判定的信任链素材：需求下全部用例 PASSED 且无 OPEN bug → 需求可置 DONE。

-- 测试用例：测试设计步骤落档（declare_test_cases），执行结果回写（settle_test_cases）
CREATE TABLE project_test_case (
    id              BIGSERIAL PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    code            VARCHAR(32) NOT NULL,                        -- TC-001（项目内唯一）
    title           VARCHAR(200) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',       -- ACTIVE/PASSED/FAILED/BLOCKED
    requirement_code VARCHAR(32),                                -- 挂哪条需求（可空=全局用例）
    step_id         BIGINT,                                      -- 产出步骤（project_stage_step.id）
    content_md      TEXT,                                        -- 用例正文（前置/步骤/预期）
    created_by      VARCHAR(64),
    created_at      TIMESTAMP DEFAULT now(),
    updated_at      TIMESTAMP DEFAULT now(),
    UNIQUE (workspace_id, code)
);
CREATE INDEX idx_tc_ws ON project_test_case (workspace_id);
CREATE INDEX idx_tc_req ON project_test_case (workspace_id, requirement_code);

-- bug：测试执行步骤上报（report_bug），修复流转（settle_bugs）
CREATE TABLE project_bug (
    id              BIGSERIAL PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    code            VARCHAR(32) NOT NULL,                        -- BUG-001（项目内唯一）
    title           VARCHAR(200) NOT NULL,
    severity        VARCHAR(8)  NOT NULL DEFAULT 'P2',           -- P0/P1/P2/P3
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN',         -- OPEN/FIXING/FIXED/CLOSED/REJECTED
    requirement_code VARCHAR(32),                                -- 归属需求（可空）
    test_case_code  VARCHAR(32),                                 -- 发现自哪个用例（可空）
    step_id         BIGINT,                                      -- 发现/修复步骤
    content_md      TEXT,                                        -- 现象/根因/修复说明
    created_by      VARCHAR(64),
    created_at      TIMESTAMP DEFAULT now(),
    updated_at      TIMESTAMP DEFAULT now(),
    UNIQUE (workspace_id, code)
);
CREATE INDEX idx_bug_ws ON project_bug (workspace_id);
CREATE INDEX idx_bug_req ON project_bug (workspace_id, requirement_code);

-- 需求拆分层级：原始需求 → 拆分点（parent_code 空=原始需求；树形呈现用）
ALTER TABLE project_requirement ADD COLUMN parent_code VARCHAR(32);
