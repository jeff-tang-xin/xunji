-- ============================================================
-- V8: 新增「资产管理」菜单（阶段 2：tool/skill/mcp/model-provider 四类资产统一管理页）
--     platform_admin 菜单由 RbacInitializer 动态补全（自动含新菜单）；member 显式绑定
-- ============================================================

INSERT INTO sys_menu (code, name, path, sort_no) VALUES ('assets', '资产管理', '09-assets.html', 10);

INSERT INTO sys_role_menu (role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.code = 'member' AND m.code = 'assets';
