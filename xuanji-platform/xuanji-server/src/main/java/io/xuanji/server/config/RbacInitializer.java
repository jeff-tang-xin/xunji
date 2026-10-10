package io.xuanji.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * RBAC 初始化（幂等）：空用户表时创建初始管理员 admin。
 *
 * <p>初始密码取环境变量 XUANJI_ADMIN_PASSWORD（未配置则默认 Admin@123），
 * 仅 BCrypt 哈希落库，明文不进任何文件与日志；must_change_password 置位，
 * 首次登录强制改密。platform_admin 角色补全全部菜单绑定。</p>
 */
@Component
public class RbacInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RbacInitializer.class);

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public RbacInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        // platform_admin 绑全部菜单（幂等：缺哪个补哪个）
        jdbc.update("INSERT INTO sys_role_menu (role_id, menu_id) "
                + "SELECT r.id, m.id FROM sys_role r, sys_menu m "
                + "WHERE r.code = 'platform_admin' AND m.status = 'ACTIVE' "
                + "AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id)");

        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class);
        if (userCount != null && userCount > 0) {
            return;
        }
        String password = System.getenv("XUANJI_ADMIN_PASSWORD");
        if (password == null || password.isBlank()) {
            password = "Admin@123";
        }
        Long adminRoleId = jdbc.queryForObject(
                "SELECT id FROM sys_role WHERE code = 'platform_admin'", Long.class);
        jdbc.update("INSERT INTO sys_user (username, password_hash, display_name, is_platform_admin, "
                        + "must_change_password, status, created_by) VALUES (?, ?, ?, TRUE, TRUE, 'ACTIVE', 'system')",
                "admin", encoder.encode(password), "平台管理员");
        Long adminId = jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", adminId, adminRoleId);
        log.info("initial admin created: username=admin (password from XUANJI_ADMIN_PASSWORD or default), must change password on first login");
    }
}
