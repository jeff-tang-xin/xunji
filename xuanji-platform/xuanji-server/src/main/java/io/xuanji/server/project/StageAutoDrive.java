package io.xuanji.server.project;

import io.xuanji.server.auth.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 驱动的阶段自动执行：用户在阶段对话里说话，引擎动作自动发生，人不需要点流程按钮。
 *
 * <p>设计口径（用户定的）：大阶段管职责边界（每阶段 AI 只做最小化的事），阶段内小流程
 * 由主控把控——能跳就跳，不能跳就执行。本类是「对话 → 引擎」的翻译层：</p>
 *
 * <ul>
 *   <li>阶段还没起跑（NO_PLAN / PENDING_APPROVAL / NOT_STARTED）→ 用户第一句话就是需求，
 *       自动生成规划并批准起跑，需求原文随 run input 进 agent 节点上下文；</li>
 *   <li>决策门挂起（WAITING_DECISION）→ 用户对话里的「同意/驳回」直接当决策，
 *       不再要求去右边栏点按钮（右边栏决策卡保留，作为精确操作入口）；</li>
 *   <li>执行中 / 已完成 → 不打扰（自动流转监听器已在跑）。</li>
 * </ul>
 *
 * <p>为什么独立成类而不是塞进 ChatService：ProjectService 已注入 ChatService，
 * 反向注入会成环；本类单向依赖 ProjectService（构造器 {@code @Lazy} 延迟解析断环），
 * ChatService 依赖本类。</p>
 */
@Service
public class StageAutoDrive {

    private static final Logger log = LoggerFactory.getLogger(StageAutoDrive.class);

    /** 决策意图判定：短消息（<50 字）才算决策——长句大概率是需求描述或追问，误判代价高。 */
    private static final int GATE_MSG_MAX = 50;

    private final ProjectService projects;

    /**
     * {@code @Lazy} 打破循环：ProjectService 字段注入 ChatService，ChatService 构造注入本类，
     * 本类再构造注入 ProjectService——三点成环，Spring 默认禁止。autoDrive() 只在对话请求时
     * 才真正用到 ProjectService，延迟解析语义安全（首次调用时宿主早已就绪）。
     */
    public StageAutoDrive(@Lazy ProjectService projects) {
        this.projects = projects;
    }

    /**
     * 对话前的引擎驱动。任何失败只记日志不抛出——驱动是增强，不能挡住对话本身。
     *
     * @return 驱动动作描述（写进对话审计日志），null 表示无需动作
     */
    public String autoDrive(LoginUser user, String wsId, String stage, String query) {
        try {
            Map<String, Object> exec = projects.stageExecution(user, wsId);
            String state = String.valueOf(exec.get("state"));
            switch (state) {
                case "NO_PLAN", "PENDING_APPROVAL", "NOT_STARTED" -> {
                    Map<String, Object> r = projects.autoStartStage(user, wsId, stage, query);
                    log.info("AI 驱动起跑: ws={} stage={} plan={} run={}", wsId, stage,
                            r.get("plan_id"), r.get("run_id"));
                    return "auto-start plan=" + r.get("plan_id") + " run=" + r.get("run_id");
                }
                case "WAITING_DECISION" -> {
                    String decision = parseGateDecision(query);
                    if (decision == null) {
                        return null;
                    }
                    Object w = exec.get("waiting_decisions");
                    if (!(w instanceof List<?> list) || list.isEmpty()) {
                        return null;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> first = (Map<String, Object>) list.get(0);
                    String nodeId = String.valueOf(first.get("node_id"));
                    Map<String, Object> body = Map.of(
                            "node_id", nodeId,
                            "decision", decision,
                            "comment", "REJECT".equals(decision) ? query : "对话批准");
                    projects.decideStageGate(user, wsId, body);
                    log.info("AI 驱动决策: ws={} stage={} node={} decision={}", wsId, stage, nodeId, decision);
                    return "auto-decide node=" + nodeId + " decision=" + decision;
                }
                default -> {
                    return null; // EXECUTING / FINISHED：自动流转监听器在处理，不打扰
                }
            }
        } catch (ResponseStatusException e) {
            // 幂等冲突（409 已处理/已起跑）等属正常竞态，降级为日志
            log.info("AI 驱动跳过（引擎拒绝）: ws={} stage={} -> {} {}", wsId, stage, e.getStatusCode(), e.getReason());
            return null;
        } catch (Exception e) {
            log.warn("AI 驱动失败（不影响对话）: ws={} stage={} -> {}", wsId, stage, e.getMessage());
            return null;
        }
    }

    /**
     * 从对话消息解析决策门意图。命中「同意/驳回」语义且消息足够短才当决策，
     * 其余一律当普通对话——把「能不能通过编译？」误判成 APPROVE 是不可接受的。
     */
    String parseGateDecision(String query) {
        if (query == null || query.isBlank() || query.length() > GATE_MSG_MAX) {
            return null;
        }
        String q = query.toLowerCase();
        if (q.matches(".*(同意|通过|可以|没问题|批准|ok|approve|继续吧|就这么定).*")) {
            return "APPROVE";
        }
        if (q.matches(".*(驳回|拒绝|不行|不同意|reject|重来).*")) {
            return "REJECT";
        }
        return null;
    }
}
