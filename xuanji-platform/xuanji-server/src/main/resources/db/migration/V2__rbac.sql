-- ============================================================
-- V2: RBAC 权限体系（主文档 v0.9 Q13：用户→角色→菜单 + 组织树 + 数据权限四档）
-- 数据权限四档：ALL / UNDER_ORG / ORG / ONLY_CREATE（平台管理员 bypass，正交于菜单权限）
-- ============================================================

-- 组织树（path 物化路径，如 /1/3/7/，支撑 under_org 前缀查询）
CREATE TABLE sys_org (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(128) NOT NULL,
  parent_id   BIGINT REFERENCES sys_org(id),
  path        VARCHAR(512),
  sort_no     INT DEFAULT 0,
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMPTZ DEFAULT now()
);

-- 用户（密码 BCrypt；must_change_password=首次登录/管理员重置后强制改密）
CREATE TABLE sys_user (
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username             VARCHAR(64) NOT NULL UNIQUE,
  password_hash        VARCHAR(128) NOT NULL,
  display_name         VARCHAR(64),
  org_id               BIGINT REFERENCES sys_org(id),
  is_platform_admin    BOOLEAN NOT NULL DEFAULT FALSE,
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
  status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/DISABLED
  last_login_at        TIMESTAMPTZ,
  created_by           VARCHAR(64),
  created_at           TIMESTAMPTZ DEFAULT now(),
  updated_at           TIMESTAMPTZ DEFAULT now()
);

-- 角色（data_scope 四档；is_built_in 内置角色禁删）
CREATE TABLE sys_role (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        VARCHAR(64) NOT NULL UNIQUE,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  data_scope  VARCHAR(16) NOT NULL DEFAULT 'ONLY_CREATE',
  is_built_in BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ DEFAULT now()
);

-- 菜单（管理台左侧导航；permission 预留按钮级权限点）
CREATE TABLE sys_menu (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code       VARCHAR(64) NOT NULL UNIQUE,
  name       VARCHAR(128) NOT NULL,
  parent_id  BIGINT REFERENCES sys_menu(id),
  path       VARCHAR(128),
  icon       VARCHAR(64),
  sort_no    INT DEFAULT 0,
  menu_type  VARCHAR(16) NOT NULL DEFAULT 'PAGE',  -- DIR/PAGE
  permission VARCHAR(128),
  status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE sys_user_role (
  user_id BIGINT NOT NULL REFERENCES sys_user(id),
  role_id BIGINT NOT NULL REFERENCES sys_role(id),
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sys_role_menu (
  role_id BIGINT NOT NULL REFERENCES sys_role(id),
  menu_id BIGINT NOT NULL REFERENCES sys_menu(id),
  PRIMARY KEY (role_id, menu_id)
);

CREATE INDEX idx_user_org ON sys_user(org_id);
CREATE INDEX idx_org_parent ON sys_org(parent_id);
CREATE INDEX idx_menu_parent ON sys_menu(parent_id);

-- ---------- 种子数据：菜单（对应管理台 9 个导航页） ----------
INSERT INTO sys_menu (code, name, path, sort_no) VALUES
  ('workspace', '工作区',     '00-workspace.html',        1),
  ('canvas',    '画布编辑器', '01-canvas-editor.html',    2),
  ('run',       '运行监控',   '02-run-monitor.html',      3),
  ('approval',  '决策工作区', '03-approval-center.html',  4),
  ('publish',   '发布中心',   '04-publish-center.html',   5),
  ('agent',     'Agent 管理台','05-agent-admin.html',     6),
  ('app',       '应用管理',   '06-app-admin.html',        7),
  ('report',    '报表中心',   '07-report-dashboard.html', 8),
  ('perm',      '权限管理',   '08-permissions.html',      9);

-- ---------- 种子数据：组织树 ----------
INSERT INTO sys_org (name, parent_id, path) VALUES ('玄机科技', NULL, '/');
INSERT INTO sys_org (name, parent_id, path)
  SELECT '平台研发部', id, '/' || id || '/' FROM sys_org WHERE parent_id IS NULL;
INSERT INTO sys_org (name, parent_id, path)
  SELECT '交付部', id, '/' || id || '/' FROM sys_org WHERE parent_id IS NULL;
INSERT INTO sys_org (name, parent_id, path)
  SELECT '质量部', id, '/' || id || '/' FROM sys_org WHERE parent_id IS NULL;

-- ---------- 种子数据：内置角色 ----------
-- 平台管理员：bypass（不受组织与数据权限控制），菜单绑定在初始化器里补全（全部菜单）
INSERT INTO sys_role (code, name, description, data_scope, is_built_in)
VALUES ('platform_admin', '平台管理员', '平台超级管理员：不受组织与数据权限控制（bypass 全量），敏感操作留审计', 'ALL', TRUE);

-- 成员：仅本人创建数据，默认可见基础菜单（画布/运行/决策/报表）
INSERT INTO sys_role (code, name, description, data_scope, is_built_in)
VALUES ('member', '成员', '仅可查看与操作本人创建的数据', 'ONLY_CREATE', TRUE);
INSERT INTO sys_role_menu (role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.code = 'member' AND m.code IN ('workspace', 'canvas', 'run', 'approval', 'report');
