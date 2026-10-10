package io.xuanji.server.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * run 事件总线（内存 SSE 通道）：按 runId 维护订阅者，run 状态变化
 * （suspended / resumed / finished）时推送。用于<b>挂起后跨页面实时同步</b>——
 * 订阅一个已挂起的 run，决策恢复/完成时实时收到事件，替代轮询。
 *
 * <p>与 ChatService 的「请求内即时 SSE」不同：本总线是「跨请求、run 生命周期」的事件通道，
 * 也为后续工作流逐节点流化（stage/agent.output/decision.request）预留同一通道。</p>
 *
 * <p>P0 单实例内存实现（多副本部署需换 Redis pub/sub 等外部总线）。终态后清理订阅者。</p>
 */
@Component
public class RunEventBus {

    private static final Logger log = LoggerFactory.getLogger(RunEventBus.class);
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;   // 挂起可能持续很久

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public RunEventBus(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 订阅某 run 的事件流（挂起后等待恢复/完成的实时通知） */
    public SseEmitter subscribe(String runId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        subscribers.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> { remove(runId, emitter); emitter.complete(); });
        emitter.onError(e -> remove(runId, emitter));
        try {
            emitter.send(SseEmitter.event().name("run.subscribe")
                    .data("{\"runId\":\"" + runId + "\"}", MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            remove(runId, emitter);
        }
        return emitter;
    }

    /** 推送事件到该 run 的所有订阅者；发送失败（客户端断开）的自动摘除 */
    public void publish(String runId, String event, Map<String, Object> payload) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(runId);
        if (list == null || list.isEmpty()) {
            return;
        }
        String data;
        try {
            data = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("run event serialize failed: {} ({})", event, e.toString());
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                log.warn("run event send failed: {} -> {} ({})", event, runId, e.toString());
                remove(runId, emitter);
            }
        }
    }

    /** run 进入终态：完成并清理所有订阅者 */
    public void complete(String runId) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.remove(runId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignore) {
                    // 已完成或客户端已断开
                }
            }
        }
    }

    private void remove(String runId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(runId);
        if (list != null) {
            list.remove(emitter);
        }
    }
}