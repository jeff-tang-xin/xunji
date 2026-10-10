package io.xuanji.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 认证域数据访问（sys_user / sys_role / sys_menu）：SQL 从 {@code AuthService} 抽离。
 * 密码校验（BCrypt）、LoginUser 组装、多角色取最宽档、菜单视图拼装等业务仍留 Service。
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findUserForLogin(String username) {
        return jdbc.queryForList(
                "SELECT id, username, password_hash, display_name, org_id, is_platform_admin, "
                        + "must_change_password FROM sys_user WHERE username = ? AND status = 'ACTIVE'",
                username == null ? "" : username);
    }

    public void updateLastLogin(long userId, Timestamp ts) {
        jdbc.update("UPDATE sys_user SET last_login_at = ? WHERE id = ?", ts, userId);
    }

    public String findPasswordHash(long userId) {
        return jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id = ?", String.class, userId);
    }

    public void updatePassword(long userId, String hash, Timestamp ts) {
        jdbc.update("UPDATE sys_user SET password_hash = ?, must_change_password = FALSE, updated_at = ? WHERE id = ?",
                hash, ts, userId);
    }

    public List<String> findDataScopes(long userId) {
        return jdbc.queryForList(
                "SELECT DISTINCT r.data_scope FROM sys_role r "
                        + "JOIN sys_user_role ur ON ur.role_id = r.id WHERE ur.user_id = ?",
                String.class, userId);
    }

    public List<String> findAllActiveMenuCodes() {
        return jdbc.queryForList("SELECT code FROM sys_menu WHERE status = 'ACTIVE'", String.class);
    }

    public List<String> findMenuCodesByUser(long userId) {
        return jdbc.queryForList(
                "SELECT DISTINCT m.code FROM sys_menu m "
                        + "JOIN sys_role_menu rm ON rm.menu_id = m.id "
                        + "JOIN sys_user_role ur ON ur.role_id = rm.role_id "
                        + "WHERE ur.user_id = ? AND m.status = 'ACTIVE'", String.class, userId);
    }

    public List<Map<String, Object>> findMenusForAdmin() {
        return jdbc.queryForList(
                "SELECT code, name, path FROM sys_menu WHERE status = 'ACTIVE' ORDER BY sort_no");
    }

    public List<Map<String, Object>> findMenusForUser(long userId) {
        return jdbc.queryForList(
                "SELECT DISTINCT m.code, m.name, m.path, m.sort_no FROM sys_menu m "
                        + "JOIN sys_role_menu rm ON rm.menu_id = m.id "
                        + "JOIN sys_user_role ur ON ur.role_id = rm.role_id "
                        + "WHERE ur.user_id = ? AND m.status = 'ACTIVE' ORDER BY m.sort_no", userId);
    }
}