package io.xuanji.server.admin;

import io.xuanji.server.auth.AuthInterceptor;
import io.xuanji.server.auth.LoginUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * RBAC 管理端点（仅平台管理员，Q13：平台管理员 bypass 全量留审计）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RbacService rbac;

    public AdminController(RbacService rbac) {
        this.rbac = rbac;
    }

    public record CreateUserRequest(String username, String displayName, Long orgId, List<Long> roleIds, String password) {}
    public record AssignRolesRequest(List<Long> roleIds) {}
    public record UpdateStatusRequest(String status) {}
    public record CreateRoleRequest(String code, String name, String description, String dataScope, List<Long> menuIds) {}
    public record RoleMenusRequest(List<Long> menuIds) {}
    public record DataScopeRequest(String dataScope) {}

    // ---------- 用户 ----------

    @GetMapping("/users")
    public List<Map<String, Object>> users(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return rbac.listUsers();
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody CreateUserRequest req, HttpServletRequest request) {
        requirePlatformAdmin(request);
        return rbac.createUser(req.username(), req.displayName(), req.orgId(), req.roleIds(), req.password());
    }

    @PostMapping("/users/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable long id, HttpServletRequest request) {
        requirePlatformAdmin(request);
        return rbac.resetPassword(id);
    }

    @PutMapping("/users/{id}/roles")
    public Map<String, Object> assignRoles(@PathVariable long id, @RequestBody AssignRolesRequest req,
                                           HttpServletRequest request) {
        requirePlatformAdmin(request);
        rbac.assignRoles(id, req.roleIds());
        return Map.of("ok", true);
    }

    @PutMapping("/users/{id}/status")
    public Map<String, Object> updateStatus(@PathVariable long id, @RequestBody UpdateStatusRequest req,
                                            HttpServletRequest request) {
        requirePlatformAdmin(request);
        rbac.updateStatus(id, req.status());
        return Map.of("ok", true);
    }

    // ---------- 角色 ----------

    @GetMapping("/roles")
    public List<Map<String, Object>> roles(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return rbac.listRoles();
    }

    @PostMapping("/roles")
    public Map<String, Object> createRole(@RequestBody CreateRoleRequest req, HttpServletRequest request) {
        requirePlatformAdmin(request);
        return rbac.createRole(req.code(), req.name(), req.description(), req.dataScope(), req.menuIds());
    }

    @PutMapping("/roles/{id}/menus")
    public Map<String, Object> updateRoleMenus(@PathVariable long id, @RequestBody RoleMenusRequest req,
                                               HttpServletRequest request) {
        requirePlatformAdmin(request);
        rbac.updateRoleMenus(id, req.menuIds());
        return Map.of("ok", true);
    }

    @PutMapping("/roles/{id}/data-scope")
    public Map<String, Object> updateDataScope(@PathVariable long id, @RequestBody DataScopeRequest req,
                                               HttpServletRequest request) {
        requirePlatformAdmin(request);
        rbac.updateDataScope(id, req.dataScope());
        return Map.of("ok", true);
    }

    // ---------- 菜单 / 组织 ----------

    @GetMapping("/menus")
    public List<Map<String, Object>> menus(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return rbac.listMenus();
    }

    @GetMapping("/orgs")
    public List<Map<String, Object>> orgs(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return rbac.listOrgs();
    }

    private void requirePlatformAdmin(HttpServletRequest request) {
        LoginUser user = (LoginUser) request.getAttribute(AuthInterceptor.ATTR_LOGIN_USER);
        if (user == null || !user.isPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅平台管理员可操作");
        }
    }
}
