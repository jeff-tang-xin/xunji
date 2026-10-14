-- ============================================================
-- V25: 模板阶段节点显式绑定「执行智能体」与「默认工作流」
--
--   动机：原先阶段只能用 owner_role（负责职能）间接表达由谁执行，后端靠
--   ProjectChatService.matchAgent() 的硬编码 switch 映射到内置 agent——
--   界面上看不到「这个阶段到底用哪个智能体」，也无法直接指定；阶段与工作流
--   （流程）的绑定同样只能在项目实例层逐个 bind-workflow，模板层无默认值。
--
--   分层语义（延续 V23/V24）：
--     · agent_name      —— 通用层：该阶段由哪个内置 agent 执行（跨项目复用）
--     · workflow_def_id —— 通用层：该阶段默认跑哪个已发布工作流；创建项目时
--                          快照进 project_work_node.workflow_ref = {"definitionId":N}，
--                          实例可再单独改绑（bind-workflow），互不影响。
--
--   兼容：agent_name 为空时，节点对话回退 owner_role → agent 映射（老行为不变）。
-- ============================================================

ALTER TABLE project_template_node ADD COLUMN IF NOT EXISTS agent_name VARCHAR(64);
ALTER TABLE project_template_node ADD COLUMN IF NOT EXISTS workflow_def_id BIGINT;

COMMENT ON COLUMN project_template_node.agent_name IS
    '该阶段执行的内置 agent name（空则回退 owner_role 映射）';
COMMENT ON COLUMN project_template_node.workflow_def_id IS
    '该阶段默认绑定的工作流定义 id（创建项目时快照进节点 workflow_ref）';

-- 回填：按既有 owner_role → agent 映射补齐所有模板阶段（含内置 standard/minimal），
-- 使界面立刻能看到每个阶段对应的智能体。与 ProjectChatService.matchAgent 的取值一致。
UPDATE project_template_node SET agent_name = CASE TRIM(owner_role)
    WHEN '产品/PM'   THEN 'project_manager'
    WHEN '产品/BA'   THEN 'product_analyst'
    WHEN '架构师'     THEN 'architect'
    WHEN '开发'       THEN 'developer'
    WHEN '测试'       THEN 'tester'
    WHEN '测试/开发'  THEN 'tester'
    WHEN '运维'       THEN 'devops'
    WHEN 'DB'        THEN 'dba'
    ELSE NULL
END
WHERE agent_name IS NULL AND owner_role IS NOT NULL;

-- 回填默认工作流：当前环境仅两条已发布测试工作流，不足以按阶段语义分配，
-- 故 workflow_def_id 一律留空，由用户在模板编辑器里显式选择（避免凭空绑定）。
