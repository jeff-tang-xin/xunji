-- V13: agent_config 加 skills_json —— Anthropic Agent Skills 形态：skill 绑定到 Agent 本身
-- 存 agent 级 skill 引用数组 ["skill:{id}@{version}"]（钉住已发布版本）；
-- 运行时 AgentNodeExecutor 解析（agent 级 + 节点级 capabilityBinding 合并）查 skill_repo
-- 的 name/description/content_md，注入 Agent 的 system prompt。
ALTER TABLE agent_config ADD COLUMN skills_json JSONB;