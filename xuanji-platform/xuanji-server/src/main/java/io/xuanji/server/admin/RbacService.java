package io.xuanji.server.admin;

import io.xuanji.server.repository.RbacRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RBAC 管理域：用户 / 角色 / 菜单 / 组织（P0：管理台用户仅由平台管理员创建）。
 *
 * <p>密码策略：创建与重置均生成随机密码，明文仅一次性返回给管理员展示，
 * 库内只存 BCrypt 哈希；must_change_password 置位强制首次登录改密。</p>
 */
@Service
public class RbacService {

    private static final String PASSWORD_CHARS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final RbacRepository repo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public RbacService(RbacRepository repo) {
        this.repo = repo;
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ---------- 用户 ----------

    public List<Map<String, Object>> listUsers() {
        List<Map<String, Object>> users = repo.listUsers();
        for (Map<String, Object> u : users) {
            long id = ((Number) u.get("id")).longValue();
            u.put("roles", repo.findRoleNamesByUser(id));
            u.put("roleIds", repo.findRoleIdsByUser(id));
        }
        return users;
    }

    /** 返回明文密码（一次性展示） */
    @Transactional
    public Map<String, Object> createUser(String username, String displayName, Long orgId,
                                          List<Long> roleIds, String rawPassword) {
        Integer dup = repo.countUserByUsername(username);
        if (dup != null && dup > 0) {
            throw bad("用户名已存在: " + username);
        }
        String password = rawPassword != null && !rawPassword.isBlank() ? rawPassword : randomPassword();
        repo.insertUser(username, encoder.encode(password), displayName, orgId, "admin");
        Long userId = repo.findUserIdByUsername(username);
        assignRoles(userId, roleIds);
        return Map.of("id", userId, "username", username, "initialPassword", password);
    }

    /** 管理员重置密码：明文一次性返回，强制首次登录改密 */
    @Transactional
    public Map<String, Object> resetPassword(long userId) {
        String password = randomPassword();
        repo.updateUserPassword(userId, encoder.encode(password), Timestamp.from(Instant.now()));
        return Map.of("id", userId, "initialPassword", password);
    }

    @Transactional
    public void assignRoles(long userId, List<Long> roleIds) {
        repo.deleteUserRoles(userId);
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                repo.insertUserRole(userId, roleId);
            }
        }
    }

    @Transactional
    public void updateStatus(long userId, String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw bad("非法状态: " + status);
        }
        repo.updateUserStatus(userId, status, Timestamp.from(Instant.now()));
    }

    // ---------- 角色 ----------

    public List<Map<String, Object>> listRoles() {
        List<Map<String, Object>> roles = repo.listRoles();
        for (Map<String, Object> r : roles) {
            long id = ((Number) r.get("id")).longValue();
            r.put("menuCodes", repo.findMenuCodesByRole(id));
        }
        return roles;
    }

    @Transactional
    public Map<String, Object> createRole(String code, String name, String description,
                                          String dataScope, List<Long> menuIds) {
        Integer dup = repo.countRoleByCode(code);
        if (dup != null && dup > 0) {
            throw bad("角色编码已存在: " + code);
        }
        repo.insertRole(code, name, description, dataScope == null ? "ONLY_CREATE" : dataScope);
        Long id = repo.findRoleIdByCode(code);
        updateRoleMenus(id, menuIds);
        return Map.of("id", id);
    }

    @Transactional
    public void updateRoleMenus(long roleId, List<Long> menuIds) {
        repo.deleteRoleMenus(roleId);
        if (menuIds != null) {
            for (Long menuId : menuIds) {
                repo.insertRoleMenu(roleId, menuId);
            }
        }
    }

    @Transactional
    public void updateDataScope(long roleId, String dataScope) {
        if (!List.of("ALL", "UNDER_ORG", "ORG", "ONLY_CREATE").contains(dataScope)) {
            throw bad("非法数据权限档位: " + dataScope);
        }
        repo.updateRoleDataScope(roleId, dataScope);
    }

    // ---------- 菜单 / 组织 ----------

    public List<Map<String, Object>> listMenus() {
        return repo.listMenus();
    }

    public List<Map<String, Object>> listOrgs() {
        return repo.listOrgs();
    }

    // ---------- 内部 ----------

    private String randomPassword() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
