package io.xuanji.server.workspace;

import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.chat.ChatService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工作区端点（主文档 4.6 / detail 05 §2）：管理台 token 鉴权。
 * GET /api/workspaces 列表（数据权限过滤）｜ POST 创建（绑定已发布资产钉版本）
 * ｜ GET/PUT/DELETE 单个 ｜ POST /{id}/runs 从工作区发起执行（WORKFLOW 绑定）。
 * GET /{id}/stream（SSE 事件流）随 chat-messages 一并落地。
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaces;
    private final ChatService chatService;

    public WorkspaceController(WorkspaceService workspaces, ChatService chatService) {
        this.workspaces = workspaces;
        this.chatService = chatService;
    }

    @GetMapping
    public Object list(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return workspaces.list(user);
    }

    @PostMapping
    public Object create(@RequestAttribute(LoginUser.ATTR) LoginUser user, @RequestBody Map<String, Object> body) {
        return workspaces.create(user, body);
    }

    @GetMapping("/{id}")
    public Object get(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return workspaces.get(user, id);
    }

    @PutMapping("/{id}")
    public Object update(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                         @PathVariable String id,
                         @RequestBody Map<String, Object> body) {
        return workspaces.update(user, id, body);
    }

    @DeleteMapping("/{id}")
    public Object delete(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        workspaces.delete(user, id);
        return Map.of("deleted", id);
    }

    /** 从工作区发起执行：body.input 为流程输入（START 节点 schema） */
    @PostMapping("/{id}/runs")
    public Object createRun(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                            @PathVariable String id,
                            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = body == null ? Map.of() : (Map<String, Object>) body.getOrDefault("input", Map.of());
        return workspaces.createRun(user, id, input);
    }

    /** 工作区对话（SSE）：AGENT 绑定流式执行（WORKFLOW 绑定 400 提示走 /runs） */
    @PostMapping(value = "/{id}/chat", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter chat(
            @RequestAttribute(LoginUser.ATTR) LoginUser user,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String query = body == null || body.get("query") == null ? null : body.get("query").toString();
        return chatService.chatByWorkspace(user, id, query);
    }
}
