package io.xuanji.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * RBAC 管理域数据访问（sys_user / sys_role / sys_menu / sys_user_role / sys_role_menu / sys_org）：
 * SQL 从 {@code RbacService} 抽离。密码生成、重复校验决策、角色/菜单分配的编排等业务留 Service。
 */
@Repository
public class RbacRepository {

    private final JdbcTemplate jdbc;

    public RbacRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 用户 ----------

    public List<Map<String, Object>> listUsers() {
        return jdbc.queryForList(
                "SELECT u.id, u.username, u.display_name, u.org_id, o.name AS org_name, "
                        + "u.is_platform_admin, u.must_change_password, u.status, u.last_login_at, u.created_at "
                        + "FROM sys_user u LEFT JOIN sys_org o ON o.id = u.org_id ORDER BY u.id");
    }

    public List<String> findRoleNamesByUser(long userId) {
        return jdbc.queryForList(
                "SELECT r.name FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? ORDER BY r.id", String.class, userId);
    }

    public List<Long> findRoleIdsByUser(long userId) {
        return jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id = ?", Long.class, userId);
    }

    public Integer countUserByUsername(String username) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, username);
    }

    public void insertUser(String username, String hash, String displayName, Long orgId, String createdBy) {
        jdbc.update("INSERT INTO sys_user (username, password_hash, display_name, org_id, "
                        + "is_platform_admin, must_change_password, status, created_by) VALUES (?, ?, ?, ?, FALSE, TRUE, 'ACTIVE', ?)",
                username, hash, displayName, orgId, createdBy);
    }

    public Long findUserIdByUsername(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    public void updateUserPassword(long userId, String hash, Timestamp ts) {
        jdbc.update("UPDATE sys_user SET password_hash = ?, must_change_password = TRUE, updated_at = ? WHERE id = ?",
                hash, ts, userId);
    }

    public void deleteUserRoles(long userId) {
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
    }

    public void insertUserRole(long userId, long roleId) {
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
    }

    public void updateUserStatus(long userId, String status, Timestamp ts) {
        jdbc.update("UPDATE sys_user SET status = ?, updated_at = ? WHERE id = ?", status, ts, userId);
    }

    // ---------- 角色 ----------

    public List<Map<String, Object>> listRoles() {
        return jdbc.queryForList("SELECT id, code, name, description, data_scope, is_built_in FROM sys_role ORDER BY id");
    }

    public List<String> findMenuCodesByRole(long roleId) {
        return jdbc.queryForList(
                "SELECT m.code FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id = m.id "
                        + "WHERE rm.role_id = ? ORDER BY m.sort_no", String.class, roleId);
    }

    public Integer countRoleByCode(String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_role WHERE code = ?", Integer.class, code);
    }

    public void insertRole(String code, String name, String description, String dataScope) {
        jdbc.update("INSERT INTO sys_role (code, name, description, data_scope, is_built_in) VALUES (?, ?, ?, ?, FALSE)",
                code, name, description, dataScope);
    }

    public Long findRoleIdByCode(String code) {
        return jdbc.queryForObject("SELECT id FROM sys_role WHERE code = ?", Long.class, code);
    }

    public void deleteRoleMenus(long roleId) {
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id = ?", roleId);
    }

    public void insertRoleMenu(long roleId, long menuId) {
        jdbc.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
    }

    public void updateRoleDataScope(long roleId, String dataScope) {
        jdbc.update("UPDATE sys_role SET data_scope = ? WHERE id = ?", dataScope, roleId);
    }

    // ---------- 菜单 / 组织 ----------

    public List<Map<String, Object>> listMenus() {
        return jdbc.queryForList("SELECT id, code, name, path, sort_no, menu_type, status FROM sys_menu ORDER BY sort_no");
    }

    public List<Map<String, Object>> listOrgs() {
        return jdbc.queryForList("SELECT id, name, parent_id, path FROM sys_org WHERE status = 'ACTIVE' ORDER BY sort_no, id");
    }
}