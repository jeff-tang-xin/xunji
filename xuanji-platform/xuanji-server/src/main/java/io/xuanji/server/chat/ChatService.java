package io.xuanji.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.core.runtime.AgentHandle;
import io.xuanji.core.runtime.AgentMessage;
import io.xuanji.core.runtime.AgentRuntime;
import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.core.runtime.AgentStreamEvent;
import io.xuanji.server.asset.AppService;
import io.xuanji.server.engine.AgentNodeExecutor;
import io.xuanji.server.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话式执行（chat SSE）：App 开放端（AppKey）与工作区（管理台 token）共用的
 * Agent 流式执行核心。SSE 事件名与 detail/05 §2 工作区对话流对齐：
 * agent.start / agent.delta / agent.result / agent.end / error。
 *
 * <p>执行模型：SseEmitter 异步——虚拟线程内跑 AgentRuntime.stream（阻塞至流结束），
 * 逐事件 emitter.send；完成/超时/错误统一回收。多轮记忆：会话标识按入口生成
 * （工作区=ws-{id}，App=请求体 sessionId 缺省 app-{id}），AgentSpec.sessionId
 * 非空时 AgentScope stateStore 按 (agentId, sessionId) 恢复/持久化上下文。</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AgentRuntime runtime;
    private final AgentNodeExecutor agentNodes;
    private final AppService apps;
    private final WorkspaceService workspaces;
    private final ObjectMapper objectMapper;

    /** 虚拟线程池：SSE 长连接执行载体（JDK21） */
    private final ExecutorService chatExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(AgentRuntime runtime, AgentNodeExecutor agentNodes,
                       AppService apps, WorkspaceService workspaces, ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.agentNodes = agentNodes;
        this.apps = apps;
        this.workspaces = workspaces;
        this.objectMapper = objectMapper;
    }

    /* ---------- 入口 ---------- */

    /** App 开放端对话：AppKey 已由 Controller 校验（scope=chat），绑定资产必须是 AGENT */
    public SseEmitter chatByApp(String appId, Map<String, Object> appCtx, String query, String sessionId) {
        if (!"AGENT".equals(appCtx.get("bound_asset_type"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chat 仅支持 AGENT 绑定的 App（当前绑定 "
                    + appCtx.get("bound_asset_type") + "，走 runs 端点）");
        }
        long agentId = ((Number) appCtx.get("bound_asset_id")).longValue();
        int version = ((Number) appCtx.get("bound_asset_version")).intValue();
        // 会话标识：第三方经请求体 sessionId 管理自己的多轮会话，缺省按 App 维度
        String session = sessionId == null || sessionId.isBlank() ? "app-" + appId : sessionId;
        return startChat(agentNodes.buildSpec(agentId, version, session), query,
                "app=" + appId + " agent=" + agentId + "@" + version + " session=" + session);
    }

    /** 工作区对话：AGENT 绑定（WORKFLOW 绑定走 /runs）；会话按工作区维度（同工作区共享上下文） */
    public SseEmitter chatByWorkspace(io.xuanji.server.auth.LoginUser user, String wsId, String query) {
        Map<String, Object> ws = workspaces.get(user, wsId);
        if (!"AGENT".equals(ws.get("binding_type"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该工作区绑定的是 WORKFLOW，请用「发起执行」（POST /api/workspaces/{id}/runs）");
        }
        long agentId = ((Number) ws.get("bound_asset_id")).longValue();
        int version = ((Number) ws.get("bound_asset_version")).intValue();
        String session = "ws-" + wsId;
        return startChat(agentNodes.buildSpec(agentId, version, session), query,
                "workspace=" + wsId + " agent=" + agentId + "@" + version + " session=" + session);
    }

    /* ---------- 核心：异步流式执行 ---------- */

    private SseEmitter startChat(AgentSpec spec, String query, String audit) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        SseEmitter emitter = new SseEmitter(120_000L); // 单轮上限 2 分钟
        chatExecutor.submit(() -> {
            AgentHandle handle = null;
            try {
                handle = runtime.createAgent(spec);
                send(emitter, "agent.start", Map.of("agent", spec.name()));
                runtime.stream(handle, new AgentMessage(query, null), evt ->
                        send(emitter, evt.type(), payloadOf(evt)));
                send(emitter, "stage.finish", Map.of("status", "SUCCESS"));
                emitter.complete();
                log.info("chat finished: {}", audit);
            } catch (Exception e) {
                log.error("chat failed: {} -> {}", audit, e.toString());
                send(emitter, "error", Map.of("message", e.getMessage() == null ? e.toString() : e.getMessage()));
                send(emitter, "stage.finish", Map.of("status", "FAILED"));
                emitter.completeWithError(e);
            } finally {
                if (handle != null) {
                    try {
                        runtime.close(handle);
                    } catch (Exception ignore) {
                        // 释放失败不影响响应
                    }
                }
            }
        });
        return emitter;
    }

    private Map<String, Object> payloadOf(AgentStreamEvent evt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (evt.delta() != null) {
            payload.put("delta", evt.delta());
        }
        if (evt.text() != null) {
            payload.put("text", evt.text());
        }
        if (evt.meta() != null && !evt.meta().isEmpty()) {
            payload.putAll(evt.meta());
        }
        return payload;
    }

    private void send(SseEmitter emitter, String event, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(toJson(payload), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("sse send failed (client gone?): {}", e.toString());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
