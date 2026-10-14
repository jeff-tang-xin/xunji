package io.xuanji.server.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.server.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PM 阶段规划 → 画布 JSON（definition format v1），执行交给既有 LiteFlow 链路。
 *
 * <p><b>为什么不自己推进子任务状态</b>：平台已经有工作流引擎——节点落库、按入边装配输入、
 * 决策门挂起与恢复、超时升级、待决策列表、run 树，这些都已在线上验证。再造一套
 * {@code project_work_node} 状态机只会多出第二个真相源，两边一旦不同步，"阶段到底走到哪"
 * 就没人说得清。规划因此编译成一条工作流，人审核时看的就是画布上那个流程。</p>
 *
 * <p><b>受引擎当前能力约束</b>：{@code LiteFlowRuleCompiler} 的 P0 只支持 SEQ 线性链
 * （每节点至多一条 SEQ 出边），PAR(WHEN)/COND(SWITCH) 出现即编译失败。所以子任务按依赖
 * 拓扑拉平成一条串行链。一期不做多 agent 并行 fan-out：需要多视角时把角色写进同一子任务的
 * {@code participants}，由执行 agent 在一次产出里覆盖多个视角，而不是假装能并行。</p>
 *
 * <p><b>任务描述走 run 输入而不是节点 params</b>：{@code AgentNodeExecutor.execute} 只读
 * refId 与 capabilityBinding，params 不进 prompt。因此子任务的实例 id（t1..tN）同时是
 * run 输入里 {@code tasks.{id}} 的键，由该节点的入边 mapping 取回自己的任务卡。</p>
 */
@Component
public class StagePlanCompiler {

    private static final Logger log = LoggerFactory.getLogger(StagePlanCompiler.class);

    private static final String START_ID = "start";
    private static final String END_ID = "end";

    /** 背景字段：从 START 输出逐个映射，不用 {@code $.start.output → $.input} 整包透传，
     *  否则 tasks 全量（含所有子任务描述）会被塞进每个 agent 的上下文。 */
    private static final List<String> CONTEXT_FIELDS =
            List.of("project", "stage", "iteration", "quality_gates", "resources", "prior_summaries");

    private final ProjectRepository repo;
    private final ObjectMapper json;

    public StagePlanCompiler(ProjectRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    /**
     * 编译结果。
     *
     * @param canvasJson 画布 JSON（可直接交 DefinitionService.create）
     * @param tasks      实际进入流程的子任务（含 instance_id，run 输入按此键构造）
     * @param skipped    被剔除的子任务与原因——必须回给人看，静默少一个子任务比报错更危险
     */
    public record Compiled(String canvasJson, List<Map<String, Object>> tasks,
                           List<Map<String, Object>> skipped) {
    }

    /**
     * 把一份已批准的阶段规划编译成画布 JSON。
     *
     * @param projectName 项目名（仅用于流程命名与描述，便于在定义列表里认出是哪条阶段链）
     * @param capability  阶段能力绑定（只 skills/mcps 能进 capabilityBinding，其余走 run 输入）
     */
    public Compiled compile(String projectName, String stage, int iteration,
                            Map<String, Object> plan, Map<String, Object> capability) {
        List<Map<String, Object>> ordered = topoOrder(mapList(plan.get("tasks")));

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        List<Map<String, Object>> kept = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();

        nodes.add(startNode());
        String prev = START_ID;
        boolean prevIsAgent = false;
        int idx = 0;
        for (Map<String, Object> task : ordered) {
            String agent = firstStr(task.get("agents"));
            String refId = agent == null ? null : publishedAgentRef(agent);
            if (refId == null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", strOr(task.get("name"), "未命名子任务"));
                s.put("agent", agent);
                s.put("reason", agent == null
                        ? "规划未指定执行 agent"
                        : "agent 无已发布版本（画布 AGENT 节点发布期要求 status='PUBLISHED'）: " + agent);
                skipped.add(s);
                continue;
            }
            String id = "t" + (++idx);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("kind", "AGENT");
            node.put("refId", refId);
            node.put("name", strOr(task.get("name"), id));
            // params 不进 prompt，只留作人在画布上溯源
            node.put("params", Map.of("plan_task_key", String.valueOf(task.get("key")),
                    "scope", strOr(task.get("scope"), "")));
            Map<String, Object> binding = capabilityBinding(capability);
            if (binding != null) {
                node.put("capabilityBinding", binding);
            }
            String approval = strOr(task.get("approval_mode"), "REVIEW").toUpperCase();
            if (!"AUTO".equals(approval)) {
                // REVIEW 与 FORCE 都落成决策门：区别只体现在给人看的标注上，
                // 引擎侧都是"产出后挂起等审批"（AGENT + REDO = 评审门，见 NodeDispatchComponent）
                node.put("decisionGate", gate(approval));
            }
            nodes.add(node);
            edges.add(edge(prev, id, prevIsAgent));

            Map<String, Object> k = new LinkedHashMap<>();
            k.put("instance_id", id);
            k.put("name", strOr(task.get("name"), id));
            k.put("agent", agent);
            k.put("participants", listOr(task.get("participants")));
            k.put("scope", strOr(task.get("scope"), ""));
            k.put("approval_mode", approval);
            kept.add(k);
            prev = id;
            prevIsAgent = true;
        }

        nodes.add(endNode());
        edges.add(edge(prev, END_ID, prevIsAgent));

        if (kept.isEmpty()) {
            throw new IllegalStateException("规划里没有任何可执行的子任务（agent 均未发布）："
                    + skipped);
        }

        String flowName = "[阶段] " + strOr(projectName, "项目") + " · " + stage + " 第" + iteration + "轮";
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", trim(flowName, 128));
        meta.put("description", trim("PM 阶段规划自动编译生成：stage=" + stage + " iteration=" + iteration
                + "，子任务 " + kept.size() + " 个" + (skipped.isEmpty() ? "" : "，跳过 " + skipped.size() + " 个"), 512));

        Map<String, Object> canvas = new LinkedHashMap<>();
        canvas.put("meta", meta);
        canvas.put("definitionVersion", 0);
        canvas.put("triggers", List.of(Map.of("type", "MANUAL", "config", Map.of())));
        canvas.put("nodes", nodes);
        canvas.put("edges", edges);

        if (!skipped.isEmpty()) {
            log.warn("阶段规划编译跳过子任务: stage={} iteration={} skipped={}", stage, iteration, skipped);
        }
        return new Compiled(writeJson(canvas), kept, skipped);
    }

    // ---------- 节点与边 ----------

    private Map<String, Object> startNode() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", Map.of("type", "object"));
        props.put("stage", Map.of("type", "string"));
        props.put("iteration", Map.of("type", "integer"));
        props.put("quality_gates", Map.of("type", "array"));
        props.put("resources", Map.of("type", "array"));
        props.put("prior_summaries", Map.of("type", "array"));
        props.put("tasks", Map.of("type", "object"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "stage", "tasks"));

        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", START_ID);
        n.put("kind", "START");
        n.put("name", "阶段输入");
        n.put("refId", "system.start");
        n.put("inputSchema", schema);
        n.put("params", Map.of());
        return n;
    }

    private Map<String, Object> endNode() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("output", Map.of("type", "string")));
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", END_ID);
        n.put("kind", "END");
        n.put("name", "阶段交付");
        n.put("refId", "system.end");
        n.put("outputSchema", schema);
        n.put("params", Map.of());
        return n;
    }

    /**
     * 线性链上的一条 SEQ 边。
     *
     * <p>输入装配三块：① 从 START 取阶段背景字段；② 取本节点自己的任务卡
     * {@code $.start.output.tasks.{target}}；③ 若上游是 agent 节点，把它的产出作为
     * {@code upstream_output} 传下来——阶段内的信息传递全靠这一步，否则后面的子任务
     * 只能看到项目背景，看不到前面已经做出的结论。</p>
     */
    private Map<String, Object> edge(String from, String to, boolean fromIsAgent) {
        List<Map<String, Object>> mapping = new ArrayList<>();
        for (String field : CONTEXT_FIELDS) {
            mapping.add(Map.of("from", "$." + START_ID + ".output." + field, "to", "$.input." + field));
        }
        // END 不是子任务、没有任务卡，别给它生成 $.start.output.tasks.end 这种必然取空的引用
        if (!END_ID.equals(to)) {
            mapping.add(Map.of("from", "$." + START_ID + ".output.tasks." + to, "to", "$.input.current_task"));
        }
        if (fromIsAgent) {
            mapping.add(Map.of("from", "$." + from + ".output", "to", "$.input.upstream_output"));
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("from", from);
        e.put("to", to);
        e.put("type", "SEQ");
        e.put("mapping", mapping);
        return e;
    }

    /** 决策门：AGENT + REDO 在引擎侧是"先产出再挂起审批"的评审门，正是阶段里"AI 决策、人审核"的形态。 */
    private Map<String, Object> gate(String approvalMode) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("enabled", true);
        g.put("interaction", "WORKSPACE");
        g.put("onReject", "REDO");
        g.put("onTimeout", "ESCALATE");
        g.put("approval_level", approvalMode);
        return g;
    }

    // ---------- 能力绑定 ----------

    /**
     * 阶段绑定 → 节点 capabilityBinding。
     *
     * <p>{@code AgentCapabilityBinding} 只有 skills 与 mcps 两个字段，tools/connectors/artifacts
     * 进不了这个结构，由调用方放进 run 输入的 resources 清单（脱敏后）。
     * 引用必须带 {@code @version}——{@code CanvasJsonParser.parseRefs} 遇到裸 id 直接抛异常，
     * 所以这里把 {@code skill:5} 补成 {@code skill:5@{最新已发布版}}；补不到就丢弃该引用并告警，
     * 不能让一条过期引用把整个阶段的流程发布卡死。</p>
     */
    private Map<String, Object> capabilityBinding(Map<String, Object> capability) {
        if (capability == null || capability.isEmpty()) {
            return null;
        }
        List<String> skills = normalizeRefs(listOr(capability.get("skills")), "skill");
        List<String> mcps = normalizeRefs(listOr(capability.get("mcps")), "mcp");
        Map<String, Object> cb = new LinkedHashMap<>();
        if (!skills.isEmpty()) {
            cb.put("skills", skills);
        }
        if (!mcps.isEmpty()) {
            cb.put("mcps", mcps);
        }
        return cb.isEmpty() ? null : cb;
    }

    private List<String> normalizeRefs(List<Object> raw, String kind) {
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (o == null) {
                continue;
            }
            String ref = String.valueOf(o).trim();
            if (ref.isEmpty()) {
                continue;
            }
            String body = ref.startsWith(kind + ":") ? ref.substring(kind.length() + 1) : ref;
            if (body.contains("@")) {
                out.add(kind + ":" + body);
                continue;
            }
            Long id = parseLong(body);
            Integer ver = id == null ? null : repo.latestPublishedVersion(kind, id);
            if (ver == null) {
                log.warn("阶段绑定引用无法解析版本，已丢弃: {}:{}", kind, ref);
                continue;
            }
            out.add(kind + ":" + id + "@" + ver);
        }
        return out;
    }

    // ---------- 排序与取值 ----------

    /**
     * 依赖拓扑排序，并列时按规划 key 升序 —— 同一份规划每次编译都得到同样的链，
     * 否则人刚批准的流程与实际发布的流程可能对不上。
     *
     * <p>成环或依赖缺失时不报错：把剩余节点按 key 顺序补到链尾。规划出自 AI，
     * 一个环不该导致整个阶段发不出去；链因此变长是可接受的，流程起不来是不可接受的。</p>
     */
    private List<Map<String, Object>> topoOrder(List<Map<String, Object>> tasks) {
        Map<Integer, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            Map<String, Object> t = tasks.get(i);
            int key = intOr(t.get("key"), i + 1);
            t.put("key", key);
            byKey.putIfAbsent(key, t);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Integer> done = new LinkedHashSet<>();
        int guard = byKey.size() + 2;
        while (out.size() < byKey.size() && guard-- > 0) {
            for (Map.Entry<Integer, Map<String, Object>> en : byKey.entrySet()) {
                if (done.contains(en.getKey())) {
                    continue;
                }
                boolean ready = true;
                for (Object d : listOr(en.getValue().get("depends"))) {
                    Integer dep = d instanceof Number n ? n.intValue() : null;
                    if (dep != null && byKey.containsKey(dep) && !done.contains(dep)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    out.add(en.getValue());
                    done.add(en.getKey());
                }
            }
        }
        for (Map.Entry<Integer, Map<String, Object>> en : byKey.entrySet()) {
            if (!done.contains(en.getKey())) {
                out.add(en.getValue());
            }
        }
        return out;
    }

    private String publishedAgentRef(String agentName) {
        return repo.findPublishedAgent(agentName).stream().findFirst()
                .map(r -> "agent:" + r.get("id") + "@" + r.get("version"))
                .orElse(null);
    }

    private String firstStr(Object raw) {
        for (Object o : listOr(raw)) {
            if (o != null && !String.valueOf(o).isBlank()) {
                return String.valueOf(o).trim();
            }
        }
        return null;
    }

    private String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String writeJson(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("画布 JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    private Long parseLong(String s) {
        try {
            return Long.valueOf(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> listOr(Object raw) {
        if (raw instanceof List<?> list) {
            return (List<Object>) list;
        }
        if (raw == null) {
            return List.of();
        }
        try {
            Object parsed = json.readValue(String.valueOf(raw), Object.class);
            if (parsed instanceof List<?> list) {
                return (List<Object>) list;
            }
        } catch (Exception ignored) {
            // 非 JSON 就当空：引用列表坏了只少一条绑定，不该中断编译
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : listOr(raw)) {
            if (o instanceof Map<?, ?>) {
                out.add(new LinkedHashMap<>((Map<String, Object>) o));
            }
        }
        return out;
    }

    private String strOr(Object v, String def) {
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private int intOr(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
