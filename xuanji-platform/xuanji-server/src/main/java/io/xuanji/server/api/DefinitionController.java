package io.xuanji.server.api;

import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.engine.DefinitionService;
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

/** 定义管理：草稿保存 / 发布 / 查询（发布中心数据源）；list 走数据权限过滤（Q13） */
@RestController
@RequestMapping("/api/definitions")
public class DefinitionController {

    private final DefinitionService definitionService;

    public DefinitionController(DefinitionService definitionService) {
        this.definitionService = definitionService;
    }

    public record CreateRequest(String name, String description, String canvasJson) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest req,
                                      @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        long id = definitionService.create(req.name(), req.description(), req.canvasJson(), user);
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody CreateRequest req,
                                      @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        definitionService.update(id, req.name(), req.description(), req.canvasJson(), user);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id,
                                      @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        definitionService.delete(id, user);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/publish")
    public Map<String, Object> publish(@PathVariable long id) {
        return definitionService.publish(id);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return definitionService.get(id);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return definitionService.list(user);
    }
}
