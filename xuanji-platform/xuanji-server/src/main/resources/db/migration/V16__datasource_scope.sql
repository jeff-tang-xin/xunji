-- ============================================================
-- V16: connector_config 补 created_by 列（数据源为平台级共享资源，
--     管理端 create/delete 做归属校验：创建者本人或平台管理员可删）
-- ============================================================

ALTER TABLE connector_config ADD COLUMN created_by VARCHAR(64);
CREATE INDEX idx_connector_config_cby ON connector_config(created_by);