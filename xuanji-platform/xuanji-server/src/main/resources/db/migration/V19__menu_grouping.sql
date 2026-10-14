-- ============================================================
-- V19: 菜单按类别分组（DIR 分组 + PAGE 挂载）
--     解决侧边栏 12 个菜单平铺混乱的问题。
--     新增 6 个 DIR 分组菜单，把现有 PAGE 菜单挂到对应分组下。
--     platform_admin 菜单由 RbacInitializer 动态补全（自动含新 DIR）；
--     member 角色只绑定 PAGE，后端查询时自动带上所属 DIR 分组。
-- ============================================================

-- 1. 插入 DIR 分组菜单（parent_id 为 NULL，menu_type='DIR'）
INSERT INTO sys_menu (code, name, path, sort_no, menu_type) VALUES
  ('grp_project',     '项目管理', NULL, 1, 'DIR'),
  ('grp_flow',        '流程编排', NULL, 2, 'DIR'),
  ('grp_agent',       '智能体',   NULL, 3, 'DIR'),
  ('grp_asset',       '资产中心', NULL, 4, 'DIR'),
  ('grp_integration', '开放集成', NULL, 5, 'DIR'),
  ('grp_system',      '系统管理', NULL, 6, 'DIR');

-- 2. 把 PAGE 菜单挂到分组下（parent_id + 组内 sort_no）
--    项目管理
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_project'), sort_no = 1 WHERE code = 'project';
--    流程编排
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_flow'), sort_no = 1 WHERE code = 'canvas';
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_flow'), sort_no = 2 WHERE code = 'run';
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_flow'), sort_no = 3 WHERE code = 'approval';
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_flow'), sort_no = 4 WHERE code = 'publish';
--    智能体
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_agent'), sort_no = 1 WHERE code = 'workspace';
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_agent'), sort_no = 2 WHERE code = 'agent';
--    资产中心
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_asset'), sort_no = 1 WHERE code = 'assets';
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_asset'), sort_no = 2 WHERE code = 'datasource';
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_asset'), sort_no = 3 WHERE code = 'report';
--    开放集成
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_integration'), sort_no = 1 WHERE code = 'app';
--    系统管理
UPDATE sys_menu SET parent_id = (SELECT id FROM sys_menu WHERE code = 'grp_system'), sort_no = 1 WHERE code = 'perm';