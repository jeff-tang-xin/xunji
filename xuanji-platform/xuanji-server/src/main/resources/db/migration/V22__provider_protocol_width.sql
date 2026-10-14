-- ============================================================
-- V22: 扩大 model_provider_config.protocol 字段宽度
--   ANTHROPIC_PROTOCOL（18 字符）超出原 VARCHAR(16)，导致
--   SeedInitializer 内置 Anthropic provider 插入失败
-- ============================================================
ALTER TABLE model_provider_config
  ALTER COLUMN protocol TYPE VARCHAR(32);