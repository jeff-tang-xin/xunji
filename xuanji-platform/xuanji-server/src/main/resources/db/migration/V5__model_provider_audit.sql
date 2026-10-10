-- ============================================================
-- V5: model_provider_config 补审计列（V1 原表无 updated_by/updated_at，通用 CRUD 需要）
-- ============================================================

ALTER TABLE model_provider_config ADD COLUMN updated_by VARCHAR(64);
ALTER TABLE model_provider_config ADD COLUMN updated_at TIMESTAMPTZ DEFAULT now();
