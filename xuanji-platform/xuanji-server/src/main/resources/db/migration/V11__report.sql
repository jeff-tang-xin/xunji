-- V11: report 表（报表 = 可创建的定时任务实体，主文档 1.5 工作报表场景）
-- 报表 = 命名 + 来源 workflow（版本钉住，必须已发布）+ 定时执行计划（SCHEDULE，cron 表达式）
-- 每次执行 = 一次 run（trigger_type=SCHEDULE）→ 看板快照（board.render 节点实装后固化，P0 仅落 run）
-- 归属审计列对齐统一归属模型（Q13 数据范围作用面含报表）

CREATE TABLE report (
    id              VARCHAR(32) PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    definition_id   BIGINT NOT NULL,
    pinned_version  INTEGER NOT NULL,
    cron_expr       VARCHAR(64) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    org_id          BIGINT,
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ
);

CREATE INDEX idx_report_org ON report (org_id);
CREATE INDEX idx_report_created_by ON report (created_by);
