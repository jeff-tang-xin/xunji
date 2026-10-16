/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * 项目状态事件总线——项目域状态变化的实时推送通道（SSE）。
 *
 * <p><b>为什么需要</b>：项目详情页的阶段执行台账、需求看板、交付物/留档此前靠轮询
 * （执行中 5s 一拉）或手动刷新——状态变了用户看不到，要自己点刷新。本总线把
 * 「后端状态落定」这个事实推给在线前端，前端收到即刷新对应视图；离线时
 * 数据仍在库里，重开页面拉全量兜底——后端是运行唯一真相，推送只是加速感知。</p>
 *
 * <p><b>事件契约</b>（event 名 → payload）：</p>
 * <ul>
 *   <li>{@code step.updated} — {stage, seq, stepType, status, name}：任务/讨论/决策步骤落终态</li>
 *   <li>{@code decision.answered} — {stage, seq, confirmed:[REQ-xxx]}：决策答复闭环（含需求自动确认结果）</li>
 *   <li>{@code requirement.updated} — {stage, codes, action}：需求落库/确认/状态推进</li>
 * </ul>
 *
 * <p><b>粒度纪律</b>：只发「状态落定」事件（终态/确认），不发过程心跳——
 * 过程可见性走 StageChatEventBus（对话流）与 run 事件总线（工作流），各通道职责不重叠。</p>
 */
@Service
public class ProjectEventBus {

    private static final Logger log = LoggerFactory.getLogger(ProjectEventBus.class);

    private final ObjectMapper objectMapper;

    /** 项目键（wsId）→ 订阅者列表。CopyOnWrite：广播时遍历安全，订阅/断开低频。 */
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public ProjectEventBus(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 常驻订阅：不设超时（0L），断开由回调清理。 */
    public SseEmitter subscribe(String wsId) {
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> list =
                subscribers.computeIfAbsent(wsId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> list.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        log.info("project event bus subscribed: ws={} subscribers={}", wsId, list.size());
        return emitter;
    }

    /**
     * 广播一条状态事件：无订阅者时静默（数据已落库，前端重开页面拉全量兜底）；
     * 单个订阅者发送失败即移除（客户端已断开），不影响其他订阅者。
     */
    public void publish(String wsId, String event, Object payload) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(wsId);
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
