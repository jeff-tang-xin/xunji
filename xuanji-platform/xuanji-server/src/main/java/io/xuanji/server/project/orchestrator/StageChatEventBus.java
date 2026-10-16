package io.xuanji.server.project.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 阶段对话事件总线——后端系统唤醒的主控轮的实时回显通道。
 *
 * <p><b>为什么需要</b>：系统唤醒（任务/讨论落板、决策答复后自动续跑）由后端发起，
 * 没有请求级 SSE（不是前端发的消息）。事件发布到本总线，前端常驻订阅
 * （GET /{id}/stage/chat/events），在线即实时看到主控的唤醒原因与回复流；
 * 离线时事件已落 agent_state，回放兜底——后端是运行唯一真相，前端只是交付入口之一。</p>
 *
 * <p><b>不广播前端发起的对话轮</b>：那些轮已有请求级 SSE，双路广播会让同一轮事件
 * 在前端渲染两遍。总线只承载系统唤醒轮。</p>
 */
@Service
public class StageChatEventBus {

    private static final Logger log = LoggerFactory.getLogger(StageChatEventBus.class);

    private final ObjectMapper objectMapper;

    /** 会话键（wsId:stage:iteration）→ 订阅者列表。CopyOnWrite：广播时遍历安全，订阅/断开低频。 */
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public StageChatEventBus(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 会话键约定：与 StageChatKeys.stageSession 同构（wsId:stage:iteration）。 */
    public static String key(String wsId, String stage, int iteration) {
        return wsId + ":" + stage + ":" + iteration;
    }

    /** 常驻订阅：不设超时（0L），断开由回调清理。 */
    public SseEmitter subscribe(String sessionKey) {
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> list =
                subscribers.computeIfAbsent(sessionKey, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> list.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        log.info("chat event bus subscribed: key={} subscribers={}", sessionKey, list.size());
        return emitter;
    }

    /**
     * 广播一条事件：无订阅者时静默（事件已落 agent_state，回放兜底）；
     * 单个订阅者发送失败即移除（客户端已断开），不影响其他订阅者。
     */
    public void publish(String sessionKey, String event, Object payload) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(sessionKey);
        if (list == null || list.isEmpty()) {
            return;
        }
        String data;
        try {
            data = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            data = String.valueOf(payload);
        }
        for (SseEmitter em : list) {
            try {
                em.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                list.remove(em);
            }
        }
    }
}
