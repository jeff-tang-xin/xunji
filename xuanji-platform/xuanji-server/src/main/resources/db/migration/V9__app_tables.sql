-- ============================================================
-- V9: 应用层补列（阶段 3）—— app / app_credential 表 V1 已建，此处补齐
--     数据权限列（org_id）+ 审计列（updated_by/updated_at）+ App 描述 + 凭据签发人
--     状态语义沿用主文档 4.9：DRAFT（创建）→ PUBLISHED（发布，对外可消费）→ DISABLED
-- ============================================================

ALTER TABLE app ADD COLUMN IF NOT EXISTS description VARCHAR(512);
ALTER TABLE app ADD COLUMN IF NOT EXISTS org_id BIGINT REFERENCES sys_org(id);
ALTER TABLE app ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);
ALTER TABLE app ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_app_org ON app(org_id);
CREATE INDEX IF NOT EXISTS idx_app_cby ON app(created_by);

ALTER TABLE app_credential ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);
