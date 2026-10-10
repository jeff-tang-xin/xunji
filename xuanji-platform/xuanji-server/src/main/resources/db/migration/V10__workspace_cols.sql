-- V10: workspace 表补列（对齐统一归属模型：Q13 数据范围作用面含工作区）
-- V1 基线 workspace 表已有 id/name/root_path/binding_type/bound_asset_id/bound_asset_version/authorization_json/created_by/created_at

ALTER TABLE workspace ADD COLUMN IF NOT EXISTS org_id BIGINT;
ALTER TABLE workspace ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE workspace ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;
