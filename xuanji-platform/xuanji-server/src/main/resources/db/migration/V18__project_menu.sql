-- ============================================================
-- V18: 新增「项目看板」菜单（阶段 D：软件项目全生命周期管理）
--     platform_admin 菜单由 RbacInitializer 动态补全（自动含新菜单）；member 显式绑定
-- ============================================================

INSERT INTO sys_menu (code, name, path, sort_no) VALUES ('project', '项目看板', 'project', 2);

INSERT INTO sys_role_menu (role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.code = 'member' AND m.code = 'project';