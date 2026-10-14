-- ============================================================
-- V20: 项目模板（节点链模板化）
--   新建 project_template（模板主表）+ project_template_node（模板节点）
--   种子数据：standard（10 节点）/ minimal（6 节点），节点定义从
--   ProjectService.buildNodeChain() 迁移
--   depends_on 存【seq 整数数组】（模板节点无 workspace 前缀 id），
--   create() 时把 seq 映射为实际节点 id ws_<wsid>_n<seq>
-- ============================================================

-- 模板主表：code 唯一（standard/minimal），供前端下拉动态加载
CREATE TABLE IF NOT EXISTS project_template (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        VARCHAR(32) NOT NULL UNIQUE,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  created_at  TIMESTAMPTZ DEFAULT now()
);

-- 模板节点表：type/seq/depends_on/preconditions/owner_role 与 project_work_node 对齐
--   depends_on: JSONB 整数数组，依赖的上游节点 seq（DAG 边）
--   preconditions: JSONB 字符串数组，前置条件描述
CREATE TABLE IF NOT EXISTS project_template_node (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  template_id  BIGINT NOT NULL REFERENCES project_template(id) ON DELETE CASCADE,
  type         VARCHAR(24) NOT NULL,
  seq          INT NOT NULL,
  depends_on   JSONB,
  preconditions JSONB,
  owner_role   VARCHAR(32),
  created_at   TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ptn_template ON project_template_node(template_id, seq);
-- 同模板内 seq 唯一（防重复种子）
CREATE UNIQUE INDEX IF NOT EXISTS uq_ptn_template_seq ON project_template_node(template_id, seq);

-- ============================================================
-- 种子数据：standard（10 节点）
--   DISCUSS→REQUIREMENT→ARCHITECTURE→DESIGN→(TEST_CASE∥DEVELOP)→SMOKE→SUBMIT→TEST→RELEASE
--   测试用例与开发并行（都依赖 DESIGN seq4），提测前置条件=冒烟 case 跑完
-- ============================================================
INSERT INTO project_template (code, name, description) VALUES
  ('standard', '标准项目', '标准软件项目全生命周期：讨论→需求→架构→详设→(测试用例∥开发)→冒烟→提测→测试→发布')
ON CONFLICT (code) DO NOTHING;

INSERT INTO project_template_node (template_id, type, seq, depends_on, preconditions, owner_role)
SELECT t.id, n.type, n.seq, n.depends_on::jsonb, n.preconditions::jsonb, n.owner_role
FROM project_template t, (VALUES
  ('DISCUSS',      1, '[]',        '[]',                '产品/PM'),
  ('REQUIREMENT',  2, '[1]',       '[]',                '产品/BA'),
  ('ARCHITECTURE', 3, '[2]',       '[]',                '架构师'),
  ('DESIGN',       4, '[3]',       '[]',                '架构师'),
  ('TEST_CASE',    5, '[4]',       '[]',                '测试'),
  ('DEVELOP',      6, '[4]',       '[]',                '开发'),
  ('SMOKE',        7, '[6]',       '[]',                '测试'),
  ('SUBMIT',       8, '[5,7]',     '["冒烟 case 跑完"]', '测试/开发'),
  ('TEST',         9, '[8]',       '[]',                '测试'),
  ('RELEASE',     10, '[9]',       '[]',                '运维')
) AS n(type, seq, depends_on, preconditions, owner_role)
WHERE t.code = 'standard'
ON CONFLICT (template_id, seq) DO NOTHING;

-- ============================================================
-- 种子数据：minimal（6 节点）
--   DISCUSS→REQUIREMENT→ARCHITECTURE→DEVELOP→TEST→RELEASE
-- ============================================================
INSERT INTO project_template (code, name, description) VALUES
  ('minimal', '精简项目', '精简软件项目：讨论→需求→架构→开发→测试→发布')
ON CONFLICT (code) DO NOTHING;

INSERT INTO project_template_node (template_id, type, seq, depends_on, preconditions, owner_role)
SELECT t.id, n.type, n.seq, n.depends_on::jsonb, n.preconditions::jsonb, n.owner_role
FROM project_template t, (VALUES
  ('DISCUSS',      1, '[]',  '[]', '产品/PM'),
  ('REQUIREMENT',  2, '[1]', '[]', '产品/BA'),
  ('ARCHITECTURE', 3, '[2]', '[]', '架构师'),
  ('DEVELOP',      4, '[3]', '[]', '开发'),
  ('TEST',         5, '[4]', '[]', '测试'),
  ('RELEASE',      6, '[5]', '[]', '运维')
) AS n(type, seq, depends_on, preconditions, owner_role)
WHERE t.code = 'minimal'
ON CONFLICT (template_id, seq) DO NOTHING;