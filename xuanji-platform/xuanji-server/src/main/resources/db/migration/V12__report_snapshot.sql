-- V12: 报表看板快照（主文档 1.5：每次执行固化一份看板快照）
-- 调度执行器在 run SUCCESS 后从 output_json 提取 board 配置落快照；
-- 报表详情默认展示最新快照，历史快照可回看审计。
CREATE TABLE report_snapshot (
  id            VARCHAR(32) PRIMARY KEY,               -- rs_ 前缀
  report_id     VARCHAR(32) NOT NULL,
  run_id        VARCHAR(32) NOT NULL,
  snapshot_json JSONB NOT NULL,                        -- 看板配置（title/kpis/columns/rows）
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_snapshot ON report_snapshot(report_id, created_at DESC);
