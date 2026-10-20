package io.xuanji.server.project;

import static io.xuanji.server.project.ProjectSupport.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.server.auth.DataScopeFilter;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.engine.DefinitionService;
import io.xuanji.server.engine.RunFinishedEvent;
import io.xuanji.server.engine.RunService;
import io.xuanji.server.project.ProjectChatService;
import io.xuanji.server.project.StagePlanCompiler;
import io.xuanji.server.project.orchestrator.StageStep;
import io.xuanji.server.project.orchestrator.StageStepRepository;
import io.xuanji.server.project.orchestrator.StepStatus;
import io.xuanji.server.project.orchestrator.StepType;
import io.xuanji.server.repository.ProjectRepository;
import io.xuanji.server.repository.WorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final String PM_AGENT = "project_manager";
    private static final Set<String> FORCE_TYPES = Set.of("RELEASE", "DEPLOY", "DB", "DATABASE", "DATA_FIX");
    private static final String MODE_LEGACY = "LEGACY";
    @Autowired
    private ProjectRepository repo;
    @Autowired
    private WorkspaceRepository workspaceRepo;
    @Autowired
    private RunService runService;
    @Autowired
    private ProjectChatService chatService;
    @Autowired
    private StageStepRepository stepRepo;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DefinitionService definitionService;
    @Autowired
    private StagePlanCompiler planCompiler;
    @Autowired
    private ProjectTemplateService templateService;
    @Autowired
    private io.xuanji.server.project.orchestrator.RequirementRepository requirementRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private io.xuanji.server.project.orchestrator.TestCaseRepository testCaseRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private io.xuanji.server.project.orchestrator.BugRepository bugRepo;
    @Autowired
    private io.xuanji.server.project.orchestrator.TaskRunner taskRunner;
    @Autowired
    private io.xuanji.server.asset.CryptoUtil crypto;
    @Autowired
    private io.xuanji.server.project.orchestrator.DiscussionEngine discussionEngine;
    @Autowired
    private io.xuanji.server.project.orchestrator.StageOrchestrator stageOrchestrator;
    @Autowired
    private io.xuanji.server.project.orchestrator.ProjectEventBus projectEvents;
    @Value(value="${xuanji.workspace.root:./workspaces}")
    private String workspaceRoot;
    private final ExecutorService autoFlowExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private static final String STAGE_TRIGGER = "STAGE_PLAN";

    @Transactional
    public Map<String, Object> create(LoginUser user, Map<String, Object> body) {
        String mode;
        String name;
        String string = name = body.get("name") == null ? null : body.get("name").toString().trim();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "name is required");
        }
        this.validateName(name);
        String template = body.get("template") == null ? null : body.get("template").toString().trim();
        String string2 = mode = body.get("project_mode") == null ? "NEW" : body.get("project_mode").toString().trim().toUpperCase();
        if (!"NEW".equals(mode) && !MODE_LEGACY.equals(mode)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "project_mode 仅支持 NEW（新项目）/ LEGACY（老项目维护），当前值: " + mode);
        }
        String currentStage = MODE_LEGACY.equals(mode) ? STAGE_OPERATE : STAGE_REQUIREMENT;
        String wsId = "ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String rootPath = (String)(this.workspaceRoot.endsWith("/") || this.workspaceRoot.endsWith("\\") ? this.workspaceRoot : this.workspaceRoot + "/") + name;
        this.workspaceRepo.insertProject(wsId, name, rootPath, user.orgId(), user.username(), template, currentStage, mode);
        
        this.createProjectDirectories(rootPath);
        // 不再自动生成规划草稿——规划由主控在对话里通过 plan_steps 发起（对话触发原则）：
        // 用户还没给需求就出现「批准规划」操作，是流程被人肉按钮驱动，违背 AI 驱动内核的设计。
        if (template != null && !template.isBlank()) {
            templateService.applyTemplateStages(wsId, template, user.username());
        }
        Object reqWf = body.get("requirement_workflow");
        String boundWf = null;
        if (reqWf != null && !String.valueOf(reqWf).isBlank()) {
            long refId;
            String wfRef = String.valueOf(reqWf).trim();
            try {
                refId = Long.parseLong(wfRef);
            }
            catch (NumberFormatException e) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "requirement_workflow 应为工作流定义 id，当前值: " + wfRef);
            }
            Map<String, Object> refDef = this.definitionService.get(refId, user);
            if (!"PUBLISHED".equals(String.valueOf(refDef.get("status")))) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "绑定的工作流 #" + wfRef + " 当前是 " + String.valueOf(refDef.get("status")) + "，仅已发布的工作流可绑定");
            }
            LinkedHashMap<String, Object> cap = new LinkedHashMap<String, Object>();
            for (String k : CAPABILITY_KEYS) {
                cap.put(k, "workflow".equals(k) ? wfRef : List.of());
            }
            this.repo.upsertStageCapability(wsId, STAGE_REQUIREMENT, toJson(objectMapper, cap), "创建项目时预设", user.username());
            boundWf = wfRef;
        }
        List<Map<String, Object>> presetServers = opsEntries(body.get("servers"), false, List.of());
        List<Map<String, Object>> presetDatabases = opsEntries(body.get("databases"), true, List.of());
        String presetDeploySpec = body.get("deploy_spec") == null ? "" : String.valueOf(body.get("deploy_spec")).trim();
        List<String> presetConnectors = strList(objectMapper, body.get("connectors"));
        if (!presetServers.isEmpty() || !presetDatabases.isEmpty() || !presetDeploySpec.isEmpty() || !presetConnectors.isEmpty()) {
            LinkedHashMap<String, Object> cap = new LinkedHashMap<String, Object>();
            for (String k : CAPABILITY_KEYS) {
                cap.put(k, "workflow".equals(k) ? "" : "deploy_spec".equals(k) ? "" : List.of());
            }
            List<Map<String, Object>> existing = this.repo.findStageCapability(wsId, STAGE_OPERATE);
            if (!existing.isEmpty()) {
                cap.putAll(parseJsonMap(objectMapper, existing.get(0).get("capability_json")));
            }
            if (!presetServers.isEmpty()) {
                cap.put("servers", presetServers);
            }
            // 新项目没有平台级数据源连接器可绑——数据库以地址直登（host:port/库名+说明），
            // 部署也不预置目录，只有服务器与部署规范（目录/启动方式由规范约定）
            // 运维连接对象列表：{address, username?, password(加密), note}——地址与凭证平台托管，
            // AI 只见引用 ID（server-1/db-1），连接由 OpsConnectionManager 代理
            if (!presetDatabases.isEmpty()) {
                cap.put("databases", presetDatabases);
            }
            if (!presetDeploySpec.isEmpty()) {
                cap.put("deploy_spec", presetDeploySpec);
            }
            if (!presetConnectors.isEmpty()) {
                cap.put("connectors", presetConnectors);
            }
            this.repo.upsertStageCapability(wsId, STAGE_OPERATE, toJson(objectMapper, cap), "创建项目时绑定", user.username());
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", wsId);
        result.put("name", name);
        result.put("root_path", rootPath);
        result.put("status", "ACTIVE");
        result.put("template", template);
        result.put("project_mode", mode);
        result.put("current_stage", currentStage);
       
        result.put("plan_id", null);
        result.put("plan_status", "NONE");
        if (boundWf != null) {
            result.put("requirement_workflow", boundWf);
        }
        return result;
    }

    public List<Map<String, Object>> list(LoginUser user) {
        DataScopeFilter.Clause c = DataScopeFilter.of(user, "org_id", "created_by");
        return this.workspaceRepo.findProjectsByScope(c.sql(), c.args().toArray());
    }

    public Map<String, Object> get(LoginUser user, String id) {
        Map ws = (Map)this.workspaceRepo.findById(id).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "project not found"));
        this.requireOwner(user, ws);
        if (!"PROJECT".equals(ws.get("binding_type"))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该 workspace 不是项目（binding_type=" + String.valueOf(ws.get("binding_type")) + "）");
        }
        List<Map<String, Object>> artifacts = this.repo.findArtifactsByWorkspace(id);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", ws.get("id"));
        result.put("name", ws.get("name"));
        result.put("root_path", ws.get("root_path"));
        result.put("status", ws.get("status"));
        result.put("created_by", ws.get("created_by"));
        result.put("created_at", ws.get("created_at"));
        result.put("template", ws.get("template_code"));
        result.put("project_mode", ws.get("project_mode"));
        String currentStage = this.resolveCurrentStage(ws.get("current_stage"));
        result.put("current_stage", currentStage);
        result.put("stages", this.buildStageViews(id, currentStage));
        result.put("artifacts", artifacts);
        return result;
    }

    
    @Transactional
    
    private Long parseWorkflowRefDefinitionId(Object workflowRefJson) {
        if (workflowRefJson == null) {
            return null;
        }
        try {
            Object parsed = this.objectMapper.readValue(workflowRefJson.toString(), Object.class);
            if (parsed instanceof Map) {
                Map map = (Map)parsed;
                Object id = map.get("definitionId");
                if (id instanceof Number) {
                    Number num = (Number)id;
                    return num.longValue();
                }
                if (id != null) {
                    try {
                        return Long.parseLong(id.toString().trim());
                    }
                    catch (NumberFormatException numberFormatException) {}
                }
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return null;
    }

    private Map<String, Object> parseInputRefs(Object inputRefsJson) {
        if (inputRefsJson == null) {
            return Map.of();
        }
        try {
            Object parsed = this.objectMapper.readValue(inputRefsJson.toString(), Object.class);
            if (parsed instanceof Map) {
                Map<?, ?> map = (Map<?, ?>)parsed;
                LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    result.put(String.valueOf(e.getKey()), e.getValue());
                }
                return result;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return Map.of();
    }

    
    
    @Transactional
    
    private Map<String, Object> requireProject(LoginUser user, String wsId) {
        Map ws = (Map)this.workspaceRepo.findById(wsId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "project not found"));
        this.requireOwner(user, ws);
        if (!"PROJECT".equals(ws.get("binding_type"))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该 workspace 不是项目（binding_type=" + String.valueOf(ws.get("binding_type")) + "）");
        }
        return ws;
    }

    private String resolveCurrentStage(Object rawStage) {
        String s = rawStage == null ? "" : String.valueOf(rawStage).trim().toUpperCase();
        // V38 起台账表已删：current_stage 有值即真相（V27 后创建即写入），无值兜底第一阶段
        return STAGE_ORDER.contains(s) ? s : STAGE_ORDER.get(0);
    }

    private List<Map<String, Object>> buildStageViews(String wsId, String currentStage) {
        // V38 起台账表已删：阶段视图状态单一来自 stage_plan/run（stageStatusFromRun），无 run 时 PENDING
        ArrayList<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (String stage : STAGE_ORDER) {
            String status = this.stageStatusFromRun(wsId, stage);
            if (status == null) {
                status = "PENDING";
            }
            LinkedHashMap<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("stage", stage);
            v.put("label", stageLabel(stage));
            v.put("status", status);
            v.put("resident", STAGE_OPERATE.equals(stage));
            v.put("current", stage.equals(currentStage));
            v.put("iterations", List.of());
            v.put("node_count", 0);
            v.put("done_count", 0);
            v.put("nodes", List.of());
            views.add(v);
        }
        return views;
    }

    private String stageStatusFromRun(String wsId, String stage) {
        try {
            String rs;
            int it = this.currentIteration(wsId, stage);
            Map plan = this.repo.findCurrentStagePlan(wsId, stage, it).stream().findFirst().orElse(null);
            if (plan == null) {
                return null;
            }
            if (!"APPROVED".equals(String.valueOf(plan.get("status")))) {
                return "RUNNING";
            }
            if (plan.get("exec_run_id") == null) {
                return "RUNNING";
            }
            return switch (rs = String.valueOf(this.runService.getRun(String.valueOf(plan.get("exec_run_id"))).get("status"))) {
                case "SUCCESS" -> "DONE";
                case "FAILED" -> "BLOCKED";
                default -> "RUNNING";
            };
        }
        catch (Exception e) {
            log.debug("stageStatusFromRun fallback to ledger: ws={} stage={} err={}", new Object[]{wsId, stage, e.getMessage()});
            return null;
        }
    }

    @Transactional
    public Map<String, Object> advanceStage(LoginUser user, String wsId, Map<String, Object> body) {
        boolean alreadyWaived;
        Map<String, Object> ws = this.requireProject(user, wsId);
        String current = this.resolveCurrentStage(ws.get("current_stage"));
        if (STAGE_OPERATE.equals(current)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "运维阶段为常驻阶段，不可继续推进，请在运维阶段内发起迭代");
        }
        String next = STAGE_ORDER.get(STAGE_ORDER.indexOf(current) + 1);
        int iteration = this.currentIteration(wsId, current);
        Map stagePlan = this.repo.findCurrentStagePlan(wsId, current, iteration).stream().findFirst().orElse(null);
        if (stagePlan == null) {
            // 对话驱动模式（plan_steps 台账）：本阶段没有 LiteFlow 规划头与 run，收口按执行台账判定——
            // 无活跃步骤（EXECUTING/WAITING/PLANNED）即视为实质完成放行。此前这里直接 400「还没有规划」，
            // 而重规划又因已销账行占住 seq 撞唯一键，形成「收口要规划头、规划头建不了」的死锁（测试003 实锤）。
            if (this.stepRepo.hasActiveStep(wsId, current, iteration)) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST,
                        stageLabel(current) + "阶段仍有未了结的小流程（执行中/等待答复/规划未执行），不能收口；"
                                + "先执行或 skip_step 处理掉，等待答复的决策等用户答复后再收口");
            }
        } else {
            if (!"APPROVED".equals(String.valueOf(stagePlan.get("status")))) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, stageLabel(current) + "阶段规划当前是 " + String.valueOf(stagePlan.get("status")) + "，需人批准并开始执行后才能推进");
            }
            if (stagePlan.get("exec_run_id") == null) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, stageLabel(current) + "阶段规划已批准但没有生成执行体（exec_run_id 为空），请驳回该规划后重发，或检查服务端日志中的编译失败原因");
            }
        }
        String stageRunId = stagePlan == null ? null : String.valueOf(stagePlan.get("exec_run_id"));
        if (stageRunId != null) {
            Map<String, Object> stageRun = this.runService.getRun(stageRunId);
            String runStatus = String.valueOf(stageRun.get("status"));
            if (!"SUCCESS".equals(runStatus)) {
                ArrayList<String> open = new ArrayList<String>();
                for (Map<String, Object> nr : mapList(objectMapper, stageRun.get("nodes"))) {
                    String st = String.valueOf(nr.get("status"));
                    if ("SUCCESS".equals(st)) continue;
                    open.add(strOr(nr.get("node_name"), String.valueOf(nr.get("node_id"))) + "（" + st + "）");
                }
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, stageLabel(current) + "阶段流程尚未跑完（run " + stageRunId + " 状态 " + runStatus + "）" + (String)(open.isEmpty() ? "" : "，未走完的节点：" + String.join("、", open)) + "；若停在决策门，请先在待决策中完成审批");
            }
        }
        Map summary = (Map)this.repo.findCurrentStageSummary(wsId, current, iteration).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "请先由 PM 产出「" + stageLabel(current) + "阶段收口总结」并经人批准，再推进阶段"));
        if (!"APPROVED".equals(String.valueOf(summary.get("status")))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "收口总结当前为 " + String.valueOf(summary.get("status")) + "，需人批准后才能推进阶段");
        }
        List<Object> openItems = objList(objectMapper, summary.get("pending_items"));
        boolean bl = alreadyWaived = summary.get("waived_by") != null;
        if (!openItems.isEmpty() && !alreadyWaived) {
            String reason;
            String string = reason = body == null || body.get("waive_reason") == null ? "" : String.valueOf(body.get("waive_reason")).trim();
            if (reason.isBlank()) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "收口总结仍有 " + openItems.size() + " 项遗留未决；确认可以带着走下一步时，请提交 waive_reason 显式豁免（将留档到人）");
            }
            this.repo.waiveStageSummary(((Number)summary.get("id")).longValue(), user.username(), reason);
        }
        this.workspaceRepo.updateCurrentStage(wsId, next);
        
        // 不再自动生成下一阶段规划草稿——下一阶段的规划同样由主控在对话里 plan_steps 发起，
        // 否则推进后立刻又出现「批准规划」操作，用户被迫批准一份自己没参与过的规划。
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("current_stage", next);
        result.put("advanced", true);
        result.put("from_stage", current);
        result.put("label", stageLabel(next));
        result.put("locked_stage", current);
        result.put("locked_iteration", iteration);
        result.put("plan_id", null);
        result.put("plan_status", "NONE");
        result.put("waived_open_items", openItems.size());
        return result;
    }

    @EventListener
    public void onRunFinished(RunFinishedEvent evt) {
        if (!"SUCCESS".equals(evt.status())) {
            return;
        }
        this.autoFlowExecutor.submit(() -> {
            try {
                this.autoAdvanceStage(evt.runId(), evt.definitionId());
            }
            catch (Exception e) {
                log.warn("阶段自动流转未完成（等人工介入）: run={} def={} -> {}", new Object[]{evt.runId(), evt.definitionId(), e.getMessage()});
            }
        });
    }

    private void autoAdvanceStage(String runId, long definitionId) {
        Long l;
        Map plan = this.repo.findStagePlanByExecDefinition(definitionId).stream().findFirst().orElse(null);
        if (plan == null) {
            return;
        }
        String wsId = String.valueOf(plan.get("workspace_id"));
        String stage = String.valueOf(plan.get("stage"));
        Map ws = this.workspaceRepo.findById(wsId).stream().findFirst().orElse(null);
        if (ws == null) {
            return;
        }
        if (!stage.equals(String.valueOf(ws.get("current_stage")))) {
            log.info("阶段自动流转跳过（阶段已变化）: ws={} planStage={} current={}", new Object[]{wsId, stage, ws.get("current_stage")});
            return;
        }
        Object v = ws.get("org_id");
        if (v instanceof Number) {
            Number n = (Number)v;
            l = n.longValue();
        } else {
            l = null;
        }
        LoginUser system = new LoginUser(null, "system", "系统自动流转", l, true, false, "ALL", Set.of());
        log.info("阶段自动流转开始: ws={} stage={} run={}", new Object[]{wsId, stage, runId});
        Map<String, Object> summary = this.draftStageSummary(system, wsId);
        long summaryId = ((Number)summary.get("summary_id")).longValue();
        this.decideStageSummary(system, wsId, summaryId, Map.of("decision", "APPROVE"));
        Map<String, Object> advanced = this.advanceStage(system, wsId, Map.of("waive_reason", "自动流转：收口遗留项带入下一阶段继续跟进"));
        long nextPlanId = ((Number)advanced.get("plan_id")).longValue();
        String nextStage = String.valueOf(advanced.get("current_stage"));
        log.info("阶段自动流转已推进: ws={} {} -> {} plan={}", new Object[]{wsId, stage, nextStage, nextPlanId});
        this.decidePlan(system, wsId, nextPlanId, Map.of("decision", "APPROVE"));
        log.info("阶段自动流转完成: ws={} 规划 {}（{}）已批准起跑", new Object[]{wsId, nextPlanId, nextStage});
    }

    @Transactional
    public Map<String, Object> draftStageSummary(LoginUser user, String wsId) {
        Object execRunId;
        Map<String, Object> ws = this.requireProject(user, wsId);
        String stage = this.resolveCurrentStage(ws.get("current_stage"));
        int iteration = this.currentIteration(wsId, stage);
        int done = 0;
        ArrayList<String> unfinished = new ArrayList<String>();
        ArrayList<String> artifacts = new ArrayList<String>();
        Map stagePlan = this.repo.findCurrentStagePlan(wsId, stage, iteration).stream().findFirst().orElse(null);
        Object v0 = execRunId = stagePlan == null ? null : stagePlan.get("exec_run_id");
        if (execRunId != null) {
            for (Map<String, Object> nr : mapList(objectMapper, this.runService.getRun(String.valueOf(execRunId)).get("nodes"))) {
                String nid = String.valueOf(nr.get("node_id"));
                if (nid.equalsIgnoreCase("start") || nid.equalsIgnoreCase("end")) continue;
                if ("SUCCESS".equals(String.valueOf(nr.get("status")))) {
                    ++done;
                    continue;
                }
                unfinished.add(strOr(nr.get("node_name"), nid) + "(" + String.valueOf(nr.get("status")) + ")");
            }
        }
        for (Map<String, Object> a : this.repo.findArtifactsByWorkspace(wsId)) {
            if (!stage.equals(String.valueOf(a.get("stage")))) continue;
            artifacts.add(String.valueOf(a.get("name")));
        }
        StringBuilder md = new StringBuilder();
        md.append("## ").append(stageLabel(stage)).append("阶段收口（第 ").append(iteration).append(" 轮）\n\n").append("- 子任务完成：").append(done).append(" / ").append(done + unfinished.size()).append("\n").append("- 未完成子任务：").append(unfinished.isEmpty() ? "无" : String.join("、", unfinished)).append("\n").append("- 本阶段留档产物：").append(artifacts.isEmpty() ? "无" : String.join("、", artifacts)).append("\n").append("- 结论与交付：待 PM 补充（当前为系统按库内事实自动汇总）\n").append("- 风险与遗留：见下方未决项，需人确认是否可带入下一阶段\n");
        ArrayList pendingItems = new ArrayList(unfinished);
        long id = this.repo.insertStageSummary(wsId, stage, iteration, md.toString(), toJson(objectMapper, pendingItems));
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("summary_id", id);
        out.put("stage", stage);
        out.put("iteration", iteration);
        out.put("status", "PENDING");
        out.put("pending_items", pendingItems);
        out.put("content_md", md.toString());
        return out;
    }

    @Transactional
    public Map<String, Object> decideStageSummary(LoginUser user, String wsId, long summaryId, Map<String, Object> body) {
        String status;
        String decision;
        this.requireProject(user, wsId);
        Map row = (Map)this.repo.findStageSummary(summaryId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "收口总结不存在"));
        if (!wsId.equals(row.get("workspace_id"))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "该收口总结不属于本项目");
        }
        String string = decision = body == null || body.get("decision") == null ? "" : body.get("decision").toString().trim().toUpperCase();
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "decision 仅支持 APPROVE / REJECT");
        }
        String string2 = status = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        if (this.repo.decideStageSummary(summaryId, status, user.username()) == 0) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.CONFLICT, "该收口总结已被处理，请刷新");
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("summary_id", summaryId);
        out.put("status", status);
        return out;
    }

    @Transactional
    public long draftStagePlan(LoginUser user, String wsId, String stage, int iteration) {
        Map<String, Object> plan = this.defaultPlanFor(stage);
        long planId = this.repo.insertStagePlan(wsId, stage, iteration, toJson(objectMapper, plan), (String)plan.get("deviation"));
        log.info("PM 阶段规划草稿已生成: project={} stage={} iteration={} plan_id={}", new Object[]{wsId, stage, iteration, planId});
        return planId;
    }

    private Map<String, Object> defaultPlanFor(String stage) {
        return switch (stage) {
            case STAGE_REQUIREMENT -> Map.of("stage", STAGE_REQUIREMENT, "participants", List.of("product_analyst", "architect", "dba", "developer", "tester"), "deviation", "默认保守拆分：需求阶段先谈成范围共识再写规格。PM 可按项目特征增删参与者与子任务。", "tasks", List.of(Map.of("key", 1, "name", "需求澄清与范围共识", "type", "DISCUSS", "agents", List.of("product_analyst"), "participants", List.of("product_analyst", "architect", "developer", "tester"), "scope", "把问题、目标、本期必做/不做、排除项谈成可判定结论", "trigger_mode", "AUTO", "approval_mode", "REVIEW"), Map.of("key", 2, "name", "需求规格与验收标准", "type", STAGE_REQUIREMENT, "agents", List.of("product_analyst"), "participants", List.of("product_analyst"), "scope", "逐条需求编号 + 可被测试直接执行的验收标准", "trigger_mode", "AUTO", "approval_mode", "REVIEW", "depends", List.of(Integer.valueOf(1))), Map.of("key", 3, "name", "优先级与交付排期", "type", STAGE_REQUIREMENT, "agents", List.of(PM_AGENT), "participants", List.of(PM_AGENT), "scope", "定 P0/P1/P2 与依赖顺序，形成开发阶段输入", "trigger_mode", "AUTO", "approval_mode", "AUTO", "depends", List.of(Integer.valueOf(2)))), "quality_gates", List.of("每条需求有唯一编号与可执行验收标准（Given/When/Then 或输入-输出对）", "非功能要求（性能/并发/安全/兼容/可观测）显式声明", "范围外清单成文，越界项单独标记并说明理由"));
            case "DEVELOP" -> Map.of("stage", "DEVELOP", "participants", List.of("architect", "developer", "tester", "dba"), "deviation", "默认保守拆分：方案先行、契约先行、冒烟不过不提测。PM 可按技术风险裁剪。", "tasks", List.of(Map.of("key", 1, "name", "技术方案与架构决策", "type", "ARCHITECTURE", "agents", List.of("architect"), "participants", List.of("architect", "dba", "developer"), "scope", "模块划分、依赖方向、关键取舍 ADR、容量估算", "trigger_mode", "AUTO", "approval_mode", "REVIEW"), Map.of("key", 2, "name", "接口契约与数据模型", "type", "DESIGN", "agents", List.of("architect"), "participants", List.of("architect", "developer", "tester"), "scope", "URL/入参/出参/错误码/幂等 + 表结构与迁移脚本", "trigger_mode", "AUTO", "approval_mode", "REVIEW", "depends", List.of(Integer.valueOf(1))), Map.of("key", 3, "name", "测试用例设计", "type", "TEST_CASE", "agents", List.of("tester"), "participants", List.of("tester", "product_analyst"), "scope", "需求×用例矩阵，含边界值与异常场景", "trigger_mode", "AUTO", "approval_mode", "AUTO", "depends", List.of(Integer.valueOf(2))), Map.of("key", 4, "name", "编码实现", "type", "DEVELOP", "agents", List.of("developer"), "participants", List.of("developer"), "scope", "按契约与垂直切片实现，每片自带测试", "trigger_mode", "AUTO", "approval_mode", "AUTO", "depends", List.of(Integer.valueOf(2))), Map.of("key", 5, "name", "冒烟自测", "type", "SMOKE", "agents", List.of("tester"), "participants", List.of("tester", "developer"), "scope", "主链路最小集全部通过才可提测", "trigger_mode", "AUTO", "approval_mode", "AUTO", "depends", List.of(Integer.valueOf(4))), Map.of("key", 6, "name", "测试执行与缺陷回归", "type", "TEST", "agents", List.of("tester"), "participants", List.of("tester", "developer", "product_analyst"), "scope", "按矩阵全量执行、缺陷闭环、出具量化报告", "trigger_mode", "AUTO", "approval_mode", "REVIEW", "depends", List.of(Integer.valueOf(3), Integer.valueOf(5)))), "quality_gates", List.of("关键取舍有 ADR：背景/备选/决定/后果，不允许只给结论", "接口契约先行，契约变更先改文档再改码并同步测试", "提交须通过编译与单测，冒烟不过不得提测", "数据变更以迁移脚本提交，先加列后删列保持向后兼容"));
            default -> Map.of("stage", STAGE_OPERATE, "participants", List.of("devops", "dba", "developer", "tester"), "deviation", "默认保守拆分：运维阶段常驻，发布与数据变更必须人工门；新需求与缺陷通过发起迭代承接，不回退大阶段。", "tasks", List.of(Map.of("key", 1, "name", "发布上线与回滚预案", "type", "RELEASE", "agents", List.of("devops"), "participants", List.of("devops", "developer"), "scope", "发布窗口、灰度批次、观测指标与回滚演练", "trigger_mode", "MANUAL_START", "approval_mode", "FORCE"), Map.of("key", 2, "name", "线上观察与告警响应", "type", "OPS", "agents", List.of("devops"), "participants", List.of("devops"), "scope", "错误率/延迟/核心业务量监控与告警处置", "trigger_mode", "AUTO", "approval_mode", "REVIEW", "depends", List.of(Integer.valueOf(1))), Map.of("key", 3, "name", "新需求与缺陷受理", "type", "DISCUSS", "agents", List.of(PM_AGENT), "participants", List.of(PM_AGENT, "product_analyst"), "scope", "受理入口与分诊：判定走迭代修复、配置处置、数据修复或关单", "trigger_mode", "AUTO", "approval_mode", "REVIEW")), "quality_gates", List.of("发布前确认迁移脚本就绪且向后兼容，回滚经过演练或验证可行", "观测指标与停止阈值事先约定，越线立即停止并回滚", "缺陷单含复现步骤、期望、实际、环境信息与严重级别", "逐动作留痕，异常可定位到具体批次与版本"));
        };
    }

    @Transactional
    public Map<String, Object> planDetail(LoginUser user, String wsId) {
        int iteration;
        Map<String, Object> ws = this.requireProject(user, wsId);
        String stage = this.resolveCurrentStage(ws.get("current_stage"));
        List<Map<String, Object>> rows = this.repo.findCurrentStagePlan(wsId, stage, iteration = this.currentIteration(wsId, stage));
        if (rows.isEmpty()) {
            // 读端点不再自动生成规划草稿（副作用违背对话触发原则）；无规划如实返回 404
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND,
                    "暂无规划——由主控在对话里通过 plan_steps 发起");
        }
        Map<String, Object> row = rows.get(0);
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("plan_id", ((Number)row.get("id")).longValue());
        out.put("stage", stage);
        out.put("stage_label", stageLabel(stage));
        out.put("iteration", iteration);
        out.put("status", row.get("status"));
        out.put("plan", parseJsonMap(objectMapper, row.get("plan_json")));
        out.put("deviation", row.get("deviation"));
        out.put("decided_by", row.get("decided_by"));
        out.put("decided_at", row.get("decided_at"));
        out.put("reject_reason", row.get("reject_reason"));
        out.put("capability", this.capabilityOf(user, wsId, stage));
        return out;
    }

    private int currentIteration(String wsId, String stage) {
        return STAGE_OPERATE.equals(stage) ? Math.max(1, this.repo.maxIteration(wsId)) : 1;
    }

    @Transactional
    public Map<String, Object> decidePlan(LoginUser user, String wsId, long planId, Map<String, Object> body) {
        this.requireProject(user, wsId);
        Map row = (Map)this.repo.findStagePlan(planId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "规划不存在: " + planId));
        if (!wsId.equals(row.get("workspace_id"))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "该规划不属于本项目");
        }
        String decision = body == null || body.get("decision") == null ? "" : body.get("decision").toString().trim().toUpperCase();
        String stage = String.valueOf(row.get("stage"));
        int iteration = ((Number)row.get("iteration")).intValue();
        if ("REJECT".equals(decision)) {
            String reason;
            String string = reason = body.get("reason") == null ? "" : body.get("reason").toString().trim();
            if (reason.isBlank()) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "驳回必须写理由：PM 要带着理由重规划，否则下一版还是同一套");
            }
            if (this.repo.decideStagePlan(planId, "REJECTED", user.username(), reason) == 0) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.CONFLICT, "该规划已被处理，请刷新");
            }
            Map<String, Object> draft = this.defaultPlanFor(stage);
            long fresh = this.repo.insertStagePlan(wsId, stage, iteration, toJson(objectMapper, draft), "已按驳回理由重发默认草稿（人工可改）：" + reason);
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("rejected_plan_id", planId);
            out.put("plan_id", fresh);
            out.put("status", "PENDING");
            out.put("regenerated", "default");
            out.put("note", "当前重发的是默认保守草稿，PM agent 接入后将按理由动态重排");
            return out;
        }
        if (!"APPROVE".equals(decision)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "decision 仅支持 APPROVE / REJECT");
        }
        if (this.repo.decideStagePlan(planId, "APPROVED", user.username(), null) == 0) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.CONFLICT, "该规划已被处理，请刷新");
        }
        return this.startStageExecution(user, wsId, row);
    }

    @Transactional
    public Map<String, Object> autoStartStage(LoginUser user, String wsId, String stage, String requirement) {
        long planId;
        this.requireProject(user, wsId);
        this.requireStage(stage);
        Map planRow = this.repo.findLatestStagePlan(wsId, stage).stream().findFirst().orElse(null);
        if (planRow == null) {
            LinkedHashMap<String, Object> draft = new LinkedHashMap<String, Object>(this.defaultPlanFor(stage));
            if (requirement != null && !requirement.isBlank()) {
                draft.put("requirement", trimTo(requirement, 2000));
            }
            int iteration = STAGE_OPERATE.equals(stage) ? Math.max(1, this.repo.maxIteration(wsId)) : 1;
            planId = this.repo.insertStagePlan(wsId, stage, iteration, toJson(objectMapper, draft), "AI 自动生成（对话驱动）" + (requirement == null || requirement.isBlank() ? "" : "：用户需求已带入执行输入"));
        } else {
            planId = ((Number)planRow.get("id")).longValue();
            if (!"APPROVED".equals(String.valueOf(planRow.get("status"))) && requirement != null && !requirement.isBlank() && parseJsonMap(objectMapper, planRow.get("plan_json")).get("requirement") == null) {
                LinkedHashMap<String, Object> pj = new LinkedHashMap<String, Object>(parseJsonMap(objectMapper, planRow.get("plan_json")));
                pj.put("requirement", trimTo(requirement, 2000));
                this.repo.updatePlanJson(planId, toJson(objectMapper, pj));
            }
        }
        Map latest = (Map)this.repo.findStagePlan(planId).stream().findFirst().orElseThrow();
        if ("APPROVED".equals(String.valueOf(latest.get("status"))) && latest.get("exec_run_id") != null) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("already_running", true);
            out.put("plan_id", planId);
            out.put("run_id", String.valueOf(latest.get("exec_run_id")));
            return out;
        }
        return this.decidePlan(user, wsId, planId, Map.of("decision", "APPROVE"));
    }

    /**
     * 人为终止执行中的步骤（2026-10-17 同步化配套）：cancel 在跑的 LLM 调用，
     * 步骤落 SKIPPED（留痕「用户终止」）。主控工具层收到终止信号后直接进入下一步。
     * 若步骤已自然完成（终止晚到）按幂等返回当前状态。
     */
    public Map<String, Object> terminateStep(LoginUser user, String wsId, long seq, String stageArg, Integer iterationArg) {
        this.requireProject(user, wsId);
        String stage = stageArg != null && !stageArg.isBlank() ? stageArg : this.currentStageOf(wsId);
        int iteration = iterationArg != null ? iterationArg : this.currentIterationOf(wsId);
        StageStep step = this.stepRepo.findByStage(wsId, stage, iteration).stream()
                .filter(s -> s.seq() == seq).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "步骤不存在: seq=" + seq + "（stage=" + stage + " iter=" + iteration + "）"));
        if (step.status() != StepStatus.EXECUTING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "步骤不在执行中（" + step.status() + "），无需终止");
        }
        boolean cancelled = io.xuanji.server.project.orchestrator.StepInterruption.terminate(step.id());
        if (!cancelled) {
            return Map.of("ok", true, "seq", seq, "status", String.valueOf(step.status()),
                    "message", "步骤已不在执行中（可能刚完成），无需终止");
        }
        // 执行体 cancel 后自行落 SKIPPED；这里兜底直接落（同状态幂等）
        if (step.stepType() == StepType.DISCUSS) {
            this.discussionEngine.markInterrupted(step);
        } else {
            this.stepRepo.updateStatus(step.withStatus(StepStatus.SKIPPED).withSkip("用户终止"));
        }
        return Map.of("ok", true, "seq", seq, "status", "SKIPPED",
                "message", "已终止，主控将直接进入下一步");
    }

    /**
     * 人工重试失败/被终止的步骤：重置 PLANNED 后后台重跑（TASK→TaskRunner / DISCUSS→DiscussionEngine），
     * 完成/失败经事件推送刷新前端，主控经 wakeIfSettled 被唤醒继续。
     */
    public Map<String, Object> retryStep(LoginUser user, String wsId, long seq, String stageArg, Integer iterationArg) {
        this.requireProject(user, wsId);
        String stage = stageArg != null && !stageArg.isBlank() ? stageArg : this.currentStageOf(wsId);
        int iteration = iterationArg != null ? iterationArg : this.currentIterationOf(wsId);
        StageStep step = this.stepRepo.findByStage(wsId, stage, iteration).stream()
                .filter(s -> s.seq() == seq).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "步骤不存在: seq=" + seq + "（stage=" + stage + " iter=" + iteration + "）"));
        if (step.status() != StepStatus.FAILED && step.status() != StepStatus.SKIPPED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "只有失败/被终止的步骤可重试（当前 " + step.status() + "）");
        }
        StageStep reset = step.withStatus(StepStatus.PLANNED).withSkip(null).withError(null);
        this.stepRepo.updateStatus(reset);
        if (step.stepType() == StepType.DISCUSS) {
            // 讨论重跑：清空旧发言流重新发起；发言完成后唤醒主控以主席身份收口
            this.stepRepo.appendSpeech(step.id(), "===== 人工重试：重新发起讨论 =====\n\n");
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    this.discussionEngine.runDiscussion(reset.withId(step.id()),
                            new io.xuanji.server.project.orchestrator.TopicBrief("", "", step.instruction(), "", ""),
                            step.participants(), null);
                    this.stageOrchestrator.wakeIfSettled(wsId, stage, iteration,
                            "讨论 #" + seq + "「" + step.name() + "」人工重试发言完成，请以主席身份收口（discuss_close）或继续追加。");
                } catch (Exception e) {
                    this.stageOrchestrator.wakeIfSettled(wsId, stage, iteration,
                            "讨论 #" + seq + "「" + step.name() + "」人工重试失败（" + e.getMessage() + "）。");
                }
            });
        } else {
            this.taskRunner.submit(reset.withId(step.id()),
                    new io.xuanji.server.project.orchestrator.TopicBrief("", "", step.instruction(), "", ""));
        }
        return Map.of("ok", true, "seq", seq, "message", "已重新发起，完成后自动刷新（主控会被唤醒继续）");
    }

    /**
     * 用户验收（P0 验收责任链）：human 验收步停在 WAITING_ACCEPT，由用户裁决。
     * 通过 → DONE（verified_by=human，需求回写，唤醒主控消化）；打回 → FAILED（带原因，
     * 主控下轮看到打回原因自主重试——execute_step 重拾机制原地重做）。
     */
    public Map<String, Object> acceptStep(LoginUser user, String wsId, long seq, Map<String, Object> body) {
        this.requireProject(user, wsId);
        String stage = this.currentStageOf(wsId);
        int iteration = this.currentIterationOf(wsId);
        StageStep step = this.stepRepo.findByStage(wsId, stage, iteration).stream()
                .filter(s -> s.seq() == seq).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "步骤不存在: seq=" + seq));
        if (step.status() != StepStatus.WAITING_ACCEPT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该步骤不在待验收状态（当前 " + step.status() + "）——只有验收人=用户的步骤执行完后才需要验收");
        }
        boolean pass = Boolean.TRUE.equals(body.get("pass"));
        String comment = String.valueOf(body.getOrDefault("comment", ""));
        if (pass) {
            StageStep done = step.withStatus(StepStatus.DONE).withVerifiedBy("human");
            if (!comment.isBlank()) {
                done = done.withAnswer(comment);
            }
            this.stepRepo.updateStatus(done);
            // 需求回写与 tool 验收同口径：human 通过同样推进需求闭环
            try {
                taskRunner.settleRequirements(done);
            } catch (Exception ignore) {
                // 需求回写失败不阻塞验收（看板信息）
            }
            this.projectEvents.publish(wsId, "step.updated", Map.of(
                    "stage", stage, "seq", seq, "stepType", "TASK",
                    "status", "DONE", "name", step.name()));
            this.stageOrchestrator.wakeIfSettled(wsId, stage, iteration,
                    "步骤 #" + seq + "「" + step.name() + "」已通过用户验收" + (comment.isBlank() ? "" : "（意见：" + comment + "）")
                            + "。请消化验收结论后继续推进。");
            return Map.of("ok", true, "seq", seq, "status", "DONE",
                    "message", "验收通过，主控已被唤醒继续推进");
        }
        String reason = "用户验收打回" + (comment.isBlank() ? "" : "：" + comment);
        this.stepRepo.updateStatus(step.withStatus(StepStatus.FAILED).withError(reason));
        this.projectEvents.publish(wsId, "step.updated", Map.of(
                "stage", stage, "seq", seq, "stepType", "TASK",
                "status", "FAILED", "name", step.name()));
        this.stageOrchestrator.wakeIfSettled(wsId, stage, iteration,
                "步骤 #" + seq + "「" + step.name() + "」被用户打回" + (comment.isBlank() ? "" : "（" + comment + "）")
                        + "。请评估后重试（execute_step 重拾原地重做，instruction 带上打回原因）或调整方案。");
        return Map.of("ok", true, "seq", seq, "status", "FAILED",
                "message", "已打回，主控会被唤醒组织重做");
    }

    private String currentStageOf(String wsId) {
        Map ws = (Map)this.workspaceRepo.findById(wsId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在"));
        return String.valueOf(ws.get("current_stage"));
    }

    private int currentIterationOf(String wsId) {
        String stage = this.currentStageOf(wsId);
        return this.stepRepo.findByStage(wsId, stage, 1).stream()
                .filter(s -> stage.equals(s.stage()))
                .mapToInt(StageStep::iteration).max().orElse(1);
    }

    @Transactional
    public Map<String, Object> rerunStage(LoginUser user, String wsId, String rawStage) {
        Long defId;
        Timestamp ts;
        Object started;
        this.requireProject(user, wsId);
        String stage = this.requireStage(rawStage);
        Map planRow = (Map)this.repo.findLatestStagePlan(wsId, stage).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该阶段还没有规划，无需重跑"));
        if (!"APPROVED".equals(String.valueOf(planRow.get("status")))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "规划尚未批准，先批准规划再执行");
        }
        if (planRow.get("exec_run_id") == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该阶段尚未起跑，无需重跑");
        }
        long planId = ((Number)planRow.get("id")).longValue();
        String oldRunId = String.valueOf(planRow.get("exec_run_id"));
        Map<String, Object> oldRun = this.runService.getRun(oldRunId);
        String oldStatus = String.valueOf(oldRun.get("status"));
        if ("SUCCESS".equals(oldStatus)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该阶段已执行完成，重跑请走「开启迭代」");
        }
        if ("SUSPENDED".equals(oldStatus)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该阶段有决策门待审批，请先处理审批而非重跑");
        }
        if ("RUNNING".equals(oldStatus) && (started = oldRun.get("started_at")) instanceof Timestamp && (ts = (Timestamp)started).toInstant().isAfter(Instant.now().minusSeconds(600L))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该阶段 10 分钟内刚起跑，可能正在执行，请稍候；若确认卡死再重跑");
        }
        Object v = planRow.get("exec_definition_id");
        defId = v instanceof Number ? Long.valueOf(((Number)v).longValue()) : null;
        if (defId == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "旧执行缺少工作流定义，无法直接重跑，请驳回规划后重新批准");
        }
        Map ws = (Map)this.workspaceRepo.findById(wsId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "project not found"));
        int iteration = ((Number)planRow.get("iteration")).intValue();
        Map<String, Object> plan = parseJsonMap(objectMapper, planRow.get("plan_json"));
        Map<String, Object> capability = this.capabilityOf(user, wsId, stage);
        // 先建新 run 再清旧：反过来 purge 成功 + 起跑失败会让 exec_run_id 指向已删 run，阶段卡死只能修库
        String newRunId = String.valueOf(this.runService.createRunAsync(defId, null, this.stageRunInput(ws, stage, iteration, plan, null, capability), STAGE_TRIGGER).get("runId"));
        this.runService.purgeRun(oldRunId);
        this.repo.updatePlanExec(planId, defId, newRunId);
        log.info("阶段重跑: project={} plan_id={} stage={} iteration={} old_run={} new_run={}", new Object[]{wsId, planId, stage, iteration, oldRunId, newRunId});
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rerun", true);
        out.put("plan_id", planId);
        out.put("definition_id", defId);
        out.put("run_id", newRunId);
        out.put("old_run_id", oldRunId);
        return out;
    }

    private Map<String, Object> startStageExecution(LoginUser user, String wsId, Map<String, Object> planRow) {
        String runId;
        Map<String, Object> published;
        Map<String, Object> capability;
        String wfRef;
        long planId = ((Number)planRow.get("id")).longValue();
        String stage = String.valueOf(planRow.get("stage"));
        int iteration = ((Number)planRow.get("iteration")).intValue();
        Map ws = (Map)this.workspaceRepo.findById(wsId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "project not found"));
        String projectName = strOr(ws.get("name"), wsId);
        Map<String, Object> plan = parseJsonMap(objectMapper, planRow.get("plan_json"));
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("plan_id", planId);
        out.put("status", "APPROVED");
        Object object = planRow.get("exec_definition_id");
        if (object instanceof Number) {
            Number num = (Number)object;
            if (planRow.get("exec_run_id") != null) {
                String reuseRun = String.valueOf(planRow.get("exec_run_id"));
                out.put("definition_id", num.longValue());
                out.put("run_id", reuseRun);
                out.put("reused", true);
                out.put("run", this.runService.getRun(reuseRun));
                return out;
            }
        }
        if (!(wfRef = this.capString((capability = this.capabilityOf(user, wsId, stage)).get("capability"), "workflow")).isBlank()) {
            long refId;
            try {
                refId = Long.parseLong(wfRef);
            }
            catch (NumberFormatException e) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "阶段绑定的工作流引用不合法：" + wfRef + "（应为工作流定义 id）");
            }
            Map<String, Object> refDef = this.definitionService.get(refId, user);
            if (!"PUBLISHED".equals(String.valueOf(refDef.get("status")))) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "阶段绑定的工作流 #" + wfRef + " 当前是 " + String.valueOf(refDef.get("status")) + "，仅已发布的工作流可直接执行，请先发布或改绑");
            }
            String runId2 = String.valueOf(this.runService.createRunAsync(refId, null, this.stageRunInput(ws, stage, iteration, plan, null, capability), STAGE_TRIGGER).get("runId"));
            this.repo.updatePlanExec(planId, refId, runId2);
            log.info("阶段按绑定工作流直接起跑: project={} plan_id={} stage={} iteration={} def={} run={}", new Object[]{wsId, planId, stage, iteration, refId, runId2});
            out.put("definition_id", refId);
            out.put("run_id", runId2);
            out.put("bound_workflow", true);
            return out;
        }
        StagePlanCompiler.Compiled compiled = this.planCompiler.compile(projectName, stage, iteration, plan, parseJsonMap(objectMapper, capability.get("capability")));
        String flowName = trimTo("[阶段] " + projectName + " · " + stage + " 第" + iteration + "轮", 128);
        long definitionId = this.definitionService.create(flowName, "PM 阶段规划编译生成（plan_id=" + planId + "）", compiled.canvasJson(), user, "stage_plan");
        try {
            published = this.definitionService.publish(definitionId, user);
            runId = String.valueOf(this.runService.createRunAsync(definitionId, null, this.stageRunInput(ws, stage, iteration, plan, compiled, capability), STAGE_TRIGGER).get("runId"));
        }
        catch (RuntimeException e) {
            this.safeDeleteDefinition(definitionId, user);
            throw e;
        }
        this.repo.updatePlanExec(planId, definitionId, runId);
        log.info("阶段规划已编译为工作流并起跑: project={} plan_id={} stage={} iteration={} def={} run={} tasks={} skipped={}", new Object[]{wsId, planId, stage, iteration, definitionId, runId, compiled.tasks().size(), compiled.skipped().size()});
        out.put("definition_id", definitionId);
        out.put("definition_version", published.get("version"));
        out.put("run_id", runId);
        out.put("tasks", compiled.tasks());
        out.put("skipped", compiled.skipped());
        if (!compiled.skipped().isEmpty()) {
            out.put("warning", "有子任务未进入流程（agent 无已发布版本），批准前请确认是否需要补发布后重发规划");
        }
        return out;
    }

    private Map<String, Object> stageRunInput(Map<String, Object> ws, String stage, int iteration, Map<String, Object> plan, StagePlanCompiler.Compiled compiled, Map<String, Object> capability) {
        LinkedHashMap tasks = new LinkedHashMap();
        if (compiled != null) {
            for (Map<String, Object> t : compiled.tasks()) {
                LinkedHashMap<String, Object> card = new LinkedHashMap<String, Object>();
                card.put("name", t.get("name"));
                card.put("scope", t.get("scope"));
                card.put("participants", t.get("participants"));
                card.put("approval_mode", t.get("approval_mode"));
                tasks.put(String.valueOf(t.get("instance_id")), card);
            }
        }
        LinkedHashMap<String, Object> proj = new LinkedHashMap<String, Object>();
        proj.put("id", ws.get("id"));
        proj.put("name", ws.get("name"));
        proj.put("root_path", ws.get("root_path"));
        proj.put("project_mode", ws.get("project_mode"));
        LinkedHashMap<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("project", proj);
        input.put("stage", stage);
        input.put("iteration", iteration);
        input.put("quality_gates", objList(objectMapper, plan.get("quality_gates")));
        input.put("requirement", strOr(plan.get("requirement"), ""));
        input.put("resources", this.stageResourceBrief(capability));
        input.put("prior_summaries", this.priorSummaries(String.valueOf(ws.get("id"))));
        input.put("tasks", tasks);
        return input;
    }

    private String capString(Object capability, String key) {
        if (!(capability instanceof Map)) {
            return "";
        }
        Map m = (Map)capability;
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private List<Map<String, Object>> stageResourceBrief(Map<String, Object> capability) {
        Map<String, Object> cap = parseJsonMap(objectMapper, capability.get("capability"));
        ArrayList<String> refs = new ArrayList<String>();
        for (String key : List.of("tools", "connectors", "artifacts")) {
            for (Object o : objList(objectMapper, cap.get(key))) {
                if (o == null || String.valueOf(o).isBlank()) continue;
                refs.add(String.valueOf(o).trim());
            }
        }
        ArrayList<Map<String, Object>> brief = new ArrayList<Map<String, Object>>(this.repo.describeCapabilityRefs(refs));
        for (Object o : objList(objectMapper, cap.get("urls"))) {
            if (o == null || String.valueOf(o).isBlank()) continue;
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("kind", "url");
            item.put("name", String.valueOf(o).trim());
            brief.add(item);
        }
        return brief;
    }

    private List<Map<String, Object>> priorSummaries(String wsId) {
        ArrayList<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> s : this.repo.findApprovedSummaries(wsId)) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("stage", s.get("stage"));
            item.put("iteration", s.get("iteration"));
            item.put("content_md", s.get("content_md"));
            out.add(item);
        }
        return out;
    }

    private void safeDeleteDefinition(long definitionId, LoginUser user) {
        try {
            this.definitionService.delete(definitionId, user);
            log.warn("阶段流程发布或起跑失败，已回收半成品定义: definition_id={}", (Object)definitionId);
        }
        catch (Exception e) {
            log.warn("阶段流程失败且半成品定义未能回收（需人工处理）: definition_id={} cause={}", (Object)definitionId, (Object)e.toString());
        }
    }

    private String trimTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * 需求清单（V34 需求先行）：需求项 + 依赖拓扑深度。
     * depth = 依赖链长度（无依赖 0，取依赖方 depth 最大值 +1），前端按 depth 分层画拓扑图；
     * 防环由 declare_requirements 落库时兜底，此处防御性封顶（超过需求总数视为环，截断）。
     */
    public Map<String, Object> requirements(LoginUser user, String wsId) {
        this.requireProject(user, wsId);
        List<io.xuanji.server.project.orchestrator.RequirementRepository.Requirement> rows =
                requirementRepo.findByWorkspace(wsId);
        Map<String, Integer> depth = new java.util.HashMap<>();
        for (io.xuanji.server.project.orchestrator.RequirementRepository.Requirement r : rows) {
            depth.put(r.code(), depthOf(r.code(), rows, depth, rows.size()));
        }
        // 需求活动留痕：讨论（DISCUSS）/澄清（CONSULT）次数与记录（结论/日期/谁/是否完结）——
        // 台账 requirementCodes 关联（ask_peer/discuss 落库时写入），一次拉全项目内存分组
        List<io.xuanji.server.project.orchestrator.StageStep> steps = stepRepo.findByWorkspace(wsId);
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        java.util.ArrayList<Map<String, Object>> items = new java.util.ArrayList<>();
        for (io.xuanji.server.project.orchestrator.RequirementRepository.Requirement r : rows) {
            java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("code", r.code());
            item.put("title", r.title());
            item.put("description", r.description());
            item.put("status", r.status());
            item.put("depends_on", jsonStringList(r.dependsOn()));
            item.put("acceptance", r.acceptanceJson());
            item.put("depth", depth.getOrDefault(r.code(), 0));
            item.put("created_by", r.createdBy());
            item.put("updated_at", r.updatedAt() == null ? null : r.updatedAt().toString());
            List<io.xuanji.server.project.orchestrator.StageStep> related = steps.stream()
                    .filter(s -> s.requirementCodes().contains(r.code()))
                    .toList();
            item.put("discuss_count", related.stream()
                    .filter(s -> s.stepType() == io.xuanji.server.project.orchestrator.StepType.DISCUSS).count());
            item.put("clarify_count", related.stream()
                    .filter(s -> s.stepType() == io.xuanji.server.project.orchestrator.StepType.CONSULT).count());
            java.util.ArrayList<Map<String, Object>> acts = new java.util.ArrayList<>();
            for (io.xuanji.server.project.orchestrator.StageStep s : related) {
                java.util.LinkedHashMap<String, Object> a = new java.util.LinkedHashMap<>();
                a.put("type", s.stepType().name());
                a.put("name", s.name());
                a.put("summary", s.outputSummary());
                a.put("date", s.createdAt() == null ? null : s.createdAt().toString());
                a.put("actor", s.agent());
                a.put("participants", s.participants());
                a.put("done", !s.status().active());
                acts.add(a);
            }
            item.put("activities", acts);
            items.add(item);
        }
        out.put("requirements", items);
        return out;
    }

    /**
     * 追溯链（用户定调 2026-10-16：流程拓扑与链路可追溯性是唯一主视图，不是内容平铺）。
     * 端到端关联：需求项 → 三阶段工作步骤（requirement_codes 声明）→ 产出证据（artifact），
     * 外加阶段流水线状态与未挂链工作项（透明暴露，倒逼补链）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> trace(LoginUser user, String wsId) {
        Map ws = this.requireProject(user, wsId);
        String currentStage = String.valueOf(ws.get("current_stage"));
        List<String> order = STAGE_ORDER;
        int curIdx = order.indexOf(currentStage);
        // 阶段流水线：当前阶段之前=已完成，当前=进行中，之后=未开始；锚取各阶段最新迭代
        java.util.ArrayList<Map<String, Object>> stages = new java.util.ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            String st = order.get(i);
            int iter = this.currentIteration(wsId, st);
            java.util.LinkedHashMap<String, Object> sm = new java.util.LinkedHashMap<>();
            sm.put("stage", st);
            sm.put("label", stageLabel(st));
            sm.put("iteration", iter);
            sm.put("status", i < curIdx ? "DONE" : i == curIdx ? "ACTIVE" : "PENDING");
            sm.put("anchor", this.stepRepo.findAnchorText(wsId, st, iter));
            stages.add(sm);
        }
        List<io.xuanji.server.project.orchestrator.StageStep> steps = this.stepRepo.findByWorkspace(wsId);
        List<Map<String, Object>> artifacts = this.repo.findArtifactsByWorkspace(wsId);
        // 步骤 → 产出证据：node_id 新规则 step-{stage}-{iter}-{seq}；存量 step-{seq} 以 file_path 含 /{stage}/ 兜底
        java.util.LinkedHashMap<String, Object> stepArts = new java.util.LinkedHashMap<>();
        for (Map<String, Object> a : artifacts) {
            String nodeId = String.valueOf(a.get("node_id"));
            String fp = a.get("file_path") == null ? "" : String.valueOf(a.get("file_path"));
            for (io.xuanji.server.project.orchestrator.StageStep s : steps) {
                String full = "step-" + s.stage() + "-" + s.iteration() + "-" + s.seq();
                String legacy = "step-" + s.seq();
                boolean match = nodeId.equals(full) || (nodeId.equals(legacy) && fp.contains("/" + s.stage() + "/"));
                if (match && !stepArts.containsKey(full)) {
                    java.util.LinkedHashMap<String, Object> am = new java.util.LinkedHashMap<>();
                    am.put("name", a.get("name"));
                    am.put("file_path", a.get("file_path"));
                    am.put("kind", a.get("kind"));
                    // 纪要/问答全文随 trace 返回：StepDetailModal 就地渲染，不用再点一层文件弹窗
                    am.put("content_md", a.get("content_md"));
                    stepArts.put(full, am);
                }
            }
        }
        // 需求 → 步骤链（requirement_codes 声明关联；DECISION/CONSULT 步天然不挂，不进 unlinked）
        List<io.xuanji.server.project.orchestrator.RequirementRepository.Requirement> rows = requirementRepo.findByWorkspace(wsId);
        Map<String, Integer> depth = new java.util.HashMap<>();
        for (io.xuanji.server.project.orchestrator.RequirementRepository.Requirement r : rows) {
            depth.put(r.code(), depthOf(r.code(), rows, depth, rows.size()));
        }
        java.util.ArrayList<Map<String, Object>> reqItems = new java.util.ArrayList<>();
        java.util.ArrayList<Map<String, Object>> unlinked = new java.util.ArrayList<>();
        for (io.xuanji.server.project.orchestrator.RequirementRepository.Requirement r : rows) {
            java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("code", r.code());
            item.put("title", r.title());
            item.put("status", r.status());
            item.put("depends_on", jsonStringList(r.dependsOn()));
            item.put("depth", depth.getOrDefault(r.code(), 0));
            // 需求详情弹窗（2026-10-19 用户反馈「点击需求没有内容」）：描述/验收/来源随 trace 返回
            item.put("description", r.description());
            item.put("acceptance", r.acceptanceJson());
            item.put("source", r.source());
            item.put("parent_code", r.parentCode());
            item.put("chain", new java.util.ArrayList<>());
            reqItems.add(item);
        }
        java.util.Map<String, Map<String, Object>> byCode = new java.util.LinkedHashMap<>();
        for (Map<String, Object> item : reqItems) {
            byCode.put(String.valueOf(item.get("code")), item);
        }
        for (io.xuanji.server.project.orchestrator.StageStep s : steps) {
            java.util.LinkedHashMap<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("stage", s.stage());
            node.put("iteration", s.iteration());
            node.put("seq", s.seq());
            node.put("name", s.name());
            node.put("step_type", s.stepType().name());
            node.put("status", s.status().name());
            node.put("verified_by", s.verifiedBy());
            node.put("error", s.error());
            node.put("output_summary", s.outputSummary());
            node.put("instruction", s.instruction());
            node.put("requirement_codes", s.requirementCodes());
            // ["*"] = 全局步骤（架构设计/测试执行/发布等不专属某条需求的，guidance 让主控显式传）——
            // 仍进 unlinked 泳道但带 global 标记，前端显示中性「全局」徽章而非断链警示
            boolean globalStep = s.requirementCodes().contains("*");
            node.put("global", globalStep);
            node.put("artifact", stepArts.get("step-" + s.stage() + "-" + s.iteration() + "-" + s.seq()));
            boolean linked = false;
            for (String code : s.requirementCodes()) {
                if ("*".equals(code)) {
                    continue; // 全局标记不挂到具体需求链
                }
                Map<String, Object> item = byCode.get(code);
                if (item != null) {
                    ((List<Object>) item.get("chain")).add(node);
                    linked = true;
                }
            }
            if (!linked && (s.stepType() == io.xuanji.server.project.orchestrator.StepType.TASK
                    || s.stepType() == io.xuanji.server.project.orchestrator.StepType.DISCUSS)) {
                unlinked.add(node);
            }
        }
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("current_stage", currentStage);
        out.put("stages", stages);
        out.put("requirements", reqItems);
        out.put("unlinked", unlinked);
        // V36 交付图：用例/bug 随 trace 返回（前端平面交付图据此画「需求→任务→用例→bug」链）
        java.util.ArrayList<Map<String, Object>> tcItems = new java.util.ArrayList<>();
        for (io.xuanji.server.project.orchestrator.TestCaseRepository.TestCase tc : testCaseRepo.findByWorkspace(wsId)) {
            java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("code", tc.code());
            item.put("title", tc.title());
            item.put("status", tc.status());
            item.put("requirement_code", tc.requirementCode());
            item.put("content", tc.contentMd());
            tcItems.add(item);
        }
        out.put("test_cases", tcItems);
        java.util.ArrayList<Map<String, Object>> bugItems = new java.util.ArrayList<>();
        for (io.xuanji.server.project.orchestrator.BugRepository.Bug b : bugRepo.findByWorkspace(wsId)) {
            java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("code", b.code());
            item.put("title", b.title());
            item.put("severity", b.severity());
            item.put("status", b.status());
            item.put("requirement_code", b.requirementCode());
            item.put("test_case_code", b.testCaseCode());
            item.put("content", b.contentMd());
            bugItems.add(item);
        }
        out.put("bugs", bugItems);
        return out;
    }

    /** 依赖深度递归（记忆化）；环防御：递归深度超过需求总数按 0 处理。 */
    private int depthOf(String code, List<io.xuanji.server.project.orchestrator.RequirementRepository.Requirement> rows,
                        Map<String, Integer> memo, int total) {
        Integer cached = memo.get(code);
        if (cached != null) {
            return cached;
        }
        memo.put(code, 0);
        io.xuanji.server.project.orchestrator.RequirementRepository.Requirement self = rows.stream()
                .filter(r -> r.code().equals(code)).findFirst().orElse(null);
        int d = 0;
        if (self != null && self.dependsOn() != null) {
            try {
                List<String> deps = jsonStringList(self.dependsOn());
                for (String dep : deps) {
                    d = Math.max(d, depthOf(dep, rows, memo, total) + 1);
                }
            } catch (Exception ignore) {
                // 依赖 JSON 损坏按无依赖处理
            }
        }
        memo.put(code, d);
        return d;
    }

    /** JSONB 数组文本 → List<String>（损坏按空表处理）。命名避开 ProjectSupport 静态导入的 parseStringList（同名会遮蔽两参重载）。 */
    private List<String> jsonStringList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            List<Object> raw = objectMapper.readValue(json, List.class);
            return raw.stream().map(String::valueOf).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, Object> stageExecution(LoginUser user, String wsId) {
        Map<String, Object> ws = this.requireProject(user, wsId);
        String current = this.resolveCurrentStage(ws.get("current_stage"));
        int iteration = this.currentIteration(wsId, current);
        Map plan = this.repo.findCurrentStagePlan(wsId, current, iteration).stream().findFirst().orElse(null);
        Map<String, Object> clarify = this.chatService.latestClarification(wsId, current, iteration);
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        List<StageStep> steps = this.stepRepo.findByStage(wsId, current, iteration);
        boolean orchestrationMode = !steps.isEmpty();
        out.put("stage", current);
        out.put("stage_label", stageLabel(current));
        out.put("iteration", iteration);
        out.put("plan", plan == null ? null : this.briefPlan(plan));
        out.put("pending_clarification", clarify);
        if (orchestrationMode) {
            return this.orchestrationView(out, steps);
        }
        if (plan == null) {
            out.put("state", "NO_PLAN");
            return out;
        }
        if (!"APPROVED".equals(String.valueOf(plan.get("status")))) {
            out.put("state", "PENDING_APPROVAL");
            return out;
        }
        if (plan.get("exec_run_id") == null) {
            out.put("state", "NOT_STARTED");
            return out;
        }
        String runId = String.valueOf(plan.get("exec_run_id"));
        Map<String, Object> run = this.runService.getRun(runId);
        List<Map<String, Object>> nodeRuns = mapList(objectMapper, run.get("nodes"));
        String runStatus = String.valueOf(run.get("status"));
        String gateNodeId = run.get("gate_node_id") == null ? null : String.valueOf(run.get("gate_node_id"));
        ArrayList waiting = new ArrayList();
        if ("SUSPENDED".equals(runStatus) && gateNodeId != null) {
            for (Map<String, Object> nr : nodeRuns) {
                if (!gateNodeId.equals(String.valueOf(nr.get("node_id")))) continue;
                LinkedHashMap<String, Object> w = new LinkedHashMap<String, Object>();
                w.put("node_run_id", nr.get("id"));
                w.put("node_id", nr.get("node_id"));
                w.put("node_name", nr.get("node_name"));
                w.put("output", nr.get("output_json"));
                waiting.add(w);
            }
        }
        out.put("state", "SUSPENDED".equals(runStatus) ? "WAITING_DECISION" : ("SUCCESS".equals(runStatus) ? "FINISHED" : "EXECUTING"));
        out.put("run_id", runId);
        out.put("run_status", run.get("status"));
        out.put("definition_id", plan.get("exec_definition_id"));
        out.put("error", run.get("error"));
        out.put("node_runs", nodeRuns);
        out.put("waiting_decisions", waiting);
        return out;
    }

    private Map<String, Object> orchestrationView(Map<String, Object> out, List<StageStep> steps) {
        // WAITING 任何步 + 待验收步 + PLANNED DECISION（存量兼容）——任一存在即 WAITING_DECISION，
        // state 语义才与决策/验收卡一致
        boolean waiting = steps.stream().anyMatch(s -> s.status() == StepStatus.WAITING
                || s.status() == StepStatus.WAITING_ACCEPT
                || (s.stepType() == StepType.DECISION && s.status() == StepStatus.PLANNED));
        boolean executing = steps.stream().anyMatch(s -> s.status() == StepStatus.EXECUTING);
        long settled = steps.stream().filter(s -> s.status() == StepStatus.DONE || s.status() == StepStatus.SKIPPED).count();
        String state = waiting ? "WAITING_DECISION" : (executing ? "EXECUTING" : (settled == (long)steps.size() ? "FINISHED" : "EXECUTING"));
        out.put("state", state);
        out.put("steps", steps);
        out.put("node_runs", List.of());
        ArrayList waitingList = new ArrayList();
        for (StageStep s2 : steps) {
            // WAITING 的任何步 + 待验收步（WAITING_ACCEPT）+ 未答复的 DECISION 步（存量兼容：
            // request_decision 2026-10-20 前创建即 PLANNED）——否则决策/验收在「阶段执行」tab
            // 永远不可见，用户不知道 AI 在等什么
            if (s2.status() != StepStatus.WAITING && s2.status() != StepStatus.WAITING_ACCEPT
                    && !(s2.stepType() == StepType.DECISION && s2.status() == StepStatus.PLANNED)) {
                continue;
            }
            LinkedHashMap<String, Object> w = new LinkedHashMap<String, Object>();
            w.put("node_run_id", s2.id());
            w.put("node_id", String.valueOf(s2.seq()));
            w.put("node_name", s2.name());
            w.put("output", s2.instruction());
            // kind 供前端区分渲染：DECISION=答复框；ACCEPT=验收卡（通过/打回按钮）
            w.put("kind", s2.status() == StepStatus.WAITING_ACCEPT ? "ACCEPT" : "DECISION");
            waitingList.add(w);
        }
        out.put("waiting_decisions", waitingList);
        return out;
    }

    private Map<String, Object> briefPlan(Map<String, Object> plan) {
        LinkedHashMap<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("id", plan.get("id"));
        b.put("status", plan.get("status"));
        b.put("iteration", plan.get("iteration"));
        b.put("deviation", plan.get("deviation"));
        b.put("decided_by", plan.get("decided_by"));
        b.put("reject_reason", plan.get("reject_reason"));
        b.put("exec_definition_id", plan.get("exec_definition_id"));
        b.put("exec_run_id", plan.get("exec_run_id"));
        b.put("tasks", mapList(objectMapper, parseJsonMap(objectMapper, plan.get("plan_json")).get("tasks")));
        return b;
    }

    public Map<String, Object> decideStageGate(LoginUser user, String wsId, Map<String, Object> body) {
        String gateNodeId;
        String comment;
        String decision;
        String nodeId;
        int iteration;
        Map<String, Object> ws = this.requireProject(user, wsId);
        String current = this.resolveCurrentStage(ws.get("current_stage"));
        Map plan = (Map)this.repo.findCurrentStagePlan(wsId, current, iteration = this.currentIteration(wsId, current)).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, stageLabel(current) + "阶段还没有规划，无门可审"));
        if (plan.get("exec_run_id") == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, stageLabel(current) + "阶段尚未开始执行，无门可审");
        }
        String runId = String.valueOf(plan.get("exec_run_id"));
        String string = nodeId = body == null || body.get("node_id") == null ? "" : String.valueOf(body.get("node_id")).trim();
        if (nodeId.isEmpty()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "node_id 必填（取自 stage-execution 的 waiting_decisions）");
        }
        String string2 = decision = body.get("decision") == null ? "" : String.valueOf(body.get("decision")).trim().toUpperCase();
        if (!("APPROVE".equals(decision) || "REJECT".equals(decision) || "SKIP".equals(decision))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "decision 仅支持 APPROVE / REJECT / SKIP");
        }
        String string3 = comment = body.get("comment") == null ? null : String.valueOf(body.get("comment")).trim();
        if ("REJECT".equals(decision) && (comment == null || comment.isBlank())) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "驳回必须写批注：引擎要拿它让 agent 按意见重生成，空批注只会重出一版同样的东西");
        }
        Map<String, Object> run = this.runService.getRun(runId);
        if (!"SUSPENDED".equals(String.valueOf(run.get("status")))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.CONFLICT, "该阶段流程当前不在等待决策（状态 " + String.valueOf(run.get("status")) + "），请刷新后重试");
        }
        String string4 = gateNodeId = run.get("gate_node_id") == null ? null : String.valueOf(run.get("gate_node_id"));
        if (gateNodeId == null || !gateNodeId.equals(nodeId)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.CONFLICT, "当前等待决策的节点是 " + (gateNodeId == null ? "（无）" : gateNodeId) + "，不是 " + nodeId + "；请刷新待决策列表后重试");
        }
        log.info("阶段决策门审批: project={} stage={} run={} node={} decision={} by={}", new Object[]{wsId, current, runId, nodeId, decision, user.username()});
        return this.runService.resumeAsync(runId, nodeId, decision, comment, user.username());
    }

    public Map<String, Object> capabilityOf(LoginUser user, String wsId, String rawStage) {
        this.requireProject(user, wsId);
        String stage = this.requireStage(rawStage);
        List<Map<String, Object>> rows = this.repo.findStageCapability(wsId, stage);
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("stage", stage);
        out.put("capability", rows.isEmpty() ? new LinkedHashMap() : parseJsonMap(objectMapper, rows.get(0).get("capability_json")));
        out.put("note", rows.isEmpty() ? null : rows.get(0).get("note"));
        return out;
    }

    @Transactional
    public Map<String, Object> saveCapability(LoginUser user, String wsId, String stage, Map<String, Object> body) {
        this.requireProject(user, wsId);
        String st = this.requireStage(stage);
        LinkedHashMap<String, Object> cap = new LinkedHashMap<String, Object>();
        for (String k : CAPABILITY_KEYS) {
            if ("workflow".equals(k)) {
                String wf = body == null || body.get(k) == null ? "" : String.valueOf(body.get(k)).trim();
                cap.put(k, wf);
                continue;
            }
            // deploy_spec 是部署规范文本（目录约定/启动方式/端口等），单字符串不是列表
            if ("deploy_spec".equals(k)) {
                String spec = body == null || body.get(k) == null ? "" : String.valueOf(body.get(k)).trim();
                cap.put(k, spec);
                continue;
            }
            cap.put(k, strList(objectMapper, body == null ? null : body.get(k)));
        }
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note")).trim();
        this.repo.upsertStageCapability(wsId, st, toJson(objectMapper, cap), note, user.username());
        return this.capabilityOf(user, wsId, st);
    }

    private String requireStage(String raw) {
        String s;
        String string = s = raw == null ? "" : raw.trim().toUpperCase();
        if (!STAGE_ORDER.contains(s)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "stage 仅支持 " + String.join((CharSequence)"/", STAGE_ORDER) + "，当前值: " + raw);
        }
        return s;
    }

    private Map<String, Object> snapshotWorkflowRef(Object rawDefId, String template, int seq) {
        Long definitionId = optionalLong(rawDefId, "template " + template + " seq=" + seq + " workflow_def_id");
        if (definitionId == null) {
            return null;
        }
        if (!this.repo.isDefinitionPublished(definitionId)) {
            log.warn("template {} seq={} 默认工作流 #{} 不存在或未发布，跳过快照（节点不预绑流程）", new Object[]{template, seq, definitionId});
            return null;
        }
        return Map.of("definitionId", definitionId);
    }

    /**
     * 编辑项目：name（改名，root_path 不变目录不动）+ servers/connectors（运维资源绑定，
     * 显式传入即覆盖写入 OPERATE 阶段 capability——空数组=清空，不传=不动）。
     */
    public Map<String, Object> updateProject(LoginUser user, String wsId, Map<String, Object> body) {
        requireProject(user, wsId);
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        boolean nameChanged = name != null && !name.isBlank();
        boolean hasServers = body.containsKey("servers");
        boolean hasConnectors = body.containsKey("connectors");
        boolean hasDatabases = body.containsKey("databases");
        boolean hasDeploySpec = body.containsKey("deploy_spec");
        if (!nameChanged && !hasServers && !hasConnectors && !hasDatabases && !hasDeploySpec) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST,
                    "无可更新字段（name / servers / databases / deploy_spec / connectors）");
        }
        if (nameChanged) {
            workspaceRepo.rename(wsId, name.trim());
        }
        if (hasServers || hasConnectors || hasDatabases || hasDeploySpec) {
            // 旧条目先取出：编辑回显脱敏（密码不回明文），用户未改密码字段（空或 ***）时沿用旧密码
            List<Map<String, Object>> existing = this.repo.findStageCapability(wsId, STAGE_OPERATE);
            Map<String, Object> oldCap = existing.isEmpty() ? Map.of()
                    : parseJsonMap(objectMapper, existing.get(0).get("capability_json"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> oldServers = oldCap.get("servers") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> oldDatabases = oldCap.get("databases") instanceof List<?> l2
                    ? (List<Map<String, Object>>) l2 : List.of();
            List<Map<String, Object>> servers = opsEntries(body.get("servers"), false, oldServers);
            List<String> connectors = strList(objectMapper, body.get("connectors"));
            List<Map<String, Object>> databases = opsEntries(body.get("databases"), true, oldDatabases);
            String deploySpec = body.get("deploy_spec") == null ? "" : String.valueOf(body.get("deploy_spec")).trim();
            // 白名单初始化 → 现值 → 显式键覆盖（与创建时绑定同款 merge，但空数组语义为清空）
            LinkedHashMap<String, Object> cap = new LinkedHashMap<String, Object>();
            for (String k : CAPABILITY_KEYS) {
                cap.put(k, "workflow".equals(k) ? "" : "deploy_spec".equals(k) ? "" : List.of());
            }
            cap.putAll(oldCap);
            if (hasServers) {
                cap.put("servers", servers);
            }
            if (hasDatabases) {
                cap.put("databases", databases);
            }
            if (hasDeploySpec) {
                cap.put("deploy_spec", deploySpec);
            }
            if (hasConnectors) {
                cap.put("connectors", connectors);
            }
            this.repo.upsertStageCapability(wsId, STAGE_OPERATE, toJson(objectMapper, cap), "编辑项目时绑定", user.username());
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", wsId);
        if (nameChanged) {
            result.put("name", name.trim());
        }
        if (hasServers) {
            result.put("servers", maskEntries(opsEntries(body.get("servers"), false, List.of())));
        }
        if (hasDatabases) {
            result.put("databases", maskEntries(opsEntries(body.get("databases"), true, List.of())));
        }
        if (hasDeploySpec) {
            result.put("deploy_spec", body.get("deploy_spec") == null ? "" : String.valueOf(body.get("deploy_spec")).trim());
        }
        if (hasConnectors) {
            result.put("connectors", strList(objectMapper, body.get("connectors")));
        }
        return result;
    }

    /**
     * 运维连接条目规范化（servers/databases 对象列表）：address 必填，password 用
     * CryptoUtil 加密落库（地址与凭证平台托管，AI 只见引用 ID）。编辑场景 password
     * 传空或 "***"（回显脱敏值）表示沿用旧密码，避免脱敏值被二次加密覆盖真值。
     */
    private List<Map<String, Object>> opsEntries(Object raw, boolean isDb, List<Map<String, Object>> oldEntries) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (int idx = 0; idx < list.size(); idx++) {
            Object o = list.get(idx);
            if (!(o instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) o;
            String address = item.get("address") == null ? "" : String.valueOf(item.get("address")).trim();
            if (address.isEmpty()) {
                continue;
            }
            Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("address", address);
            if (isDb) {
                e.put("username", item.get("username") == null ? "" : String.valueOf(item.get("username")).trim());
            }
            String pwd = item.get("password") == null ? "" : String.valueOf(item.get("password")).trim();
            String oldPwd = idx < oldEntries.size() && oldEntries.get(idx).get("password") != null
                    ? String.valueOf(oldEntries.get(idx).get("password")) : "";
            if (pwd.isEmpty() || "***".equals(pwd)) {
                e.put("password", oldPwd);
            } else {
                e.put("password", crypto.encrypt(pwd));
            }
            e.put("note", item.get("note") == null ? "" : String.valueOf(item.get("note")).trim());
            out.add(e);
        }
        return out;
    }

    /** 回显脱敏：password 永不回明文——有值给 "***"，无值给空串。 */
    private List<Map<String, Object>> maskEntries(List<Map<String, Object>> entries) {
        for (Map<String, Object> e : entries) {
            Object pwd = e.get("password");
            e.put("password", pwd != null && !String.valueOf(pwd).isBlank() ? "***" : "");
        }
        return entries;
    }

    /**
     * 删除项目：项目域关联数据级联清理 + 项目目录递归删除（破坏性，调用方须已获用户确认）。
     * 引擎侧 run 历史不删（审计数据）。
     */
    public Map<String, Object> deleteProject(LoginUser user, String wsId) {
        Map ws = (Map)this.workspaceRepo.findById(wsId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "project not found"));
        this.requireOwner(user, ws);
        if (!"PROJECT".equals(ws.get("binding_type"))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该 workspace 不是项目，不能用项目删除接口");
        }
        String rootPath = String.valueOf(ws.get("root_path"));
        // 先终止该项目所有在跑任务再删数据（2026-10-20 实锤：删除不终止任务，异步轮
        // 继续落库复活脏数据——CONSULT 步 seq 从 1 重新编号复活 7 行）
        int killed = stageOrchestrator.purgeRuntimeState(wsId);
        log.info("删除项目: ws={} 终止在跑任务 {} 个", wsId, killed);
        workspaceRepo.deleteProjectCascade(wsId);
        Path root = Paths.get(rootPath).normalize();
        if (Files.isDirectory(root)) {
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignore) {
                        // 单个文件删除失败不阻断（目录可能被进程占用），残留由运维清理
                    }
                });
            }
            catch (IOException ignore) {
                // 目录遍历失败同上：数据已删，目录残留可手工清理
            }
        }
        return Map.of("deleted", wsId, "root_path", rootPath);
    }

    /** 项目文件树：列出项目目录（root_path）下的文件与子目录——代码/文档产出直接可见，不再只看对话碎片。 */
    /** 文件树忽略目录：编译产物/包管理依赖/IDE 元数据——node_modules 几万条目会把 500 配额吃光，其他目录全被挤没（2026-10-20 用户实测）。 */
    private static final java.util.Set<String> IGNORED_DIR_NAMES = java.util.Set.of(
            "node_modules", "target", "dist", "build", "out", ".git", ".idea", ".vscode",
            "__pycache__", ".next", ".nuxt", "coverage", ".gradle", ".venv", "venv",
            ".pytest_cache", ".mypy_cache", ".cache", ".turbo", ".output", ".settings");

    public List<Map<String, Object>> listFiles(LoginUser user, String wsId) {
        Path root = projectRoot(user, wsId);
        List<Map<String, Object>> out = new ArrayList<>();
        // walkFileTree 而非 Files.walk：忽略目录必须 SKIP_SUBTREE 整棵剪枝——
        // Files.walk 会先遍历 node_modules 内部再过滤，慢且 500 limit 在排序前截断（配额被垃圾目录吃光）
        try {
            java.util.function.BiConsumer<Path, Boolean> addEntry = (p, dir) -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", root.relativize(p).toString().replace('\\', '/'));
                f.put("type", dir ? "dir" : "file");
                if (!dir) {
                    try {
                        f.put("size", Files.size(p));
                    } catch (IOException ignore) {
                        f.put("size", 0);
                    }
                }
                out.add(f);
            };
            final int[] count = {0};
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                private java.nio.file.FileVisitResult check(Path p, boolean dir) {
                    if (count[0] >= 500) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    count[0]++;
                    addEntry.accept(p, dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    if (IGNORED_DIR_NAMES.contains(dir.getFileName().toString())) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    if (root.relativize(dir).getNameCount() > 6) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return check(dir, true);
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    return check(file, false);
                }
            });
        }
        catch (IOException e) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR, "读取项目目录失败: " + e.getMessage());
        }
        return out;
    }

    /** 读项目文件文本内容（≤200KB）：path 为相对项目根的相对路径，normalize 后必须仍在项目根内（防穿越）。 */
    public Map<String, Object> readFile(LoginUser user, String wsId, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "path is required");
        }
        Path root = projectRoot(user, wsId);
        Path target = root.resolve(relPath).normalize();
        if (!target.startsWith(root)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "路径越界");
        }
        if (!Files.isRegularFile(target)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "文件不存在");
        }
        try {
            long size = Files.size(target);
            String content;
            boolean truncated = false;
            if (size > 200 * 1024) {
                // 大文件不再 400 拒绝（2026-10-20 用户反馈「大于200kb的文件不可见」）：
                // 截断返回前 200KB + truncated 标记，前端提示「仅显示前 200KB」
                try (java.io.InputStream in = Files.newInputStream(target)) {
                    byte[] buf = in.readNBytes(200 * 1024);
                    java.nio.charset.CharsetDecoder dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.IGNORE)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.IGNORE);
                    content = dec.decode(java.nio.ByteBuffer.wrap(buf)).toString();
                }
                truncated = true;
            } else {
                content = Files.readString(target);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", relPath);
            out.put("size", size);
            out.put("truncated", truncated);
            out.put("content", content);
            return out;
        }
        catch (IOException e) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR, "读取文件失败: " + e.getMessage());
        }
    }

    /** 项目根目录：root_path（创建时落库，相对路径按 JVM 工作目录解析），不存在则 404。 */
    private Path projectRoot(LoginUser user, String wsId) {
        Map ws = (Map)this.workspaceRepo.findById(wsId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "project not found"));
        this.requireOwner(user, ws);
        Path root = Paths.get(String.valueOf(ws.get("root_path"))).normalize();
        if (!Files.isDirectory(root)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "项目目录不存在: " + ws.get("root_path"));
        }
        return root;
    }

    private void createProjectDirectories(String rootPath) {
        try {
            Path root = Paths.get(rootPath, new String[0]);
            Files.createDirectories(root, new FileAttribute[0]);
            Files.createDirectories(root.resolve("docs"), new FileAttribute[0]);
            Files.createDirectories(root.resolve("src"), new FileAttribute[0]);
            Files.createDirectories(root.resolve("test"), new FileAttribute[0]);
        }
        catch (IOException e) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR, "创建项目目录失败: " + e.getMessage());
        }
    }

    private void requireWorkspaceVisible(LoginUser user, String wsId) {
        Map ws = (Map)this.workspaceRepo.findById(wsId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "project not found"));
        this.requireOwner(user, ws);
    }


    private void requireOwner(LoginUser user, Map<String, Object> row) {
        boolean orgVisible;
        if (user.isPlatformAdmin() || "ALL".equals(user.dataScope())) {
            return;
        }
        String createdBy = (String)row.get("created_by");
        String scope = user.dataScope() == null ? "ONLY_CREATE" : user.dataScope();
        boolean bl = orgVisible = ("ORG".equals(scope) || "UNDER_ORG".equals(scope)) && user.orgId() != null && user.orgId().equals(row.get("org_id"));
        if (!orgVisible && !user.username().equals(createdBy)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "无权访问该项目");
        }
    }

    private void validateName(String name) {
        if (name.equals(".") || name.equals("..")) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "项目名不能为 . 或 ..");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "项目名不能包含路径分隔符");
        }
        if (!name.matches("[\\p{L}\\p{N}_\\- .]+")) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "项目名含非法字符（仅允许字母/数字/中文/下划线/连字符/空格/点）");
        }
    }

}
