package io.xuanji.server.auth;

import io.xuanji.server.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证服务（A2 分层整改）：登录 / 登出 / 改密 / 当前用户视图的全部 SQL 与业务从
 * {@link AuthController} 下沉至此。Controller 只做 HTTP 绑定（解析请求体、取拦截器注入的
 * {@link LoginUser}、提取 Bearer），不再直接持有 JdbcTemplate——符合
 * Controller→Service→DB 的分层铁律。
 */
@Service
public class AuthService {

    private final UserRepository repo;
    private final TokenService tokenService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository repo, TokenService tokenService) {
        this.repo = repo;
        this.tokenService = tokenService;
    }

    /** 登录：校验用户名密码 → 记 last_login → 签发 token，返回 {token, user} 响应体。 */
    public Map<String, Object> login(String username, String password) {
        List<Map<String, Object>> rows = repo.findUserForLogin(username);
        if (rows.isEmpty() || !encoder.matches(password == null ? "" : password,
                (String) rows.get(0).get("password_hash"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        Map<String, Object> u = rows.get(0);
        long userId = ((Number) u.get("id")).longValue();
        repo.updateLastLogin(userId, Timestamp.from(Instant.now()));

        LoginUser user = new LoginUser(userId, (String) u.get("username"),
                (String) u.get("display_name"),
                u.get("org_id") == null ? null : ((Number) u.get("org_id")).longValue(),
                (Boolean) u.get("is_platform_admin"),
                (Boolean) u.get("must_change_password"),
                dataScopeOf(userId, (Boolean) u.get("is_platform_admin")),
                menuCodesOf(userId, (Boolean) u.get("is_platform_admin")));
        String token = tokenService.create(user);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("user", userView(user));
        return body;
    }

    /** 登出：撤销内存 token。 */
    public void logout(String token) {
        tokenService.remove(token);
    }

    /** 改密：校验原密码 → 强度校验 → 更新 hash 并清除 must_change_password。 */
    public void changePassword(long userId, String oldPassword, String newPassword) {
        String currentHash = repo.findPasswordHash(userId);
        if (currentHash == null || !encoder.matches(oldPassword == null ? "" : oldPassword, currentHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码不正确");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码至少 8 位");
        }
        repo.updatePassword(userId, encoder.encode(newPassword), Timestamp.from(Instant.now()));
    }

    /** 当前用户视图（含按角色聚合的菜单）。 */
    public Map<String, Object> userView(LoginUser user) {
        List<Map<String, Object>> menus = new ArrayList<>();
        List<Map<String, Object>> rows = user.isPlatformAdmin()
                ? repo.findMenusForAdmin() : repo.findMenusForUser(user.userId());
        for (Map<String, Object> r : rows) {
            menus.add(Map.of("code", r.get("code"), "name", r.get("name"), "path", r.get("path")));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", user.userId());
        view.put("username", user.username());
        view.put("displayName", user.displayName());
        view.put("isPlatformAdmin", user.isPlatformAdmin());
        view.put("mustChangePassword", user.mustChangePassword());
        view.put("dataScope", user.dataScope());
        view.put("menus", menus);
        return view;
    }

    // ---------- 内部 ----------

    private String dataScopeOf(long userId, boolean isPlatformAdmin) {
        if (isPlatformAdmin) {
            return "ALL"; // bypass 语义：不受组织与数据权限控制
        }
        List<String> scopes = repo.findDataScopes(userId);
        // 多角色取最宽档（P0 简化：ALL > UNDER_ORG > ORG > ONLY_CREATE）
        return scopes.contains("ALL") ? "ALL"
                : scopes.contains("UNDER_ORG") ? "UNDER_ORG"
                : scopes.contains("ORG") ? "ORG"
                : scopes.isEmpty() ? "ONLY_CREATE" : scopes.get(0);
    }

    private Set<String> menuCodesOf(long userId, boolean isPlatformAdmin) {
        if (isPlatformAdmin) {
            return new HashSet<>(repo.findAllActiveMenuCodes());
        }
        return new HashSet<>(repo.findMenuCodesByUser(userId));
    }
}