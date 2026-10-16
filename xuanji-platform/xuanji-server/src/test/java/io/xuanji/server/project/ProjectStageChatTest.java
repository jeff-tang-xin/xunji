package io.xuanji.server.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.engine.AgentNodeExecutor;
import io.xuanji.server.repository.ProjectRepository;
import io.xuanji.server.repository.WorkspaceRepository;
import io.xuanji.core.runtime.AgentRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段级对话的 agent 选择与上下文口径（{@link ProjectChatService#chatByStage}）。
 *
 * <p>这条路的出现本身就是因为 V27 把对话弄断了：{@code chatByNode} 依赖
 * {@code project_work_node} 台账节点与已停用的 {@code project_template} 选 agent，
 * 而 V27 之后新项目一个台账节点都没有（实测全库最后一条台账是 V26 时代写的），
 * 于是界面上既点不出对话入口、硬拼 nodeId 调用也只拿到 400。这里钉住新口径：
 * 对话对象是「阶段 + 可选子任务」，agent 来自规划的 tasks，阶段规范本体交给 skill。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProjectStageChatTest {

    private static final LoginUser USER = new LoginUser(
            1L, "admin", "管理员", 1L, true, false, "ALL", Set.of("project"));
    private static final String WS = "ws_chat_test";

    @Mock
    private AgentRuntime runtime;
    @Mock
    private AgentNodeExecutor agentNodes;
    @Mock
    private ProjectRepository repo;
    @Mock
    private WorkspaceRepository workspaceRepo;
    @Mock
    private io.xuanji.server.project.orchestrator.StageOrchestrator orchestrator;
    @Mock
    private io.xuanji.server.project.orchestrator.StageStepRepository stepRepo;
    @Mock
    private io.xuanji.server.repository.RunRepository runRepo;
    @Mock
    private StageAutoDrive autoDrive;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ProjectChatService service;

    private void workspaceAt(String stage) {
        lenient().when(workspaceRepo.findById(WS)).thenReturn(List.of(Map.of(
                "id", WS, "name", "订单中心改造", "binding_type", "PROJECT",
                "current_stage", stage, "project_mode", "NEW", "created_by", "admin")));
    }

    /** 让双轨分流走旧链路（LiteFlow run 活跃），本测试类钉住的是旧链路的 agent 解析口径。 */
    private void legacyActive(String stage) {
        lenient().when(repo.findLatestStagePlan(WS, stage))
                .thenReturn(List.of(Map.of("exec_run_id", "r_legacy")));
        lenient().when(runRepo.findRunForResume("r_legacy"))
                .thenReturn(Map.of("status", "RUNNING"));
    }

    private void planJson(String json) {
        when(repo.findCurrentStagePlan(WS, "REQUIREMENT", 1))
                .thenReturn(List.of(Map.of("status", "APPROVED", "plan_json", json)));
    }

    /** 取 buildSpecByAgentName 的四个入参：agentName / sessionId / guidance / wsId（批六起带项目上下文启用内置工具） */
    private String[] specArgs() {
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> session = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> guidance = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> wsId = ArgumentCaptor.forClass(String.class);
        verify(agentNodes).buildSpecByAgentName(name.capture(), session.capture(), guidance.capture(), wsId.capture());
        return new String[]{name.getValue(), session.getValue(), guidance.getValue()};
    }

    @Test
    @DisplayName("没传 task_key：不读规划（省一次查询），按阶段兜底选 agent，会话按「项目+阶段+迭代」聚合")
    void fallsBackToStageDefaultAgent() {
        workspaceAt("REQUIREMENT");
        legacyActive("REQUIREMENT");

        service.chatByStage(USER, WS, Map.of("message", "帮我梳理范围"));

        String[] args = specArgs();
        assertEquals("product_analyst", args[0]);
        assertEquals("proj-" + WS + "-stage-REQUIREMENT-1", args[1]);
        assertTrue(args[2].contains("订单中心改造"), args[2]);
        assertTrue(args[2].contains("未指定具体子任务"), args[2]);
        // 钉住这个短路：没指定子任务就不该去查规划
        verify(repo, never()).findCurrentStagePlan(any(), any(), anyInt());
    }

    @Test
    @DisplayName("指定 task_key：agent 取该子任务的 agents[0]，任务边界与评审要求进上下文")
    void taskKeyDrivesAgentAndScope() {
        workspaceAt("REQUIREMENT");
        legacyActive("REQUIREMENT");
        planJson("{\"tasks\":["
                + "{\"key\":\"1\",\"name\":\"需求澄清\",\"agents\":[\"project_manager\"]},"
                + "{\"key\":\"2\",\"name\":\"技术方案与架构决策\",\"agents\":[\"architect\"],"
                + "\"scope\":\"模块划分、依赖方向、ADR\",\"approval_mode\":\"FORCE\"}]}");

        service.chatByStage(USER, WS, Map.of("message", "出方案", "task_key", "2"));

        String[] args = specArgs();
        assertEquals("architect", args[0]);
        assertTrue(args[2].contains("技术方案与架构决策"), args[2]);
        assertTrue(args[2].contains("模块划分、依赖方向、ADR"), args[2]);
        // FORCE 门要告诉 agent 产出得被人评审，否则它会给出一个没法评审的半成品
        assertTrue(args[2].contains("人工评审"), args[2]);
    }

    @Test
    @DisplayName("task_key 与规划里的 key 类型不一致也能命中（实测后端 key 是字符串 '1'）")
    void keyTypeMismatchStillMatches() {
        workspaceAt("REQUIREMENT");
        legacyActive("REQUIREMENT");
        // 历史/手工规划里 key 可能是数字，前端传来的是字符串
        planJson("{\"tasks\":[{\"key\":1,\"name\":\"需求澄清\",\"agents\":[\"project_manager\"]}]}");

        service.chatByStage(USER, WS, Map.of("message", "先对齐范围", "task_key", "1"));

        assertEquals("project_manager", specArgs()[0]);
    }

    @Test
    @DisplayName("agents 为空时退回 participants，再退回阶段默认")
    void agentResolutionOrder() {
        workspaceAt("REQUIREMENT");
        legacyActive("REQUIREMENT");
        planJson("{\"tasks\":[{\"key\":\"1\",\"name\":\"联调\",\"participants\":[\"tester\"]}]}");

        service.chatByStage(USER, WS, Map.of("message", "准备联调", "task_key", "1"));

        assertEquals("tester", specArgs()[0]);
    }

    @Test
    @DisplayName("规划正文坏了不能拖垮对话：降级为无子任务上下文照常开聊")
    void brokenPlanJsonDegrades() {
        workspaceAt("REQUIREMENT");
        legacyActive("REQUIREMENT");
        planJson("{\"tasks\":[ 坏掉的 JSON");

        service.chatByStage(USER, WS, Map.of("message", "继续", "task_key", "1"));

        String[] args = specArgs();
        assertEquals("product_analyst", args[0]);
        assertTrue(args[2].contains("未指定具体子任务"), args[2]);
    }

    @Test
    @DisplayName("常驻运维阶段的会话带迭代号，与推进门算的 iteration 同口径")
    void operateStageSessionCarriesIteration() {
        workspaceAt("OPERATE");
        legacyActive("OPERATE");
        lenient().when(repo.maxIteration(WS)).thenReturn(3);
        lenient().when(repo.findCurrentStagePlan(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        service.chatByStage(USER, WS, Map.of("message", "线上有个 Bug"));

        String[] args = specArgs();
        assertEquals("devops", args[0]);
        assertEquals("proj-" + WS + "-stage-OPERATE-3", args[1]);
        assertTrue(args[2].contains("第 3 轮"), args[2]);
    }

    @Test
    @DisplayName("空消息直接 400，不要开一条没有内容的 SSE")
    void blankMessageRejected() {
        workspaceAt("REQUIREMENT");
        legacyActive("REQUIREMENT");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.chatByStage(USER, WS, Map.of("message", "   ")));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }
}
