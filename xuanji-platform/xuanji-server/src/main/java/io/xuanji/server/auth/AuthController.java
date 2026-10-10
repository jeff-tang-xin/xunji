package io.xuanji.server.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理台认证：登录 / 登出 / 当前用户 / 首次改密。
 *
 * <p>登录返回 token + 用户信息 + 按角色聚合的菜单（platform_admin 返回全部菜单）。
 * must_change_password=true 时前端强制进入改密流程，改密成功后须重新登录
 * （会话内 mustChangePassword 为登录时快照，不热更新）。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(String username, String password) {}

    public record ChangePasswordRequest(String oldPassword, String newPassword) {}

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        return authService.login(req.username(), req.password());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(jakarta.servlet.http.HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        authService.logout(header != null && header.startsWith("Bearer ") ? header.substring(7) : null);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(jakarta.servlet.http.HttpServletRequest request) {
        LoginUser user = (LoginUser) request.getAttribute(AuthInterceptor.ATTR_LOGIN_USER);
        return authService.userView(user);
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody ChangePasswordRequest req,
                                              jakarta.servlet.http.HttpServletRequest request) {
        LoginUser user = (LoginUser) request.getAttribute(AuthInterceptor.ATTR_LOGIN_USER);
        authService.changePassword(user.userId(), req.oldPassword(), req.newPassword());
        return Map.of("ok", true);
    }
}
