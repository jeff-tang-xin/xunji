-- ============================================================
-- V26: 项目四阶段生命周期（立项 → 需求 → 开发 → 运维常驻）
--
--   ① 大阶段 stage 由平台固定为 4 个值，模板不得自定义阶段集合：
--        INITIATIVE  立项：项目说明、文档与留档结构建立
--        REQUIREMENT 需求：需求分析与撰写、原型设计
--        DEVELOP     开发：架构、详设、开发、用例、冒烟、提测、测试
--        OPERATE     运维：发布部署、DB、后期 Bug 与新需求（常驻阶段）
--      单向推进、不可回退；OPERATE 是常驻终态（没有"完成"，只有继续迭代）。
--   ② 模板只定义「每个大阶段里有哪些节点 + 该阶段通用规范」，
--      因此 project_template_node 需要 stage 归属列。
--   ③ 运维阶段内的新需求/Bug 走「迭代」：project_work_node.iteration 记迭代号。
--      大阶段不回退，迭代可重复 —— 这是"不可回退"与"运维承接新需求"的调和方式。
--   ④ 创建项目时选择新项目 / 老项目维护：老项目直接落 OPERATE，只生成运维阶段节点。
--
--   幂等：ADD COLUMN IF NOT EXISTS；回填带 WHERE stage IS NULL，不覆盖人工改判。
--   本迁移不含业务数据删除（存量测试项目由运维单独清理，避免破坏性 DML 被其他环境重放）。
-- ============================================================

-- ① 项目当前所处大阶段：默认立项，新建项目由代码写入实际值
ALTER TABLE workspace ADD COLUMN IF NOT EXISTS current_stage VARCHAR(16) NOT NULL DEFAULT 'INITIATIVE';
CREATE INDEX IF NOT EXISTS idx_workspace_current_stage ON workspace(current_stage);

-- ② 节点归属大阶段 + 迭代号（iteration 默认 1 = 首次交付；运维阶段内的重复轮次从 2 起）
ALTER TABLE project_work_node ADD COLUMN IF NOT EXISTS stage     VARCHAR(16);
ALTER TABLE project_work_node ADD COLUMN IF NOT EXISTS iteration INT NOT NULL DEFAULT 1;

-- ③ 模板节点归属大阶段
ALTER TABLE project_template_node ADD COLUMN IF NOT EXISTS stage VARCHAR(16);

CREATE INDEX IF NOT EXISTS idx_pwn_ws_stage ON project_work_node(workspace_id, stage, iteration);

-- ------------------------------------------------------------
-- 模板节点归入大阶段：按 type 映射（与前端 NODE_TYPE_LABEL 取值对齐）
-- 未知/自定义阶段类型保守落 DEVELOP —— 它是唯一"必须做完才能进运维"的阶段，
-- 宁可让新类型卡在开发阶段被人工改判，也不要静默跳过交付质量门。
-- ------------------------------------------------------------
UPDATE project_template_node SET stage = CASE UPPER(TRIM(type))
    WHEN 'DISCUSS'       THEN 'INITIATIVE'
    WHEN 'INITIATION'    THEN 'INITIATIVE'
    WHEN 'PROJECT_DOC'   THEN 'INITIATIVE'
    WHEN 'REQUIREMENT'   THEN 'REQUIREMENT'
    WHEN 'PRD'           THEN 'REQUIREMENT'
    WHEN 'PROTOTYPE'     THEN 'REQUIREMENT'
    WHEN 'UI_DESIGN'     THEN 'REQUIREMENT'
    WHEN 'ARCHITECTURE'  THEN 'DEVELOP'
    WHEN 'DESIGN'        THEN 'DEVELOP'
    WHEN 'TEST_CASE'     THEN 'DEVELOP'
    WHEN 'DEVELOP'       THEN 'DEVELOP'
    WHEN 'SMOKE'         THEN 'DEVELOP'
    WHEN 'SUBMIT'        THEN 'DEVELOP'
    WHEN 'TEST'          THEN 'DEVELOP'
    WHEN 'REVIEW'        THEN 'DEVELOP'
    WHEN 'RELEASE'       THEN 'OPERATE'
    WHEN 'DEPLOY'        THEN 'OPERATE'
    WHEN 'OPS'           THEN 'OPERATE'
    WHEN 'BUGFIX'        THEN 'OPERATE'
    WHEN 'MONITOR'       THEN 'OPERATE'
    WHEN 'DB'            THEN 'OPERATE'
    WHEN 'DATABASE'      THEN 'OPERATE'
    ELSE 'DEVELOP'
  END
WHERE stage IS NULL;

-- ------------------------------------------------------------
-- 项目节点同样按 type 回填（存量测试项目随后会被清理，此处保证清理前/
-- 万一有残留数据时，节点不会因为没有 stage 而无法归组展示）
-- ------------------------------------------------------------
UPDATE project_work_node SET stage = CASE UPPER(TRIM(type))
    WHEN 'DISCUSS'       THEN 'INITIATIVE'
    WHEN 'INITIATION'    THEN 'INITIATIVE'
    WHEN 'PROJECT_DOC'   THEN 'INITIATIVE'
    WHEN 'REQUIREMENT'   THEN 'REQUIREMENT'
    WHEN 'PRD'           THEN 'REQUIREMENT'
    WHEN 'PROTOTYPE'     THEN 'REQUIREMENT'
    WHEN 'UI_DESIGN'     THEN 'REQUIREMENT'
    WHEN 'ARCHITECTURE'  THEN 'DEVELOP'
    WHEN 'DESIGN'        THEN 'DEVELOP'
    WHEN 'TEST_CASE'     THEN 'DEVELOP'
    WHEN 'DEVELOP'       THEN 'DEVELOP'
    WHEN 'SMOKE'         THEN 'DEVELOP'
    WHEN 'SUBMIT'        THEN 'DEVELOP'
    WHEN 'TEST'          THEN 'DEVELOP'
    WHEN 'REVIEW'        THEN 'DEVELOP'
    WHEN 'RELEASE'       THEN 'OPERATE'
    WHEN 'DEPLOY'        THEN 'OPERATE'
    WHEN 'OPS'           THEN 'OPERATE'
    WHEN 'BUGFIX'        THEN 'OPERATE'
    WHEN 'MONITOR'       THEN 'OPERATE'
    WHEN 'DB'            THEN 'OPERATE'
    WHEN 'DATABASE'      THEN 'OPERATE'
    ELSE 'DEVELOP'
  END
WHERE stage IS NULL;
