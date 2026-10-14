package io.xuanji.server.asset;

import io.xuanji.server.auth.LoginUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 资产管理端点（docs/detail/05 §2 约定形状）：
 * CRUD /api/agents /api/tools /api/skills /api/mcp /api/model-providers
 * + 各自 POST /{id}/publish + POST /api/mcp/{id}/discover + POST /api/model-providers/{id}/test。
 *
 * <p>list 走数据权限过滤（Q13）；写操作校验归属（管理员或创建者本人）。
 * LoginUser 由 AuthInterceptor 以 request attribute 注入。</p>
 */
@RestController
@RequestMapping("/api")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    // ---------- 通用五组端点（路径即资产类型） ----------

    @GetMapping("/agents")
    public List<Map<String, Object>> listAgents(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.list("agents", user);
    }

    @PostMapping("/agents")
    public Map<String, Object> createAgent(@RequestBody Map<String, Object> body,
                                           @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return Map.of("id", assetService.create("agents", body, user));
    }

    @GetMapping("/agents/{id}")
    public Map<String, Object> getAgent(@PathVariable long id,
                                        @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.get("agents", id, user);
    }

    @PutMapping("/agents/{id}")
    public Map<String, Object> updateAgent(@PathVariable long id, @RequestBody Map<String, Object> body,
                                           @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.update("agents", id, body, user);
        return Map.of("ok", true);
    }

    @DeleteMapping("/agents/{id}")
    public Map<String, Object> deleteAgent(@PathVariable long id,
                                           @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.delete("agents", id, user);
        return Map.of("ok", true);
    }

    @PostMapping("/agents/{id}/publish")
    public Map<String, Object> publishAgent(@PathVariable long id,
                                            @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.publish("agents", id, user);
    }

    // ---------- tools ----------

    @GetMapping("/tools")
    public List<Map<String, Object>> listTools(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.list("tools", user);
    }

    @PostMapping("/tools")
    public Map<String, Object> createTool(@RequestBody Map<String, Object> body,
                                          @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return Map.of("id", assetService.create("tools", body, user));
    }

    @GetMapping("/tools/{id}")
    public Map<String, Object> getTool(@PathVariable long id,
                                       @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.get("tools", id, user);
    }

    @PutMapping("/tools/{id}")
    public Map<String, Object> updateTool(@PathVariable long id, @RequestBody Map<String, Object> body,
                                          @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.update("tools", id, body, user);
        return Map.of("ok", true);
    }

    @DeleteMapping("/tools/{id}")
    public Map<String, Object> deleteTool(@PathVariable long id,
                                          @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.delete("tools", id, user);
        return Map.of("ok", true);
    }

    @PostMapping("/tools/{id}/publish")
    public Map<String, Object> publishTool(@PathVariable long id,
                                           @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.publish("tools", id, user);
    }

    // ---------- skills ----------

    @GetMapping("/skills")
    public List<Map<String, Object>> listSkills(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.list("skills", user);
    }

    @PostMapping("/skills")
    public Map<String, Object> createSkill(@RequestBody Map<String, Object> body,
                                           @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return Map.of("id", assetService.create("skills", body, user));
    }

    @GetMapping("/skills/{id}")
    public Map<String, Object> getSkill(@PathVariable long id,
                                        @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.get("skills", id, user);
    }

    @PutMapping("/skills/{id}")
    public Map<String, Object> updateSkill(@PathVariable long id, @RequestBody Map<String, Object> body,
                                           @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.update("skills", id, body, user);
        return Map.of("ok", true);
    }

    @DeleteMapping("/skills/{id}")
    public Map<String, Object> deleteSkill(@PathVariable long id,
                                           @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.delete("skills", id, user);
        return Map.of("ok", true);
    }

    @PostMapping("/skills/{id}/publish")
    public Map<String, Object> publishSkill(@PathVariable long id,
                                            @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.publish("skills", id, user);
    }

    // ---------- mcp ----------

    @GetMapping("/mcp")
    public List<Map<String, Object>> listMcp(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.list("mcp", user);
    }

    @PostMapping("/mcp")
    public Map<String, Object> createMcp(@RequestBody Map<String, Object> body,
                                         @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return Map.of("id", assetService.create("mcp", body, user));
    }

    @GetMapping("/mcp/{id}")
    public Map<String, Object> getMcp(@PathVariable long id,
                                      @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.get("mcp", id, user);
    }

    @PutMapping("/mcp/{id}")
    public Map<String, Object> updateMcp(@PathVariable long id, @RequestBody Map<String, Object> body,
                                         @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.update("mcp", id, body, user);
        return Map.of("ok", true);
    }

    @DeleteMapping("/mcp/{id}")
    public Map<String, Object> deleteMcp(@PathVariable long id,
                                         @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.delete("mcp", id, user);
        return Map.of("ok", true);
    }

    @PostMapping("/mcp/{id}/publish")
    public Map<String, Object> publishMcp(@PathVariable long id,
                                          @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.publish("mcp", id, user);
    }

    @PostMapping("/mcp/{id}/discover")
    public Map<String, Object> discoverMcp(@PathVariable long id,
                                           @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.requireOwner("mcp", id, user);
        return assetService.discoverMcp(id);
    }

    // ---------- model-providers ----------

    @GetMapping("/model-providers")
    public List<Map<String, Object>> listModelProviders(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.list("model-providers", user);
    }

    @PostMapping("/model-providers")
    public Map<String, Object> createModelProvider(@RequestBody Map<String, Object> body,
                                                   @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return Map.of("id", assetService.create("model-providers", body, user));
    }

    @GetMapping("/model-providers/{id}")
    public Map<String, Object> getModelProvider(@PathVariable long id,
                                                @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.get("model-providers", id, user);
    }

    @PutMapping("/model-providers/{id}")
    public Map<String, Object> updateModelProvider(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                   @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.update("model-providers", id, body, user);
        return Map.of("ok", true);
    }

    @DeleteMapping("/model-providers/{id}")
    public Map<String, Object> deleteModelProvider(@PathVariable long id,
                                                   @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.delete("model-providers", id, user);
        return Map.of("ok", true);
    }

    @PostMapping("/model-providers/{id}/publish")
    public Map<String, Object> publishModelProvider(@PathVariable long id,
                                                    @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return assetService.publish("model-providers", id, user);
    }

    @PostMapping("/model-providers/{id}/test")
    public Map<String, Object> testModelProvider(@PathVariable long id,
                                                 @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        assetService.requireOwner("model-providers", id, user);
        return assetService.testModelProvider(id);
    }
}
