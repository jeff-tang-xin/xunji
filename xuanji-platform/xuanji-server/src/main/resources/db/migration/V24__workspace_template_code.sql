-- ============================================================
-- V24: workspace 记录来源模板 code
--   用途：节点内对话时，按 (template_code, 节点 type/seq) 回查 project_template_node
--   的「通用层」知识（standards 规范 / requirements 要求 / methodology 方法论 /
--   deliverable 交付物形态），注入 agent 的 system prompt，使 AI 按阶段规范工作。
--
--   分层语义（与 V23 配套）：
--     · 模板 = 跨项目复用的规范与方法论（活引用：模板更新即时影响已有项目）
--     · 项目实例 = 具体任务边界（project_work_node.scope，创建项目时由用户填）
--   因此这里只存 code（活引用），不把规范文本快照到节点上。
--
--   历史项目 template_code 为 NULL：对话时降级为不注入阶段知识，不阻断对话。
-- ============================================================

ALTER TABLE workspace ADD COLUMN IF NOT EXISTS template_code VARCHAR(32);

-- 回填：按已建项目的节点链特征反推模板 code（仅对能明确匹配的种子模板回填，
-- 自定义模板创建的项目无法反推，留 NULL 由对话降级处理）
UPDATE workspace w SET template_code = 'standard'
WHERE w.binding_type = 'PROJECT' AND w.template_code IS NULL
  AND (SELECT COUNT(*) FROM project_work_node n WHERE n.workspace_id = w.id) = 10;

UPDATE workspace w SET template_code = 'minimal'
WHERE w.binding_type = 'PROJECT' AND w.template_code IS NULL
  AND (SELECT COUNT(*) FROM project_work_node n WHERE n.workspace_id = w.id) = 6;
