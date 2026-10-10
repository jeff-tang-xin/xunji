-- ============================================================
-- V3: 资产数据权限列（Q13：过滤统一查询层 org_id + created_by）+ skill 内容列
-- 资产五类：workflow(wf_definition) / agent(agent_config) / tool(tool_registry)
--           / skill(skill_repo) / mcp(mcp_config)；model_provider 为平台级配置不过滤
-- ============================================================

-- wf_definition 已有 created_by，补 org_id
ALTER TABLE wf_definition ADD COLUMN org_id BIGINT REFERENCES sys_org(id);

-- 四类资产表补 org_id + created_by（原表仅有 updated_by）
ALTER TABLE agent_config   ADD COLUMN org_id     BIGINT REFERENCES sys_org(id);
ALTER TABLE agent_config   ADD COLUMN created_by VARCHAR(64);
ALTER TABLE tool_registry  ADD COLUMN org_id     BIGINT REFERENCES sys_org(id);
ALTER TABLE tool_registry  ADD COLUMN created_by VARCHAR(64);
ALTER TABLE skill_repo     ADD COLUMN org_id     BIGINT REFERENCES sys_org(id);
ALTER TABLE skill_repo     ADD COLUMN created_by VARCHAR(64);
ALTER TABLE mcp_config     ADD COLUMN org_id     BIGINT REFERENCES sys_org(id);
ALTER TABLE mcp_config     ADD COLUMN created_by VARCHAR(64);

-- skill 内容直存库（P0 不做对象存储；content_uri 保留可空，后续可指向外部仓库）
ALTER TABLE skill_repo ALTER COLUMN content_uri DROP NOT NULL;
ALTER TABLE skill_repo ADD COLUMN content_md TEXT;

-- 数据权限过滤索引
CREATE INDEX idx_wf_definition_org  ON wf_definition(org_id);
CREATE INDEX idx_wf_definition_cby  ON wf_definition(created_by);
CREATE INDEX idx_agent_config_org   ON agent_config(org_id);
CREATE INDEX idx_agent_config_cby   ON agent_config(created_by);
CREATE INDEX idx_tool_registry_org  ON tool_registry(org_id);
CREATE INDEX idx_tool_registry_cby  ON tool_registry(created_by);
CREATE INDEX idx_skill_repo_org     ON skill_repo(org_id);
CREATE INDEX idx_skill_repo_cby     ON skill_repo(created_by);
CREATE INDEX idx_mcp_config_org     ON mcp_config(org_id);
CREATE INDEX idx_mcp_config_cby     ON mcp_config(created_by);
