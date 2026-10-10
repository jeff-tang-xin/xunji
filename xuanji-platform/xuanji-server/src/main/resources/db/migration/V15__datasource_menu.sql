-- ============================================================
-- V15: 新增「数据源管理」菜单（C5 数据源连接器配置页）
--     platform_admin 菜单由 RbacInitializer 动态补全（自动含新菜单）；member 显式绑定
-- ============================================================

INSERT INTO sys_menu (code, name, path, sort_no) VALUES ('datasource', '数据源管理', 'datasource', 11);

INSERT INTO sys_role_menu (role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.code = 'member' AND m.code = 'datasource';
