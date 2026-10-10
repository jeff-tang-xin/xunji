-- ============================================================
-- V4: model_provider_config 补数据权限列（V3 遗漏——通用 CRUD 统一填 org_id/created_by）
-- ============================================================

ALTER TABLE model_provider_config ADD COLUMN org_id     BIGINT REFERENCES sys_org(id);
ALTER TABLE model_provider_config ADD COLUMN created_by VARCHAR(64);
CREATE INDEX idx_model_provider_org ON model_provider_config(org_id);
CREATE INDEX idx_model_provider_cby ON model_provider_config(created_by);
