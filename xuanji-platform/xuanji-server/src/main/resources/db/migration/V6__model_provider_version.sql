-- ============================================================
-- V6: model_provider_config 补 version（五类资产统一生命周期 DRAFT→PUBLISHED→…）
-- ============================================================

ALTER TABLE model_provider_config ADD COLUMN version INT NOT NULL DEFAULT 1;
