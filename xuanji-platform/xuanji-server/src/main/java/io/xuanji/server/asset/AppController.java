package io.xuanji.server.asset;

import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.chat.ChatService;
import io.xuanji.server.engine.RunService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 应用层（主文档 4.9）：管理端 /api/apps/**（管理台 token 鉴权）+
 * 开放端 /api/v1/apps/{appId}/**（AppKey Bearer 鉴权，独立鉴权域）。
 *
 * <p>开放端点四件套：runs 触发 / runs 查询 / chat-messages（SSE，AgentRuntime 未实装 → 501）/
 * resume（S2 分段执行未实装 → 501）。每次调用回写 wf_run.app_id（appkey → app → run 审计链路）。</p>
 */
@RestController
@RequestMapping
public class AppController {

    private final AppService apps;
    private final RunService runs;
    private final ChatService chatService;

    public AppController(AppService apps, RunService runs, ChatService chatService) {
        this.apps = apps;
        this.runs = runs;
        this.chatService = chatService;
    }

    /* ==================== 管理端（管理台 token） ==================== */

    @GetMapping("/api/apps")
    public Object list(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return apps.list(user);
    }

    @PostMapping("/api/apps")
    public Object create(@RequestAttribute(LoginUser.ATTR) LoginUser user, @RequestBody Map<String, Object> body) {
        return apps.create(user, body);
    }

    @GetMapping("/api/apps/{id}")
    public Object get(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return apps.get(user, id);
    }

    @PutMapping("/api/apps/{id}")
    public Object update(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id,
                         @RequestBody Map<String, Object> body) {
        apps.update(user, id, body);
        return Map.of("ok", true);
    }

    @DeleteMapping("/api/apps/{id}")
    public Object delete(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        apps.delete(user, id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/apps/{id}/publish")
    public Object publish(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return apps.publish(user, id);
    }

    @PostMapping("/api/apps/{id}/disable")
    public Object disable(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        apps.disable(user, id);
        return Map.of("ok", true);
    }

    @GetMapping("/api/apps/{id}/credentials")
    public Object credentials(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return apps.listCredentials(user, id);
    }

    /** 签发 AppKey：响应中的明文 key 仅此一次展示 */
    @PostMapping("/api/apps/{id}/credentials")
    public Object createCredential(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return apps.createCredential(user, id, body == null ? Map.of() : body);
    }

    @PostMapping("/api/apps/{id}/credentials/{keyId}/rotate")
    public Object rotate(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id,
                         @PathVariable long keyId) {
        return apps.rotate(user, id, keyId);
    }

    @DeleteMapping("/api/apps/{id}/credentials/{keyId}")
    public Object disableCredential(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id,
                                    @PathVariable long keyId) {
        apps.disableCredential(user, id, keyId);
        return Map.of("ok", true);
    }

    /* ==================== 开放端（AppKey Bearer） ==================== */

    /** 触发 run：按 App 绑定的资产与钉住版本执行（WORKFLOW）；AGENT 绑定待 AgentRuntime（501） */
    @PostMapping("/api/v1/apps/{appId}/runs")
    public Object createRun(@PathVariable String appId,
                            @RequestHeader(value = "Authorization", required = false) String auth,
                            @RequestBody(required = false) Map<String, Object> body) {
        var ctx = apps.authorize(bearer(auth), appId, "runs");
        if (!"WORKFLOW".equals(ctx.get("bound_asset_type"))) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "AGENT 绑定的 App 执行依赖 AgentRuntime（阶段 3+ 实装）");
        }
        long definitionId = ((Number) ctx.get("bound_asset_id")).longValue();
        Integer version = (Integer) ctx.get("bound_asset_version");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = body == null ? Map.of() : (Map<String, Object>) body.getOrDefault("input", Map.of());
        Map<String, Object> result = runs.createRun(definitionId, version, input, "APP");
        // 审计链路：appkey → app → run（wf_run.app_id 为 VARCHAR(32)，V1 基线）——归属回写下沉 RunService
        runs.attachApp((String) result.get("runId"), appId);
        return result;
    }

    /** 查询 run：仅允许查询本 App 产生的 run（wf_run.app_id 审计链路比对，比 definition_id 更严格） */
    @GetMapping("/api/v1/apps/{appId}/runs/{runId}")
    public Object getRun(@PathVariable String appId, @PathVariable String runId,
                         @RequestHeader(value = "Authorization", required = false) String auth) {
        apps.authorize(bearer(auth), appId, "runs");
        Map<String, Object> run = runs.getRun(runId);
        Object runAppId = run.get("app_id");
        if (runAppId == null || !appId.equals(runAppId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "run 不属于该 App");
        }
        run.remove("context_snapshot");
        return run;
    }

    /** HITL 对外恢复：依赖 SUSPENDED 分段执行（S2 spike），未实装 */
    @PostMapping("/api/v1/apps/{appId}/runs/{runId}/resume")
    public Object resume(@PathVariable String appId, @PathVariable String runId,
                         @RequestHeader(value = "Authorization", required = false) String auth) {
        apps.authorize(bearer(auth), appId, "resume");
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "resume 依赖决策门分段执行（S2 spike），未实装");
    }

    /** 对话式调用（SSE）：AppKey 鉴权（scope=chat）→ AGENT 绑定资产流式执行；sessionId 可选（多轮会话） */
    @PostMapping("/api/v1/apps/{appId}/chat-messages")
    public Object chat(@PathVariable String appId,
                       @RequestHeader(value = "Authorization", required = false) String auth,
                       @RequestBody(required = false) Map<String, Object> body) {
        var ctx = apps.authorize(bearer(auth), appId, "chat");
        String query = body == null || body.get("query") == null ? null : body.get("query").toString();
        String sessionId = body == null || body.get("sessionId") == null ? null : body.get("sessionId").toString();
        return chatService.chatByApp(appId, ctx, query, sessionId);
    }

    private String bearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
