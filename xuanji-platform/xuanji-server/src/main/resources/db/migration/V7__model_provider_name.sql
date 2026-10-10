-- ============================================================
-- V7: model_provider_config 补 name + 状态默认值对齐统一生命周期
-- ============================================================

ALTER TABLE model_provider_config ADD COLUMN name VARCHAR(128);
ALTER TABLE model_provider_config ALTER COLUMN status SET DEFAULT 'DRAFT';
UPDATE model_provider_config SET status = 'DRAFT' WHERE status = 'ENABLED';
