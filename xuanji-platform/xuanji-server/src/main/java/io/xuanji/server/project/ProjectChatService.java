package io.xuanji.server.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.core.runtime.AgentHandle;
import io.xuanji.core.runtime.AgentMessage;
import io.xuanji.core.runtime.AgentRuntime;
import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.core.runtime.AgentStreamEvent;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.engine.AgentNodeExecutor;
import io.xuanji.server.repository.ProjectRepository;
import io.xuanji.server.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 项目节点内对话（方案 A）：给项目工作节点加 SSE 流式对话入口。
 *
 * <p>按节点 owner_role 匹配内置 agent（project_manager/product_analyst/architect/
 * developer/tester/devops/dba），构建 AgentSpec 走 AgentRuntime 流式对话，对话结束后
 * 把最终文本自动登记为 DELIVERABLE 留档（insertArtifact）。会话标识按节点维度
 * {@code proj-{wsId}-node-{nodeId}}，多轮记忆按节点隔离。</p>
 *
 * <p>SSE 事件名与 ChatService 对齐：agent.start / agent.delta / agent.result /
 * agent.end / error / stage.finish。</p>
 */
@Service
public class ProjectChatService {

    private static final Logger log = LoggerFactory.getLogger(ProjectChatService.class);

    private final AgentRuntime runtime;
    private final AgentNodeExecutor agentNodes;
    private final ProjectRepository repo;
    private final WorkspaceRepository workspaceRepo;
    private final ObjectMapper objectMapper;
    /** 会话历史直查 agent_state（PostgresAgentStateStore 的存储表，key 恒为 agent_state） */
    private final JdbcTemplate jdbc;
    private final StageAutoDrive autoDrive;
    private final io.xuanji.server.project.orchestrator.StageOrchestrator orchestrator;
    private final io.xuanji.server.project.orchestrator.StageStepRepository stepRepo;
    private final io.xuanji.server.repository.RunRepository runRepo;
    /** 编程式事务：registerDeliverable 在 chatExecutor lambda 里被 this 直调，注解事务不走代理，必须显式包 */
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    /** 虚拟线程池：SSE 长连接执行载体（JDK21） */
    private final ExecutorService chatExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ProjectChatService(AgentRuntime runtime, AgentNodeExecutor agentNodes,
                              ProjectRepository repo, WorkspaceRepository workspaceRepo,
                              ObjectMapper objectMapper, JdbcTemplate jdbc,
                              StageAutoDrive autoDrive,
                              io.xuanji.server.project.orchestrator.StageOrchestrator orchestrator,
                              io.xuanji.server.project.orchestrator.StageStepRepository stepRepo,
                              io.xuanji.server.repository.RunRepository runRepo,
                              org.springframework.transaction.PlatformTransactionManager txManager) {
        this.txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.runtime = runtime;
        this.agentNodes = agentNodes;
        this.repo = repo;
        this.workspaceRepo = workspaceRepo;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.autoDrive = autoDrive;
        this.orchestrator = orchestrator;
        this.stepRepo = stepRepo;
        this.runRepo = runRepo;
    }

    /**
     * 项目节点内对话（SSE）：查节点 → 校验归属 → 按 owner_role 匹配 agent →
     * 流式对话 → 结束后把最终文本登记为 DELIVERABLE 留档。
     */
    public SseEmitter chatByNode(LoginUser user, String wsId, String nodeId, String query) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        requireWorkspaceVisible(user, wsId);
        Map<String, Object> node = requireNodeInWorkspace(wsId, nodeId);

        String ownerRole = node.get("owner_role") == null ? null : node.get("owner_role").toString();
        // 阶段模板节点只查一次，同时供「选智能体」与「注入阶段知识」使用（活引用：改模板即生效）
        TemplateStage stage = resolveTemplateStage(wsId, node);

        // 智能体优先级：模板阶段显式指定的 agent_name > owner_role 硬编码映射（老项目/老模板回退）。
        // 显式指定让「这个阶段用哪个智能体」成为模板里可见可改的配置，而不是藏在代码 switch 里。
        String templateAgent = stage == null ? null : (String) stage.node().get("agent_name");
        String agentName = (templateAgent != null && !templateAgent.isBlank())
                ? templateAgent.trim()
                : matchAgent(ownerRole);
        if (agentName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "节点未匹配到内置 agent（模板 agent_name 为空，owner_role=" + ownerRole
                            + " 也未映射到内置 agent；请在模板编辑器的「执行智能体」里指定）");
        }

        String sessionId = "proj-" + wsId + "-node-" + nodeId;
        String nodeType = node.get("type") == null ? "NODE" : node.get("type").toString();
        // 阶段通用知识（模板的规范/要求/方法论/交付物）+ 本项目该节点任务边界 → 注入 system prompt
        String stageGuidance = buildStageGuidance(wsId, stage, node);
        log.info("project-node stage guidance: project={} node={} seq={} chars={} head=[{}]",
                wsId, nodeId, node.get("seq"),
                stageGuidance == null ? 0 : stageGuidance.length(),
                stageGuidance == null ? "-" : stageGuidance.replace('\n', ' ').substring(0,
                        Math.min(120, stageGuidance.length())));
        AgentSpec spec = agentNodes.buildSpecByAgentName(agentName, sessionId, stageGuidance, wsId);
        String audit = "project-node=" + wsId + "/" + nodeId + " agent=" + agentName
                + " session=" + sessionId
                + " guidance=" + (stageGuidance == null ? "none" : stageGuidance.length() + "chars");

        return startChat(spec, query, audit, user, wsId, nodeId, nodeType, null, List.of());
    }

    /**
     * 阶段级对话（V28）：对话对象是「当前阶段的子任务」，不再依赖 {@code project_work_node} 台账。
     *
     * <p>为什么必须另开一条路：{@link #chatByNode} 依赖两样 V27 之后不再存在的东西——
     * ① 台账节点（V27 起规划不再落 {@code project_work_node}，新项目 0 节点，前端连对话入口都点不出来）；
     * ② {@code project_template} 的 seq 匹配（模板已停用，阶段知识迁为 3 份 stage-spec skill）。
     * 结果是新项目 agent 选择恒失败，硬拼 nodeId 调用也只拿到 400「节点未匹配到内置 agent」。</p>
     *
     * <p>阶段通用知识不在这里拼：3 份 stage-spec 已由 {@code SeedInitializer} 绑到对应 agent，
     * {@code AgentNodeExecutor.resolveSkills} 装配系统提示词时自动展开。本方法只补「本项目本阶段的任务边界」
     * ——那是项目实例数据，skill 里没有。</p>
     */
    public SseEmitter chatByStage(LoginUser user, String wsId, Map<String, Object> body) {
        String query = body == null || body.get("message") == null
                ? "" : body.get("message").toString().trim();
        if (query.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        Map<String, Object> ws = workspaceRepo.findById(wsId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found"));
        requireOwner(user, ws);

        String stage = ws.get("current_stage") == null ? "REQUIREMENT" : ws.get("current_stage").toString();
        int iteration = "OPERATE".equals(stage) ? Math.max(1, repo.maxIteration(wsId)) : 1;

        // 双轨分流：旧内核（LiteFlow run）活跃 → 旧链路自然收尾；否则走主控编排（AI 驱动内核）。
        if (!legacyRunActive(wsId, stage)) {
            return orchestrator.chat(user, wsId, stage, iteration, query);
        }

        // AI 驱动：对话即执行——未起跑自动起跑（用户输入=需求）、决策门挂起时对话即审批。
        // 驱动失败不挡对话（StageAutoDrive 内部已兜底），右边栏决策卡仍是精确操作入口。
        String drove = autoDrive.autoDrive(user, wsId, stage, query);
        String audit0 = drove == null ? "" : " drove=" + drove;

        Map<String, Object> task = pickStageTask(wsId, stage, iteration, body.get("task_key"));
        String agentName = resolveStageAgent(stage, task, stageBoundAgents(wsId, stage));
        // 会话按「项目 + 阶段 + 迭代」聚合：切子任务不丢上下文——对话是这一阶段的工作现场，不是一问一答的表单。
        String sessionId = StageChatKeys.stageSession(wsId, stage, iteration);
        String guidance = buildStageContext(ws, stage, iteration, task);

        AgentSpec spec = agentNodes.buildSpecByAgentName(agentName, sessionId, guidance, wsId);
        String audit = "project-stage=" + wsId + "/" + stage + "#" + iteration
                + " agent=" + agentName + " session=" + sessionId
                + " guidance=" + guidance.length() + "chars" + audit0;
        return startChat(spec, query, audit, user, wsId, "stage-" + stage, stage, stage,
                parseAttachments(body));
    }

    /**
     * 对话附件解析（V29）：图片走多模态（media_type + data_base64），文本文件走 content。
     * 上限：4 个/轮；单图 base64 后 ≤ 8MB；文本附件 ≤ 200KB——防单条消息撑爆 SSE 与 agent_state。
     */
    private List<AgentMessage.Attachment> parseAttachments(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("attachments");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        if (list.size() > 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件最多 4 个/轮");
        }
        List<AgentMessage.Attachment> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String name = m.get("name") == null ? null : m.get("name").toString().trim();
            if (name == null || name.isBlank()) {
                continue;
            }
            String mediaType = m.get("media_type") == null ? null : m.get("media_type").toString().trim();
            String data = m.get("data_base64") == null ? null : m.get("data_base64").toString().trim();
            String content = m.get("content") == null ? null : m.get("content").toString();
            if (data != null) {
                // base64 通道只收图片（多模态 ImageBlock 依赖 media_type 是 image/*，文本走 content 通道）
                if (mediaType == null || !mediaType.startsWith("image/")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片附件 " + name + " 的 media_type 必须为 image/*");
                }
                if (data.length() > 8_000_000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片 " + name + " 超过大小限制（约 6MB）");
                }
            }
            if (content != null && content.length() > 200_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本附件 " + name + " 超过 200KB");
            }
            if ((data == null || data.isBlank()) && (content == null || content.isBlank())) {
                continue;
            }
            out.add(new AgentMessage.Attachment(name, mediaType, data, content));
        }
        return out;
    }

    /**
     * 阶段会话历史（回放）：按「项目+阶段+迭代」读 AgentScope 存在 agent_state 里的会话状态。
     * 阶段对话此前刷新即空白（agent 侧记忆还在、但没有读取通道），本方法补上这条通道。
     */
    public Map<String, Object> chatHistory(LoginUser user, String wsId, String stage, Integer iteration) {
        requireWorkspaceVisible(user, wsId);
        String st = stage == null || stage.isBlank()
                ? workspaceRepo.findById(wsId).stream().findFirst()
                        .map(w -> String.valueOf(w.get("current_stage"))).orElse("REQUIREMENT")
                : stage.trim().toUpperCase();
        int it = iteration == null || iteration <= 0
                ? ("OPERATE".equals(st) ? Math.max(1, repo.maxIteration(wsId)) : 1)
                : iteration;
        return chatHistoryBySession(StageChatKeys.stageSession(wsId, st, it));
    }

    /** 按 sessionId 读会话状态并解析成可回放的消息列表（role/name/timestamp/text）。 */
    private Map<String, Object> chatHistoryBySession(String sessionId) {
        List<String> rows = jdbc.queryForList(
                "SELECT state_json FROM agent_state WHERE agent_id = ? AND session_id = ? AND key = ? "
                        + "ORDER BY updated_at DESC LIMIT 1",
                String.class, "__anon__", sessionId, "agent_state");
        List<Map<String, Object>> messages = new ArrayList<>();
        if (!rows.isEmpty()) {
            try {
                com.fasterxml.jackson.databind.JsonNode ctx =
                        objectMapper.readTree(rows.get(0)).path("context");
                if (ctx.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode m : ctx) {
                        // content 是分段数组：回放只拼 text 段——thinking 是模型内部推理，不进历史视图
                        StringBuilder text = new StringBuilder();
                        for (com.fasterxml.jackson.databind.JsonNode c : m.path("content")) {
                            if ("text".equals(c.path("type").asText())) {
                                text.append(c.path("text").asText(""));
                            }
                        }
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("role", m.path("role").asText(""));
                        item.put("name", m.path("name").asText(null));
                        item.put("timestamp", m.path("timestamp").asText(null));
                        item.put("text", text.toString());
                        messages.add(item);
                    }
                }
            } catch (Exception e) {
                log.warn("解析会话历史失败: session={}", sessionId, e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", sessionId);
        out.put("messages", messages);
        return out;
    }

    /** 项目内全部阶段会话列表（右侧栏「会话」tab 数据源）：会话粒度=阶段，阶段推进自动开新会话。 */
    public List<Map<String, Object>> chatSessions(LoginUser user, String wsId) {
        requireWorkspaceVisible(user, wsId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT session_id, max(updated_at) AS last_at FROM agent_state "
                        + "WHERE agent_id = ? AND session_id LIKE ? "
                        + "GROUP BY session_id ORDER BY max(updated_at) DESC",
                "__anon__", StageChatKeys.stagePrefix(wsId) + "%");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String sid = String.valueOf(r.get("session_id"));
            // proj-{wsId}-stage-{STAGE}-{iter} → 拆出 stage / iteration 供前端展示
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("session_id", sid);
            item.put("last_at", r.get("last_at"));
            int i = sid.indexOf("-stage-");
            String rest = i >= 0 ? sid.substring(i + "-stage-".length()) : "";
            int dash = rest.lastIndexOf('-');
            item.put("stage", dash > 0 ? rest.substring(0, dash) : rest);
            item.put("iteration", dash > 0 ? rest.substring(dash + 1) : "1");
            out.add(item);
        }
        return out;
    }

    /** 阶段兜底智能体：没有规划、或规划没指定 agent 时按阶段选。 */
    private static final Map<String, String> STAGE_DEFAULT_AGENT = Map.of(
            "REQUIREMENT", "product_analyst",
            "DEVELOP", "architect",
            "OPERATE", "devops");

    private String resolveStageAgent(String stage, Map<String, Object> task, List<String> bound) {
        // 用户在阶段设置里显式绑定的 agent 优先于规划草稿——UI 上选了就要生效，
        // 否则绑定形同虚设（草稿里写死的 agents 会永远压住用户的选择）
        if (bound != null && !bound.isEmpty()) {
            return bound.get(0);
        }
        if (task != null) {
            String fromTask = firstString(task.get("agents"));
            if (fromTask == null) {
                fromTask = firstString(task.get("participants"));
            }
            if (fromTask != null) {
                return fromTask;
            }
        }
        return STAGE_DEFAULT_AGENT.getOrDefault(stage, "project_manager");
    }

    /** 阶段能力绑定里的 agents（阶段设置里显式选的）；未绑定返回空列表。 */
    private List<String> stageBoundAgents(String wsId, String stage) {
        List<Map<String, Object>> rows = repo.findStageCapability(wsId, stage);
        if (rows.isEmpty() || rows.get(0).get("capability_json") == null) {
            return List.of();
        }
        try {
            Object raw = rows.get(0).get("capability_json");
            // jsonb 经 JdbcTemplate 读出通常是 PGobject/字符串；个别驱动配置下是 Map，两种都兼容
            com.fasterxml.jackson.databind.JsonNode node = raw instanceof Map<?, ?> m
                    ? objectMapper.valueToTree(m)
                    : objectMapper.readTree(String.valueOf(raw));
            com.fasterxml.jackson.databind.JsonNode agents = node.get("agents");
            if (agents == null || !agents.isArray() || agents.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            agents.forEach(a -> {
                String v = a.asText("").trim();
                if (!v.isEmpty()) {
                    out.add(v);
                }
            });
            return out;
        } catch (Exception e) {
            log.warn("解析阶段能力绑定的 agents 失败: ws={} stage={}", wsId, stage, e);
            return List.of();
        }
    }

    /** 按 task_key 在当前生效规划里取子任务；未指定或找不到都返回 null（只带阶段上下文，不报错）。 */
    private Map<String, Object> pickStageTask(String wsId, String stage, int iteration, Object taskKeyRaw) {
        String key = taskKeyRaw == null ? "" : taskKeyRaw.toString().trim();
        if (key.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> plans = repo.findCurrentStagePlan(wsId, stage, iteration);
        if (plans.isEmpty() || plans.get(0).get("plan_json") == null) {
            return null;
        }
        for (Map<String, Object> task : parsePlanTasks(plans.get(0).get("plan_json").toString())) {
            Object k = task.get("key");
            // 实测后端 key 是字符串（'1'），历史数据可能是数字，两边都按字符串比
            if (k != null && key.equals(String.valueOf(k))) {
                return task;
            }
        }
        return null;
    }

    /** plan_json → tasks 列表；兼容「顶层就是数组」与「{tasks:[...]}」两种历史写法。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parsePlanTasks(String planJson) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        try {
            Object parsed = objectMapper.readValue(planJson, Object.class);
            Object tasks = parsed instanceof Map<?, ?> map ? map.get("tasks") : parsed;
            if (tasks instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        out.add((Map<String, Object>) m);
                    }
                }
            }
        } catch (Exception e) {
            // 规划正文坏了不能拖垮对话：降级为无子任务上下文
            log.warn("stage plan_json parse failed (len={}): {}", planJson.length(), e.toString());
        }
        return out;
    }

    private static String firstString(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first != null && !first.toString().isBlank()) {
                return first.toString().trim();
            }
        }
        return null;
    }

    /** 项目实例上下文（阶段规范本体走 skill，这里只给「这个项目这一轮要做什么」）。 */
    private String buildStageContext(Map<String, Object> ws, String stage, int iteration,
                                     Map<String, Object> task) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前项目与阶段（这就是你的工作现场，请在此上下文内推进）\n\n");
        sb.append("- 项目：").append(ws.getOrDefault("name", "-")).append('\n');
        sb.append("- 阶段：").append(stageLabel(stage)).append("（第 ").append(iteration).append(" 轮）\n");
        if (task != null) {
            sb.append("- 本轮聚焦子任务：").append(task.getOrDefault("name", "-")).append('\n');
            Object scope = task.get("scope");
            if (scope != null && !scope.toString().isBlank()) {
                sb.append("- 任务边界（边界外的内容不要擅自纳入）：").append(scope).append('\n');
            }
            Object approval = task.get("approval_mode");
            if (approval != null && "FORCE".equalsIgnoreCase(approval.toString())) {
                sb.append("- 该子任务的产出需要人工评审通过后流程才会继续，请给出可直接评审的明确结论\n");
            }
        } else {
            sb.append("- 本轮未指定具体子任务，请按该阶段的目标整体推进\n");
        }
        sb.append("\n若用户的要求与阶段规范冲突，先指出冲突并给出你的建议，再按用户决定执行。");

        // 澄清约定：需要用户补充信息时输出结构化标记，主控据此在执行视图显示「待澄清」——
        // 对话与流程状态的唯一联动通道，格式必须严格（一行、回复末尾、无标记=信息足够）
        sb.append("\n\n## 澄清约定\n");
        sb.append("- 若缺少关键信息、必须由用户补充才能继续推进，在回复的最后一行单独输出：[NEED_CLARIFY] 你的具体问题（一行说清要什么、为什么需要）\n");
        sb.append("- 信息足够推进时禁止输出该标记；除该标记外不要用其他方式表达澄清请求\n");
        sb.append("- 用户在对话里回答后，基于答案继续推进，不要重复已澄清的问题\n");
        return sb.toString();
    }

    /**
     * 提取当前待澄清问题（主控对对话的「判断与提取」）：扫阶段会话历史，找最近一条带
     * [NEED_CLARIFY] 标记的 assistant 消息；若其后用户尚未回复（没有更晚的 user 消息），
     * 视为仍在等待澄清。零迁移：状态从对话历史推导，不落新表。
     *
     * @return {question, asked_at} 或 null（无待澄清 / 用户已回答）
     */
    public Map<String, Object> latestClarification(String wsId, String stage, int iteration) {
        String sessionId = StageChatKeys.stageSession(wsId, stage, iteration);
        List<Map<String, Object>> messages = chatHistoryBySession(sessionId)
                .get("messages") instanceof List<?> l ? castMessageList(l) : List.of();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if (!"assistant".equalsIgnoreCase(String.valueOf(m.get("role")))) {
                continue;
            }
            String text = String.valueOf(m.get("text"));
            int idx = text.indexOf("[NEED_CLARIFY]");
            if (idx < 0) {
                continue;
            }
            boolean answered = false;
            for (int j = i + 1; j < messages.size(); j++) {
                if ("user".equalsIgnoreCase(String.valueOf(messages.get(j).get("role")))) {
                    answered = true;
                    break;
                }
            }
            if (answered) {
                return null;
            }
            String question = text.substring(idx + "[NEED_CLARIFY]".length()).trim();
            int nl = question.indexOf('\n');
            if (nl > 0) {
                question = question.substring(0, nl).trim();
            }
            if (question.isEmpty()) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("question", question);
            out.put("asked_at", m.get("timestamp"));
            return out;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castMessageList(List<?> l) {
        return (List<Map<String, Object>>) l;
    }

    private static String stageLabel(String stage) {
        return switch (stage) {
            case "DEVELOP" -> "开发";
            case "OPERATE" -> "运维";
            default -> "需求";
        };
    }

    /**
     * 组装「阶段指导」文本——本次分层语义的落地点：
     * <ul>
     *   <li><b>通用层</b>（来自 project_template_node）：本阶段的 standards 规范、requirements 要求、
     *       methodology 方法论、deliverable 交付物形态、preconditions 前置条件——跨项目复用，
     *       是 AI 在该阶段必须遵循的工作标准与做法；</li>
     *   <li><b>边界层</b>（来自 project_work_node.scope）：本项目该节点要做什么、范围到哪——
     *       由项目实例决定，模板不预设，用来约束 AI 不越界发挥。</li>
     * </ul>
     * 降级：项目无 template_code（历史项目/模板已删/节点 seq 不匹配）时返回 null，
     * 即不注入阶段知识，对话照常进行。
     */
    /**
     * 模板阶段（活引用）：模板显示名 + 本项目节点 seq 对应的模板节点行。
     * 项目无 template_code、模板已删、或 seq 在模板里不存在时整体返回 null（调用方降级）。
     */
    /** 模板阶段快照：模板显示名（供指导文本与日志）+ 该 seq 的模板节点行（含 agent_name/workflow_def_id）。 */
    private record TemplateStage(String templateName, Map<String, Object> node) {
    }

    private TemplateStage resolveTemplateStage(String wsId, Map<String, Object> node) {
        try {
            String templateCode = workspaceRepo.findTemplateCode(wsId);
            if (templateCode == null || templateCode.isBlank()) {
                return null;
            }
            Map<String, Object> tpl = repo.findTemplateByCode(templateCode)
                    .stream().findFirst().orElse(null);
            if (tpl == null || tpl.get("id") == null) {
                return null;
            }
            int seq = node.get("seq") == null ? -1 : ((Number) node.get("seq")).intValue();
            Map<String, Object> tn = repo.findTemplateNodes(((Number) tpl.get("id")).longValue())
                    .stream()
                    .filter(r -> r.get("seq") != null && ((Number) r.get("seq")).intValue() == seq)
                    .findFirst().orElse(null);
            if (tn == null) {
                return null;
            }
            return new TemplateStage(String.valueOf(tpl.get("name")), tn);
        } catch (Exception e) {
            log.warn("template stage lookup skipped: project={} -> {}", wsId, e.toString());
            return null;
        }
    }

    private String buildStageGuidance(String wsId, TemplateStage stage, Map<String, Object> node) {
        if (stage == null) {
            return null;
        }
        try {
            Map<String, Object> tn = stage.node();
            int seq = node.get("seq") == null ? -1 : ((Number) node.get("seq")).intValue();
            StringBuilder sb = new StringBuilder();
            sb.append("## 本阶段工作规范（来自项目模板「").append(stage.templateName())
                    .append("」的阶段定义，跨项目复用的通用要求，你必须遵循）\n\n");
            sb.append("- 阶段类型：").append(tn.get("type"))
                    .append("（第 ").append(seq).append(" 步）\n");
            if (tn.get("owner_role") != null) {
                sb.append("- 负责角色：").append(tn.get("owner_role")).append('\n');
            }

            appendBulletList(sb, "产出必须满足的规范（standards）", tn.get("standards"));
            appendBulletList(sb, "本阶段要求覆盖（requirements）", tn.get("requirements"));
            appendBulletList(sb, "前置条件（preconditions）", tn.get("preconditions"));

            if (tn.get("methodology") != null && !tn.get("methodology").toString().isBlank()) {
                sb.append("\n### 建议方法论（methodology）\n").append(tn.get("methodology")).append('\n');
            }
            if (tn.get("deliverable") != null && !tn.get("deliverable").toString().isBlank()) {
                sb.append("\n### 本阶段交付物形态\n").append(tn.get("deliverable")).append('\n');
            }
            if (node.get("scope") != null && !node.get("scope").toString().isBlank()) {
                sb.append("\n### 本项目该节点的任务边界（由项目决定，边界外内容不要擅自纳入）\n")
                        .append(node.get("scope")).append('\n');
            }

            sb.append("\n请严格按上述规范与方法论开展本阶段工作；若用户的要求与上述规范冲突，")
                    .append("先指出冲突并给出你的建议，再按用户决定执行。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("stage guidance skipped: project={} -> {}", wsId, e.toString());
            return null;
        }
    }

    /** JSONB 字符串数组列（::text）渲染为小节 + bullet 列表；空值跳过。 */
    private void appendBulletList(StringBuilder sb, String title, Object jsonArray) {
        List<String> items = parseStringList(jsonArray);
        if (items.isEmpty()) {
            return;
        }
        sb.append("\n### ").append(title).append('\n');
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                sb.append("- ").append(item).append('\n');
            }
        }
    }

    /* ---------- 核心：异步流式执行 + 结束后登记留档 ---------- */

    /**
     * 旧内核活跃判定：本阶段最新规划已起跑且 run 处于 RUNNING/SUSPENDED → 旧链路继续负责
     * （自然收尾，不中途切换）；其余情况（无规划/未起跑/已结束）走主控编排。
     */
    private boolean legacyRunActive(String wsId, String stage) {
        var planRow = repo.findLatestStagePlan(wsId, stage).stream().findFirst().orElse(null);
        if (planRow == null || planRow.get("exec_run_id") == null) {
            return false;
        }
        String runId = String.valueOf(planRow.get("exec_run_id"));
        try {
            var run = runRepo.findRunForResume(runId);
            if (run.isEmpty()) {
                return false;
            }
            String status = String.valueOf(run.get("status"));
            return "RUNNING".equals(status) || "SUSPENDED".equals(status);
        } catch (Exception e) {
            return false;
        }
    }

    private SseEmitter startChat(AgentSpec spec, String query, String audit,
                                 LoginUser user, String wsId, String nodeId, String nodeType, String stage,
                                 List<AgentMessage.Attachment> attachments) {
        // 单轮上限 30 分钟（2026-10-17 同步化）：主控工具调用内同步等讨论/任务做完（无超时），
        // 单请求可达 20 分钟+；原 300s 会把还在跑的对话掐断，产出停在半截且无 stage.finish。
        SseEmitter emitter = new SseEmitter(1_800_000L);
        chatExecutor.submit(() -> {
            AgentHandle handle = null;
            AtomicReference<String> finalText = new AtomicReference<>();
            try {
                handle = runtime.createAgent(spec);
                send(emitter, "agent.start", Map.of("agent", spec.name()));
                runtime.stream(handle, new AgentMessage(query, null, attachments), evt -> {
                    // 捕获最终文本（agent.result 事件携带 text），供对话结束后登记留档
                    if ("agent.result".equals(evt.type()) && evt.text() != null) {
                        finalText.set(evt.text());
                    }
                    send(emitter, evt.type(), payloadOf(evt));
                });
                send(emitter, "stage.finish", Map.of("status", "SUCCESS"));
                emitter.complete();
                log.info("project-node chat finished: {}", audit);

                // 对话结束后把最终文本自动登记为 DELIVERABLE 留档
                String text = finalText.get();
                if (text != null && !text.isBlank()) {
                    registerDeliverable(user, wsId, nodeId, nodeType, text, stage);
                } else {
                    log.warn("project-node chat 无最终文本，跳过留档登记: {}", audit);
                }
            } catch (Exception e) {
                log.error("project-node chat failed: {} -> {}", audit, e.toString());
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

    /**
     * 把对话最终文本登记为 DELIVERABLE 留档（name=节点类型+日期），并落盘到项目目录
     * {@code docs/{stage}/{name}.md}——交付物不只活在库里，项目目录可直接进 git 跟踪。
     * 写文件失败不阻断登记（库里仍有全文，file_path 为空仅代表未落盘）。
     */
    protected void registerDeliverable(LoginUser user, String wsId, String nodeId,
                                       String nodeType, String contentMd, String stage) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String name = nodeType + "-" + date;
        String filePath = null;
        try {
            String rootPath = String.valueOf(workspaceRepo.findById(wsId).stream().findFirst()
                    .map(w -> w.get("root_path")).orElse(""));
            if (!rootPath.isBlank() && !"null".equals(rootPath) && stage != null && !stage.isBlank()) {
                Path dir = Path.of(rootPath, "docs", stage);
                Files.createDirectories(dir);
                Path file = dir.resolve(name + ".md");
                Files.writeString(file, contentMd == null ? "" : contentMd, StandardCharsets.UTF_8);
                filePath = "docs/" + stage + "/" + name + ".md";
            }
        } catch (Exception e) {
            log.warn("交付物落盘失败（登记继续）: ws={} name={} -> {}", wsId, name, e.toString());
        }
        // 两步写（artifact + 节点 refs）必须原子：本方法在 chatExecutor lambda 里被 this 直调，
        // @Transactional 不走代理无效，改用编程式事务（构造器注入 PlatformTransactionManager 包装）
        final String filePathFx = filePath; // filePath 在 try 里被重赋值，lambda 捕获需 effectively final
        txTemplate.executeWithoutResult(tx -> {
            repo.insertArtifact(wsId, nodeId, "DELIVERABLE", name, "DOC", filePathFx, contentMd,
                    null, "FINAL", stage, user.username());
            // 交付留档登记后，更新节点 output_refs（对齐 ProjectService.addArtifact 语义）
            Map<String, Object> node = repo.findNodeById(nodeId)
                    .stream().findFirst().orElse(null);
            if (node != null) {
                List<String> inputs = parseStringList(node.get("input_refs"));
                List<String> outputs = parseStringList(node.get("output_refs"));
                outputs.add(name + "@latest");
                repo.updateNodeRefs(nodeId, toJson(inputs), toJson(outputs));
            }
        });
        log.info("project-node chat 留档已登记: node={} name={} stage={} file={} chars={}",
                nodeId, name, stage, filePath, contentMd.length());
    }

    /* ---------- owner_role → agent 映射 ---------- */

    /**
     * owner_role → 内置 agent name。模板 owner_role 取值：产品/PM、产品/BA、架构师、
     * 开发、测试、测试/开发、运维；DB 为扩展预留。未匹配返回 null。
     */
    private String matchAgent(String ownerRole) {
        if (ownerRole == null || ownerRole.isBlank()) {
            return null;
        }
        return switch (ownerRole.trim()) {
            case "产品/PM" -> "project_manager";
            case "产品/BA" -> "product_analyst";
            case "架构师" -> "architect";
            case "开发" -> "developer";
            case "测试" -> "tester";
            case "测试/开发" -> "tester";
            case "运维" -> "devops";
            case "DB" -> "dba";
            default -> null;
        };
    }

    /* ---------- 归属校验（复制自 ProjectService，避免改动该文件） ---------- */

    private void requireWorkspaceVisible(LoginUser user, String wsId) {
        Map<String, Object> ws = workspaceRepo.findById(wsId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found"));
        requireOwner(user, ws);
    }

    /** 校验 nodeId 属于 wsId，返回节点记录（防越权读写他人项目节点）。 */
    private Map<String, Object> requireNodeInWorkspace(String wsId, String nodeId) {
        Map<String, Object> node = repo.findNodeById(nodeId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "node not found"));
        if (!wsId.equals(node.get("workspace_id"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "node 不属于该项目");
        }
        return node;
    }

    private void requireOwner(LoginUser user, Map<String, Object> row) {
        if (user.isPlatformAdmin() || "ALL".equals(user.dataScope())) {
            return;
        }
        String createdBy = (String) row.get("created_by");
        String scope = user.dataScope() == null ? "ONLY_CREATE" : user.dataScope();
        boolean orgVisible = ("ORG".equals(scope) || "UNDER_ORG".equals(scope))
                && user.orgId() != null && user.orgId().equals(row.get("org_id"));
        if (!orgVisible && !user.username().equals(createdBy)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该项目");
        }
    }

    /* ---------- SSE 工具 ---------- */

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

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(Object json) {
        if (json == null) {
            return List.of();
        }
        try {
            Object parsed = objectMapper.readValue(json.toString(), Object.class);
            if (parsed instanceof List<?> list) {
                List<String> result = new java.util.ArrayList<>();
                for (Object o : list) {
                    result.add(o == null ? null : o.toString());
                }
                return result;
            }
        } catch (Exception ignored) {
            // 非法 JSON 视为空列表
        }
        return List.of();
    }
}