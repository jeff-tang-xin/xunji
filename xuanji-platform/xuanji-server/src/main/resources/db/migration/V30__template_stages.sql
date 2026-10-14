-- V30: 模板升级为「3 阶段 × 执行路径预设」形态（贴合 V27+ 阶段生命周期架构）
-- 旧 project_template_node 节点链保留（历史兼容），新模板以 stages_json 为准：
-- {REQUIREMENT:{workflow,agents}, DEVELOP:{...}, OPERATE:{...,servers,connectors}}
-- 创建项目选模板时按阶段写入 project_stage_capability（workflow/agents/servers/connectors）。
ALTER TABLE project_template ADD COLUMN IF NOT EXISTS stages_json JSONB;
