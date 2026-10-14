package io.xuanji.server.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.engine.RunService;
import io.xuanji.server.repository.ProjectRepository;
import io.xuanji.server.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收口总结的子任务完成度口径（{@link ProjectService#draftStageSummary}）。
 *
 * <p>线上实测踩过：run 里 t1/t2/t3 全部 SUCCESS，收口总结仍写「0 / 3 完成」，并把三个子任务
 * 全列为未决项——因为它读的是 {@code project_work_node} 台账，而台账<b>没有从 run 回写的通道</b>。
 * 后果不是显示难看，而是 {@code pending_items} 永远非空、阶段推进只能靠人工豁免才能走。</p>
 *
 * <p>所以口径必须与推进门同源：<b>以批准规划编译出来的那条 run 为唯一真相</b>，
 * 只有还没有执行体（规划未批准/未起跑）时才退回台账。这里把这条钉死。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProjectStageSummaryTest {

    private static final LoginUser USER = new LoginUser(
            1L, "admin", "管理员", 1L, true, false, "ALL", Set.of("project"));
    private static final String WS = "ws_summary_test";

    @Mock
    private ProjectRepository repo;
    @Mock
    private WorkspaceRepository workspaceRepo;
    @Mock
    private RunService runService;
    /** ProjectService 的 objectMapper 是字段注入，给真的才能跑到 toJson */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ProjectService service;

    @BeforeEach
    void stubProject() {
        lenient().when(workspaceRepo.findById(WS)).thenReturn(List.of(Map.of(
                "id", WS, "name", "订单中心改造", "binding_type", "PROJECT",
                "current_stage", "REQUIREMENT", "project_mode", "NEW", "created_by", "admin")));
        lenient().when(repo.findNodesByWorkspace(WS)).thenReturn(List.of());
        lenient().when(repo.findArtifactsByWorkspace(WS)).thenReturn(List.of());
    }

    private static Map<String, Object> nodeRun(String nodeId, String name, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node_id", nodeId);
        m.put("node_name", name);
        m.put("status", status);
        return m;
    }

    /** 让「当前阶段有已批准规划 + 已起跑的 run」成立，run 的节点状态由调用方给。 */
    private void approvedRunWith(List<Map<String, Object>> nodeRuns) {
        when(repo.findCurrentStagePlan(WS, "REQUIREMENT", 1))
                .thenReturn(List.of(Map.of("status", "APPROVED", "exec_run_id", "r_1")));
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("status", "SUCCESS");
        run.put("nodes", nodeRuns);
        when(runService.getRun("r_1")).thenReturn(run);
    }

    private String capturedSummaryMd() {
        ArgumentCaptor<String> md = ArgumentCaptor.forClass(String.class);
        verify(repo).insertStageSummary(eq(WS), eq("REQUIREMENT"), eq(1), md.capture(),
                org.mockito.ArgumentMatchers.anyString());
        return md.getValue();
    }

    private String capturedPendingItems() {
        ArgumentCaptor<String> pending = ArgumentCaptor.forClass(String.class);
        verify(repo).insertStageSummary(eq(WS), eq("REQUIREMENT"), eq(1),
                org.mockito.ArgumentMatchers.anyString(), pending.capture());
        return pending.getValue();
    }

    @Test
    @DisplayName("run 里子任务全成功：总结写 3/3、遗留项为空，不再因为台账 NOT_STARTED 卡住推进")
    void allTasksSuccessGivesCleanSummary() {
        approvedRunWith(List.of(
                nodeRun("start", "阶段输入", "SUCCESS"),
                nodeRun("t1", "需求澄清与范围共识", "SUCCESS"),
                nodeRun("t2", "需求规格与验收标准", "SUCCESS"),
                nodeRun("t3", "优先级与交付排期", "SUCCESS"),
                nodeRun("end", "阶段交付", "SUCCESS")));

        service.draftStageSummary(USER, WS);

        String md = capturedSummaryMd();
        assertTrue(md.contains("3 / 3"), "应统计到 3 个已完成子任务，实际：" + md);
        assertTrue(md.contains("未完成子任务：无"), md);
        assertTrue(capturedPendingItems().equals("[]"),
                "遗留项必须为空，否则推进门会强制人工豁免，实际：" + capturedPendingItems());
    }

    @Test
    @DisplayName("start/end 是阶段输入与交付，不计入子任务完成度")
    void startAndEndAreNotCountedAsSubtasks() {
        approvedRunWith(List.of(
                nodeRun("start", "阶段输入", "SUCCESS"),
                nodeRun("t1", "需求澄清", "SUCCESS"),
                nodeRun("end", "阶段交付", "SUCCESS")));

        service.draftStageSummary(USER, WS);

        String md = capturedSummaryMd();
        assertTrue(md.contains("1 / 1"), "只有 t1 算子任务，实际：" + md);
    }

    @Test
    @DisplayName("有子任务没跑成功：列进未完成并落到 pending_items，推进时必须由人豁免")
    void failedTaskBecomesPendingItem() {
        approvedRunWith(List.of(
                nodeRun("start", "阶段输入", "SUCCESS"),
                nodeRun("t1", "需求澄清", "SUCCESS"),
                nodeRun("t2", "需求规格与验收标准", "FAILED"),
                nodeRun("t3", "优先级与交付排期", "RUNNING"),
                nodeRun("end", "阶段交付", "PENDING")));

        service.draftStageSummary(USER, WS);

        String md = capturedSummaryMd();
        assertTrue(md.contains("1 / 3"), md);
        String pending = capturedPendingItems();
        assertTrue(pending.contains("需求规格与验收标准(FAILED)"), pending);
        assertTrue(pending.contains("优先级与交付排期(RUNNING)"), pending);
    }

    @Test
    @DisplayName("还没有执行体（规划未起跑）：退回台账口径，总结不能是空壳")
    void fallsBackToLedgerWhenNoRun() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("status", "PENDING");
        plan.put("exec_run_id", null);
        when(repo.findCurrentStagePlan(WS, "REQUIREMENT", 1)).thenReturn(List.of(plan));
        when(repo.findNodesByWorkspace(WS)).thenReturn(List.of(Map.of(
                "name", "需求澄清", "type", "DISCUSS", "status", "DONE",
                "stage", "REQUIREMENT", "iteration", 1)));

        service.draftStageSummary(USER, WS);

        String md = capturedSummaryMd();
        assertTrue(md.contains("1 / 1"), "无 run 时应按台账统计，实际：" + md);
    }

    @Test
    @DisplayName("规划不存在时不抛异常，总结仍要产出（人得能看到阶段现状）")
    void noPlanStillProducesSummary() {
        when(repo.findCurrentStagePlan(WS, "REQUIREMENT", 1)).thenReturn(List.of());

        service.draftStageSummary(USER, WS);

        String md = capturedSummaryMd();
        assertTrue(md.contains("0 / 0"), md);
    }
}
