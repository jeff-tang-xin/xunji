package io.xuanji.server.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.server.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link StagePlanCompiler} 单元测试：阶段规划 → 画布 JSON 的编译契约。
 *
 * <p>这里的断言全部对应线上踩过的坑，不是为凑覆盖率写的：</p>
 * <ul>
 *   <li><b>END 节点没有任务卡。</b>给它生成 {@code $.start.output.tasks.end} 这种必然取空的引用，
 *       会让 null 被写进节点 input，引擎侧 {@code NodeOutput.of} 内的 {@code Map.copyOf} 抛出
 *       <b>连 message 都是 null</b> 的 NPE，整条 run FAILED 而日志只剩 {@code cause error,error:null}
 *       ——线上实测过一次，排查成本极高，所以专门钉住。</li>
 *   <li><b>引用未发布 agent 的子任务必须进 skipped 回给人看。</b>静默少跑一步比直接报错更危险。</li>
 *   <li><b>引擎 P0 只支持 SEQ 线性链</b>（{@code LiteFlowRuleCompiler} 遇到 COND/PAR 边编译即失败），
 *       编译器不得产出其它边类型。</li>
 * </ul>
 */
class StagePlanCompilerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProjectRepository repo;
    private StagePlanCompiler compiler;

    @BeforeEach
    void setUp() {
        repo = mock(ProjectRepository.class);
        compiler = new StagePlanCompiler(repo, JSON);
        // 三个内置角色都有已发布版本；未 stub 的角色 Mockito 默认返回空集合，正好表示「未发布」
        published("product_analyst", 11L, 2);
        published("architect", 12L, 3);
        published("developer", 13L, 1);
    }

    private void published(String name, long id, int version) {
        when(repo.findPublishedAgent(name)).thenReturn(List.of(Map.of("id", id, "version", version)));
    }

    private static Map<String, Object> task(int key, String name, String agent, String approval, List<Integer> depends) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("agents", agent == null ? List.of() : List.of(agent));
        if (approval != null) {
            m.put("approval_mode", approval);
        }
        m.put("scope", "范围-" + name);
        m.put("depends", depends == null ? List.of() : depends);
        return m;
    }

    /** 编译并解析回画布 JSON（compile 的产物是字符串，断言前要先读回来）。 */
    private StagePlanCompiler.Compiled compile(List<Map<String, Object>> tasks) {
        return compiler.compile("订单中心改造", "REQUIREMENT", 1,
                Map.of("tasks", tasks), Map.of());
    }

    private static JsonNode canvas(StagePlanCompiler.Compiled c) throws Exception {
        return JSON.readTree(c.canvasJson());
    }

    private static List<String> ids(JsonNode canvas, String kind) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : canvas.path("nodes")) {
            if (kind == null || kind.equals(n.path("kind").asText())) {
                out.add(n.path("id").asText());
            }
        }
        return out;
    }

    private static JsonNode nodeById(JsonNode canvas, String id) {
        for (JsonNode n : canvas.path("nodes")) {
            if (id.equals(n.path("id").asText())) {
                return n;
            }
        }
        throw new AssertionError("画布里找不到节点 " + id);
    }

    @Test
    @DisplayName("三个子任务编译成 START→t1→t2→t3→END 串行链，全 SEQ 边、无跳过")
    void compilesSerialChain() throws Exception {
        var compiled = compile(List.of(
                task(1, "需求澄清与范围共识", "product_analyst", "REVIEW", null),
                task(2, "架构方案", "architect", "AUTO", List.of(1)),
                task(3, "接口与库表详设", "developer", "REVIEW", List.of(2))));

        JsonNode canvas = canvas(compiled);
        assertEquals(5, canvas.path("nodes").size(), "START + 3 子任务 + END");
        assertEquals(List.of("start", "t1", "t2", "t3", "end"), ids(canvas, null));
        assertEquals(List.of("t1", "t2", "t3"), ids(canvas, "AGENT"));
        assertTrue(compiled.skipped().isEmpty(), "全部 agent 已发布，不该有跳过");

        JsonNode edges = canvas.path("edges");
        assertEquals(4, edges.size());
        for (JsonNode e : edges) {
            assertEquals("SEQ", e.path("type").asText(), "引擎 P0 只认 SEQ 线性链");
        }
        // 链必须真的串起来：前一个的 to 是后一个的 from
        assertEquals("start", edges.get(0).path("from").asText());
        assertEquals("end", edges.get(3).path("to").asText());

        // run 输入按 instance_id 构造任务卡，id 必须与节点一一对应
        assertEquals(3, compiled.tasks().size());
        assertEquals(List.of("t1", "t2", "t3"), compiled.tasks().stream().map(t -> t.get("instance_id")).toList());
    }

    @Test
    @DisplayName("END 不是子任务、没有任务卡：不得给它生成 $.start.output.tasks.end 这种必然取空的引用")
    void endNodeCarriesNoTaskCardMapping() throws Exception {
        var compiled = compile(List.of(
                task(1, "需求澄清", "product_analyst", "REVIEW", null),
                task(2, "架构方案", "architect", "REVIEW", List.of(1))));

        JsonNode canvas = canvas(compiled);
        String endId = ids(canvas, "END").get(0);
        JsonNode endEdge = null;
        for (JsonNode e : canvas.path("edges")) {
            if (endId.equals(e.path("to").asText())) {
                endEdge = e;
            }
        }
        assertTrue(endEdge != null, "应有指向 END 的边");

        for (JsonNode m : endEdge.path("mapping")) {
            String from = m.path("from").asText();
            String to = m.path("to").asText();
            assertFalse(from.contains(".tasks."), "END 的 mapping 不该引用任务卡：" + from);
            assertFalse("$.input.current_task".equals(to), "END 不该有 current_task：" + to);
        }
        // 阶段背景字段仍然要传下去，否则 END 连项目名都看不到
        boolean hasProjectField = false;
        for (JsonNode m : endEdge.path("mapping")) {
            if ("$.input.project".equals(m.path("to").asText())) {
                hasProjectField = true;
            }
        }
        assertTrue(hasProjectField, "END 仍应拿到 project 等阶段背景字段");
    }

    @Test
    @DisplayName("子任务取自己的任务卡，上游是 agent 时产出作为 upstream_output 传下去")
    void agentEdgeCarriesTaskCardAndUpstreamOutput() throws Exception {
        var compiled = compile(List.of(
                task(1, "需求澄清", "product_analyst", "REVIEW", null),
                task(2, "架构方案", "architect", "REVIEW", List.of(1))));

        JsonNode edges = canvas(compiled).path("edges");
        JsonNode toT2 = null;
        for (JsonNode e : edges) {
            if ("t2".equals(e.path("to").asText())) {
                toT2 = e;
            }
        }
        assertTrue(toT2 != null);
        List<String> tos = new ArrayList<>();
        for (JsonNode m : toT2.path("mapping")) {
            tos.add(m.path("to").asText());
            if ("$.input.current_task".equals(m.path("to").asText())) {
                assertEquals("$.start.output.tasks.t2", m.path("from").asText(), "任务卡要按节点 id 取");
            }
        }
        assertTrue(tos.contains("$.input.current_task"));
        assertTrue(tos.contains("$.input.upstream_output"), "上游 agent 产出必须传递，否则后面的子任务看不到已做出的结论");
    }

    @Test
    @DisplayName("未发布 agent 的子任务进 skipped 并说明原因，节点序号保持连续不留空洞")
    void unpublishedAgentIsSkippedAndReported() throws Exception {
        var compiled = compile(List.of(
                task(1, "需求澄清", "product_analyst", "REVIEW", null),
                task(2, "幽灵任务", "ghost_agent", "REVIEW", List.of(1)),
                task(3, "详设", "developer", "REVIEW", List.of(2))));

        assertEquals(1, compiled.skipped().size());
        Map<String, Object> s = compiled.skipped().get(0);
        assertEquals("幽灵任务", s.get("name"));
        assertTrue(String.valueOf(s.get("reason")).contains("无已发布版本"),
                "要写清为什么被跳过，实际：" + s.get("reason"));

        JsonNode canvas = canvas(compiled);
        // 被跳过的不占节点位：t1/t2 而不是 t1/t3
        assertEquals(List.of("t1", "t2"), ids(canvas, "AGENT"));
        assertEquals(List.of("start", "t1", "t2", "end"), ids(canvas, null));
    }

    @Test
    @DisplayName("没有任何可执行子任务时直接失败，不能发布一条空流程")
    void allUnpublishedFailsLoud() {
        var ex = assertThrows(IllegalStateException.class, () -> compile(List.of(
                task(1, "幽灵任务", "ghost_agent", "REVIEW", null))));
        assertTrue(ex.getMessage().contains("没有任何可执行的子任务"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("approval_mode=AUTO 不挂决策门；REVIEW/FORCE 都挂门且 onReject=REDO")
    void approvalModeDecidesGate() throws Exception {
        var compiled = compile(List.of(
                task(1, "自动跑完就行", "product_analyst", "AUTO", null),
                task(2, "要人审", "architect", "REVIEW", List.of(1)),
                task(3, "强制人审", "developer", "FORCE", List.of(2))));

        JsonNode canvas = canvas(compiled);
        assertFalse(nodeById(canvas, "t1").has("decisionGate"), "AUTO 不该挂门");
        for (String id : List.of("t2", "t3")) {
            JsonNode gate = nodeById(canvas, id).path("decisionGate");
            assertTrue(gate.isObject(), id + " 应挂决策门");
            assertTrue(gate.path("enabled").asBoolean());
            assertEquals("REDO", gate.path("onReject").asText(), "驳回要让 agent 按批注重生成");
        }
    }

    @Test
    @DisplayName("乱序输入按 depends 拓扑重排，t1..tN 反映真实执行顺序")
    void tasksAreTopologicallyOrdered() throws Exception {
        var compiled = compile(List.of(
                task(3, "第三步", "developer", "AUTO", List.of(2)),
                task(1, "第一步", "product_analyst", "AUTO", null),
                task(2, "第二步", "architect", "AUTO", List.of(1))));

        JsonNode canvas = canvas(compiled);
        assertEquals("第一步", nodeById(canvas, "t1").path("name").asText());
        assertEquals("第二步", nodeById(canvas, "t2").path("name").asText());
        assertEquals("第三步", nodeById(canvas, "t3").path("name").asText());
    }

    @Test
    @DisplayName("流程名带阶段与轮次，定义列表里能认出是哪条阶段链")
    void metaNamesTheStageRun() throws Exception {
        var compiled = compile(List.of(task(1, "需求澄清", "product_analyst", "REVIEW", null)));
        JsonNode meta = canvas(compiled).path("meta");
        assertTrue(meta.path("name").asText().contains("REQUIREMENT"));
        assertTrue(meta.path("name").asText().contains("第1轮"));
        assertEquals(0, canvas(compiled).path("definitionVersion").asInt());
    }
}
