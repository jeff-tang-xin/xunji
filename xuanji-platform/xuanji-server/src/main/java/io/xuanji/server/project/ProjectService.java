package io.xuanji.server.project;

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
import java.util.regex.Pattern;
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
    private static final Pattern TEMPLATE_CODE = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final int NODE_TYPE_MAX = 24;
    private static final int OWNER_ROLE_MAX = 32;
    private static final int DELIVERABLE_MAX = 256;
    private static final int AGENT_NAME_MAX = 64;
    private static final List<String> STAGE_ORDER = List.of("REQUIREMENT", "DEVELOP", "OPERATE");
    private static final Map<String, String> STAGE_LABEL = Map.of("REQUIREMENT", "需求", "DEVELOP", "开发", "OPERATE", "运维");
    private static final String STAGE_OPERATE = "OPERATE";
    private static final String STAGE_REQUIREMENT = "REQUIREMENT";
    private static final String PM_AGENT = "project_manager";
    private static final Set<String> FORCE_TYPES = Set.of("RELEASE", "DEPLOY", "DB", "DATABASE", "DATA_FIX");
    private static final List<String> CAPABILITY_KEYS = List.of("tools", "skills", "mcps", "connectors", "artifacts", "urls", "agents", "workflow", "servers");
    private static final String MODE_LEGACY = "LEGACY";
    private static final Map<String, String> TYPE_STAGE = Map.ofEntries(Map.entry("DISCUSS", "REQUIREMENT"), Map.entry("INITIATION", "REQUIREMENT"), Map.entry("PROJECT_DOC", "REQUIREMENT"), Map.entry("REQUIREMENT", "REQUIREMENT"), Map.entry("PRD", "REQUIREMENT"), Map.entry("PROTOTYPE", "REQUIREMENT"), Map.entry("UI_DESIGN", "REQUIREMENT"), Map.entry("ARCHITECTURE", "DEVELOP"), Map.entry("DESIGN", "DEVELOP"), Map.entry("TEST_CASE", "DEVELOP"), Map.entry("DEVELOP", "DEVELOP"), Map.entry("SMOKE", "DEVELOP"), Map.entry("SUBMIT", "DEVELOP"), Map.entry("TEST", "DEVELOP"), Map.entry("REVIEW", "DEVELOP"), Map.entry("RELEASE", "OPERATE"), Map.entry("DEPLOY", "OPERATE"), Map.entry("OPS", "OPERATE"), Map.entry("BUGFIX", "OPERATE"), Map.entry("MONITOR", "OPERATE"), Map.entry("DB", "OPERATE"), Map.entry("DATABASE", "OPERATE"));
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
        this.repo.upsertPmSession(wsId, PM_AGENT, null);
        this.createProjectDirectories(rootPath);
        long planId = this.draftStagePlan(user, wsId, currentStage, 1);
        if (template != null && !template.isBlank()) {
            this.applyTemplateStages(wsId, template, user.username());
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
            this.repo.upsertStageCapability(wsId, STAGE_REQUIREMENT, this.toJson(cap), "创建项目时预设", user.username());
            boundWf = wfRef;
        }
        List<String> presetServers = this.strList(body.get("servers"));
        List<String> presetConnectors = this.strList(body.get("connectors"));
        if (!presetServers.isEmpty() || !presetConnectors.isEmpty()) {
            LinkedHashMap<String, Object> cap = new LinkedHashMap<String, Object>();
            for (String k : CAPABILITY_KEYS) {
                cap.put(k, "workflow".equals(k) ? "" : List.of());
            }
            List<Map<String, Object>> existing = this.repo.findStageCapability(wsId, STAGE_OPERATE);
            if (!existing.isEmpty()) {
                cap.putAll(this.parseJsonMap(existing.get(0).get("capability_json")));
            }
            if (!presetServers.isEmpty()) {
                cap.put("servers", presetServers);
            }
            if (!presetConnectors.isEmpty()) {
                cap.put("connectors", presetConnectors);
            }
            this.repo.upsertStageCapability(wsId, STAGE_OPERATE, this.toJson(cap), "创建项目时绑定", user.username());
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", wsId);
        result.put("name", name);
        result.put("root_path", rootPath);
        result.put("status", "ACTIVE");
        result.put("template", template);
        result.put("project_mode", mode);
        result.put("current_stage", currentStage);
        result.put("nodes", List.of());
        result.put("plan_id", planId);
        result.put("plan_status", "PENDING");
        if (boundWf != null) {
            result.put("requirement_workflow", boundWf);
        }
        return result;
    }

    public List<Map<String, Object>> list(LoginUser user) {
        DataScopeFilter.Clause c = DataScopeFilter.of(user, "org_id", "created_by");
        return this.workspaceRepo.findProjectsByScope(c.sql(), c.args().toArray());
    }

    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> rows = this.repo.findTemplates();
        for (Map<String, Object> r : rows) {
            r.put("stages", this.parseJsonMap(r.get("stages_json")));
        }
        return rows;
    }

    public Map<String, Object> templateDetail(String code) {
        Map<String, Object> tpl = this.requireTemplate(code);
        long templateId = ((Number)tpl.get("id")).longValue();
        ArrayList nodes = new ArrayList();
        for (Map<String, Object> tn : this.repo.findTemplateNodes(templateId)) {
            LinkedHashMap<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("type", tn.get("type"));
            n.put("seq", tn.get("seq"));
            n.put("owner_role", tn.get("owner_role"));
            n.put("agent_name", tn.get("agent_name"));
            n.put("workflow_def_id", tn.get("workflow_def_id"));
            n.put("standards", this.parseStringList(tn.get("standards")));
            n.put("requirements", this.parseStringList(tn.get("requirements")));
            n.put("methodology", tn.get("methodology"));
            n.put("deliverable", tn.get("deliverable"));
            n.put("depends_on", this.parseIntList(tn.get("depends_on")));
            n.put("preconditions", this.parseStringList(tn.get("preconditions")));
            nodes.add(n);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", tpl.get("id"));
        result.put("code", tpl.get("code"));
        result.put("name", tpl.get("name"));
        result.put("description", tpl.get("description"));
        result.put("builtin", tpl.get("builtin"));
        result.put("stages", this.parseJsonMap(tpl.get("stages_json")));
        result.put("nodes", nodes);
        return result;
    }

    @Transactional
    public Map<String, Object> createTemplate(Map<String, Object> body) {
        String code = this.requireText(body.get("code"), "code");
        if (!TEMPLATE_CODE.matcher(code).matches()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "code 只能由小写字母/数字/下划线/连字符组成且长度 1-32（当前: " + code + "）");
        }
        if (!this.repo.findTemplateByCode(code).isEmpty()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "code 已被占用: " + code);
        }
        String name = this.limited(this.requireText(body.get("name"), "name"), 128, "name");
        String description = this.limited(this.blankToNull(body.get("description")), 512, "description");
        List<Map<String, Object>> nodes = this.normalizeTemplateNodes(body.get("nodes"));
        String stagesJson = this.normalizeTemplateStages(body.get("stages"));
        long templateId = this.repo.insertTemplate(code, name, description, stagesJson);
        this.insertTemplateNodes(templateId, nodes);
        log.info("template created: code={} nodes={} stages={}", new Object[]{code, nodes.size(), stagesJson == null ? 0 : stagesJson.length()});
        return this.templateDetail(code);
    }

    @Transactional
    public Map<String, Object> updateTemplate(String code, Map<String, Object> body) {
        Map<String, Object> tpl = this.requireTemplate(code);
        String bodyCode = this.blankToNull(body.get("code"));
        if (bodyCode != null && !bodyCode.equals(code)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "code 不可修改（路径: " + code + "，body: " + bodyCode + "）");
        }
        String name = this.limited(this.requireText(body.get("name"), "name"), 128, "name");
        String description = this.limited(this.blankToNull(body.get("description")), 512, "description");
        List<Map<String, Object>> nodes = this.normalizeTemplateNodes(body.get("nodes"));
        String stagesJson = this.normalizeTemplateStages(body.get("stages"));
        long templateId = ((Number)tpl.get("id")).longValue();
        this.repo.updateTemplate(templateId, name, description, stagesJson);
        if (body.get("nodes") != null) {
            this.repo.deleteTemplateNodes(templateId);
            this.insertTemplateNodes(templateId, nodes);
        }
        return this.templateDetail(code);
    }

    @Transactional
    public Map<String, Object> deleteTemplate(String code) {
        Map<String, Object> tpl = this.requireTemplate(code);
        if (Boolean.TRUE.equals(tpl.get("builtin"))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "内置模板不可删除: " + code + "（可复制其节点自建模板）");
        }
        this.repo.deleteTemplateByCode(code);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deleted", true);
        result.put("code", code);
        return result;
    }

    private String normalizeTemplateStages(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "stages 应为对象");
        }
        Map m = (Map)raw;
        LinkedHashMap out = new LinkedHashMap();
        for (String stage : List.of(STAGE_REQUIREMENT, "DEVELOP", STAGE_OPERATE)) {
            Object s = m.get(stage);
            if (s == null) continue;
            if (!(s instanceof Map)) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "stages." + stage + " 应为对象");
            }
            Map sm = (Map)s;
            LinkedHashMap<String, Object> cap = new LinkedHashMap<String, Object>();
            Object wf = sm.get("workflow");
            cap.put("workflow", wf == null ? "" : String.valueOf(wf).trim());
            for (String k : List.of("agents", "servers", "connectors")) {
                cap.put(k, this.strList(sm.get(k)));
            }
            out.put(stage, cap);
        }
        return out.isEmpty() ? null : this.toJson(out);
    }

    private void applyTemplateStages(String wsId, String templateCode, String username) {
        Map tpl = this.repo.findTemplateByCode(templateCode).stream().findFirst().orElse(null);
        if (tpl == null || tpl.get("stages_json") == null) {
            return;
        }
        Map<String, Object> stages = this.parseJsonMap(tpl.get("stages_json"));
        String note = "来自模板 " + templateCode;
        for (String stage : List.of(STAGE_REQUIREMENT, "DEVELOP", STAGE_OPERATE)) {
            Map sm;
            Object s = stages.get(stage);
            if (!(s instanceof Map) || (sm = (Map)s).isEmpty()) continue;
            LinkedHashMap<String, Object> cap = new LinkedHashMap<String, Object>();
            for (String k : CAPABILITY_KEYS) {
                if ("workflow".equals(k)) {
                    Object wf = sm.get("workflow");
                    cap.put(k, wf == null ? "" : String.valueOf(wf).trim());
                    continue;
                }
                cap.put(k, this.strList(sm.get(k)));
            }
            this.repo.upsertStageCapability(wsId, stage, this.toJson(cap), note, username);
        }
        log.info("template stages applied: ws={} template={}", (Object)wsId, (Object)templateCode);
    }

    public Map<String, Object> get(LoginUser user, String id) {
        Map ws = (Map)this.workspaceRepo.findById(id).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "project not found"));
        this.requireOwner(user, ws);
        if (!"PROJECT".equals(ws.get("binding_type"))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该 workspace 不是项目（binding_type=" + String.valueOf(ws.get("binding_type")) + "）");
        }
        List<Map<String, Object>> nodes = this.repo.findNodesByWorkspace(id);
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
        String currentStage = this.resolveCurrentStage(ws.get("current_stage"), nodes);
        result.put("current_stage", currentStage);
        result.put("stages", this.buildStageViews(id, currentStage, nodes));
        result.put("nodes", nodes);
        result.put("artifacts", artifacts);
        return result;
    }

    public Map<String, Object> runNode(LoginUser user, String wsId, String nodeId) {
        this.requireWorkspaceVisible(user, wsId);
        Map<String, Object> node = this.requireNodeInWorkspace(wsId, nodeId);
        List<String> dependsOn = this.parseStringList(node.get("depends_on"));
        for (String depId : dependsOn) {
            Map dep = this.repo.findNodeById(depId).stream().findFirst().orElse(null);
            if (dep != null && "DONE".equals(dep.get("status"))) continue;
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.CONFLICT, "前置节点未完成，无法执行该节点（依赖节点: " + depId + "）");
        }
        this.repo.updateNodeStatus(nodeId, "RUNNING");
        Long definitionId = this.parseWorkflowRefDefinitionId(node.get("workflow_ref"));
        if (definitionId != null) {
            Map<String, Object> input = this.parseInputRefs(node.get("input_refs"));
            Map<String, Object> run = this.runService.createRunAsync(definitionId, null, input, "PROJECT_NODE");
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("node_id", nodeId);
            result.put("runId", run.get("runId"));
            result.put("status", "RUNNING");
            result.put("message", "节点已启动，阶段工作流异步执行中（runId=" + String.valueOf(run.get("runId")) + "）");
            return result;
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("node_id", nodeId);
        result.put("status", "RUNNING");
        result.put("message", "节点已启动（未绑定阶段工作流，仅置 RUNNING）");
        return result;
    }

    @Transactional
    public Map<String, Object> bindWorkflowRef(LoginUser user, String wsId, String nodeId, Long definitionId) {
        this.requireWorkspaceVisible(user, wsId);
        this.requireNodeInWorkspace(wsId, nodeId);
        if (definitionId == null || definitionId <= 0L) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "definitionId is required");
        }
        if (!this.repo.isDefinitionPublished(definitionId)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "工作流 #" + definitionId + " 不存在或未发布（须 status=PUBLISHED）");
        }
        String refJson = this.toJson(Map.of("definitionId", definitionId));
        this.repo.updateNodeWorkflowRef(nodeId, refJson);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("node_id", nodeId);
        result.put("workflow_ref", Map.of("definitionId", definitionId));
        result.put("bound", true);
        return result;
    }

    public void onNodeRunFinished(String wsId, String nodeId, String runStatus) {
        if (nodeId == null || nodeId.isBlank()) {
            log.warn("onNodeRunFinished: nodeId is blank, skip (wsId={} runStatus={})", (Object)wsId, (Object)runStatus);
            return;
        }
        String target = "SUCCESS".equals(runStatus) ? "DONE" : "BLOCKED";
        this.repo.updateNodeStatus(nodeId, target);
        log.info("node run finished: wsId={} nodeId={} runStatus={} -> nodeStatus={}", new Object[]{wsId, nodeId, runStatus, target});
    }

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

    public Map<String, Object> completeNode(LoginUser user, String wsId, String nodeId) {
        this.requireWorkspaceVisible(user, wsId);
        this.requireNodeInWorkspace(wsId, nodeId);
        this.repo.updateNodeStatus(nodeId, "DONE");
        return Map.of("node_id", nodeId, "status", "DONE");
    }

    public List<Map<String, Object>> nodeArtifacts(LoginUser user, String wsId, String nodeId) {
        this.requireWorkspaceVisible(user, wsId);
        this.requireNodeInWorkspace(wsId, nodeId);
        return this.repo.findArtifactsByNode(nodeId);
    }

    @Transactional
    public Map<String, Object> addArtifact(LoginUser user, String wsId, String nodeId, Map<String, Object> body) {
        Map node;
        String name;
        String kind;
        this.requireWorkspaceVisible(user, wsId);
        this.requireNodeInWorkspace(wsId, nodeId);
        String string = kind = body.get("kind") == null ? "DELIVERABLE" : body.get("kind").toString();
        if (!"PROCESS".equals(kind) && !"DELIVERABLE".equals(kind)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "kind must be PROCESS | DELIVERABLE");
        }
        String string2 = name = body.get("name") == null ? null : body.get("name").toString().trim();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "name is required");
        }
        String type = body.get("type") == null ? "DOC" : body.get("type").toString();
        String filePath = body.get("file_path") == null ? null : body.get("file_path").toString();
        String contentMd = body.get("content_md") == null ? null : body.get("content_md").toString();
        String status = body.get("status") == null ? "FINAL" : body.get("status").toString();
        Integer version = this.parseVersion(body.get("version"));
        this.repo.insertArtifact(wsId, nodeId, kind, name, type, filePath, contentMd, version, status, user.username());
        if ("DELIVERABLE".equals(kind) && (node = (Map)this.repo.findNodeById(nodeId).stream().findFirst().orElse(null)) != null) {
            List<String> inputs = this.parseStringList(node.get("input_refs"));
            List<String> outputs = this.parseStringList(node.get("output_refs"));
            outputs.add(name + "@" + String.valueOf(version == null ? "latest" : version));
            this.repo.updateNodeRefs(nodeId, this.toJson(inputs), this.toJson(outputs));
        }
        return Map.of("registered", true, "kind", kind, "name", name);
    }

    private Map<String, Object> requireTemplate(String code) {
        return (Map)this.repo.findTemplateByCode(code).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "模板不存在: " + code));
    }

    private List<Map<String, Object>> normalizeTemplateNodes(Object raw) {
        List list;
        if (raw == null) {
            return new ArrayList<Map<String, Object>>();
        }
        if (!(raw instanceof List) || (list = (List)raw).isEmpty()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "nodes 不能为空（模板至少包含一个节点，或改用 stages 阶段预设）");
        }
        ArrayList<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size(); ++i) {
            Object e = list.get(i);
            if (!(e instanceof Map)) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "nodes[" + i + "] 必须是对象");
            }
            Map m = (Map)e;
            Map<String, Object> e2 = this.asStringMap(m);
            String label = "nodes[" + i + "]";
            String type = this.limited(this.requireText(e2.get("type"), label + ".type"), 24, label + ".type");
            String ownerRole = this.limited(this.requireText(e2.get("owner_role"), label + ".owner_role"), 32, label + ".owner_role");
            int seq = this.requireSeq(e2.get("seq"), label + ".seq");
            String deliverable = this.limited(this.blankToNull(e2.get("deliverable")), 256, label + ".deliverable");
            String agentName = this.limited(this.blankToNull(e2.get("agent_name")), 64, label + ".agent_name");
            Long workflowDefId = this.optionalLong(e2.get("workflow_def_id"), label + ".workflow_def_id");
            String stage = ProjectService.resolveStage(e2.get("stage"), type);
            LinkedHashMap<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("type", type);
            n.put("seq", seq);
            n.put("owner_role", ownerRole);
            n.put("agent_name", agentName);
            n.put("workflow_def_id", workflowDefId);
            n.put("stage", stage);
            n.put("standards", this.requireStringList(e2.get("standards"), label + ".standards"));
            n.put("requirements", this.requireStringList(e2.get("requirements"), label + ".requirements"));
            n.put("methodology", this.blankToNull(e2.get("methodology")));
            n.put("deliverable", deliverable);
            n.put("depends_on", this.requireIntList(e2.get("depends_on"), label + ".depends_on"));
            n.put("preconditions", this.requireStringList(e2.get("preconditions"), label + ".preconditions"));
            nodes.add(n);
        }
        HashSet<Integer> seqs = new HashSet<Integer>();
        for (Map map : nodes) {
            int seq = (Integer)map.get("seq");
            if (seqs.add(seq)) continue;
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "seq 重复: " + seq);
        }
        for (int expected = 1; expected <= nodes.size(); ++expected) {
            if (seqs.contains(expected)) continue;
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "seq 必须从 1 起连续，缺少 seq=" + expected + "（当前共 " + nodes.size() + " 个节点）");
        }
        for (Map map : nodes) {
            int seq = (Integer)map.get("seq");
            for (Object depObj : (List)map.get("depends_on")) {
                int dep = (Integer)depObj;
                if (!seqs.contains(dep)) {
                    throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "节点 seq=" + seq + " 的 depends_on 引用了不存在的 seq=" + dep);
                }
                if (dep < seq) continue;
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "节点 seq=" + seq + " 的 depends_on 必须小于自身 seq（引用 seq=" + dep + " 会成环或自依赖）");
            }
        }
        return nodes;
    }

    private void insertTemplateNodes(long templateId, List<Map<String, Object>> nodes) {
        for (Map<String, Object> n : nodes) {
            this.repo.insertTemplateNode(templateId, (String)n.get("type"), (Integer)n.get("seq"), this.toJson(n.get("standards")), this.toJson(n.get("requirements")), (String)n.get("methodology"), (String)n.get("deliverable"), this.toJson(n.get("depends_on")), this.toJson(n.get("preconditions")), (String)n.get("owner_role"), (String)n.get("agent_name"), (Long)n.get("workflow_def_id"), (String)n.get("stage"));
        }
    }

    private Long optionalLong(Object v, String label) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            Number num = (Number)v;
            long lv = num.longValue();
            if (lv <= 0L) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 必须是正整数（当前: " + lv + "）");
            }
            return lv;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            long lv = Long.parseLong(s);
            if (lv <= 0L) {
                throw new NumberFormatException();
            }
            return lv;
        }
        catch (NumberFormatException ex) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 必须是正整数（当前: " + s + "）");
        }
    }

    private Map<String, Object> asStringMap(Map<?, ?> m) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }

    private String requireText(Object v, String label) {
        String s = this.blankToNull(v);
        if (s == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " is required");
        }
        return s;
    }

    private String limited(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 超长（" + value.length() + " > " + max + "）");
        }
        return value;
    }

    private int requireSeq(Object v, String label) {
        Integer seq = this.toInteger(v);
        if (seq == null || seq < 1) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 必须是从 1 开始的整数（当前: " + String.valueOf(v) + "）");
        }
        return seq;
    }

    private Integer toInteger(Object v) {
        if (v instanceof Number) {
            Number n = (Number)v;
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return null;
    }

    private List<String> requireStringList(Object v, String label) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 必须是字符串数组");
        }
        List list = (List)v;
        ArrayList<String> result = new ArrayList<String>();
        for (Object o : list) {
            String s = this.blankToNull(o);
            if (s == null) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 含空项（第 " + (result.size() + 1) + " 项）");
            }
            result.add(s);
        }
        return result;
    }

    private List<Integer> requireIntList(Object v, String label) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 必须是 seq 整数数组");
        }
        List list = (List)v;
        ArrayList<Integer> result = new ArrayList<Integer>();
        for (Object o : list) {
            Integer i = this.toInteger(o);
            if (i == null || i < 1) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 含非法 seq: " + String.valueOf(o));
            }
            result.add(i);
        }
        return result;
    }

    private List<Map<String, Object>> buildNodeChainFromTemplate(String wsId, Map<String, Object> tpl, Map<String, Object> scopes, String stageFilter) {
        long templateId = ((Number)tpl.get("id")).longValue();
        String template = String.valueOf(tpl.get("code"));
        List<Map<String, Object>> tplNodes = this.repo.findTemplateNodes(templateId);
        if (tplNodes.isEmpty()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "模板无节点: " + template);
        }
        ArrayList<Map<String, Object>> picked = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> map : tplNodes) {
            if (stageFilter != null && !stageFilter.equals(ProjectService.resolveStage(map.get("stage"), map.get("type")))) continue;
            picked.add(map);
        }
        if (picked.isEmpty() && stageFilter != null) {
            LinkedHashMap<String, Object> fallback = new LinkedHashMap<String, Object>();
            fallback.put("type", "OPS");
            fallback.put("seq", 1);
            fallback.put("owner_role", "运维");
            fallback.put("agent_name", "devops");
            fallback.put("workflow_def_id", null);
            fallback.put("standards", List.of());
            fallback.put("requirements", List.of());
            fallback.put("methodology", null);
            fallback.put("deliverable", "运维阶段工作记录");
            fallback.put("depends_on", List.of());
            fallback.put("preconditions", List.of());
            fallback.put("stage", stageFilter);
            picked.add(fallback);
            log.info("模板 {} 无 {} 阶段节点，老项目使用兜底 OPS 节点", (Object)template, (Object)stageFilter);
        }
        if (picked.isEmpty()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "模板无可用节点: " + template);
        }
        HashSet<Integer> kept = new HashSet<Integer>();
        for (Map map : picked) {
            kept.add(((Number)map.get("seq")).intValue());
        }
        ArrayList<Map<String, Object>> arrayList = new ArrayList<Map<String, Object>>();
        for (Map map : picked) {
            int seq = ((Number)map.get("seq")).intValue();
            LinkedHashMap<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("id", this.depId(wsId, seq));
            n.put("type", map.get("type"));
            n.put("seq", seq);
            n.put("status", "NOT_STARTED");
            n.put("depends_on", this.mapSeqToIds(wsId, this.retainKeptDeps(this.parseIntList(map.get("depends_on")), kept, seq)));
            n.put("preconditions", this.parseStringList(map.get("preconditions")));
            n.put("workflow_ref", this.snapshotWorkflowRef(map.get("workflow_def_id"), template, seq));
            n.put("input_refs", List.of());
            n.put("output_refs", List.of());
            n.put("owner_role", map.get("owner_role"));
            n.put("stage", ProjectService.resolveStage(map.get("stage"), map.get("type")));
            n.put("iteration", 1);
            n.put("scope", this.blankToNull(scopes.get(String.valueOf(seq))));
            arrayList.add(n);
        }
        return arrayList;
    }

    private List<Integer> retainKeptDeps(List<Integer> deps, Set<Integer> kept, int seq) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (Integer dep : deps) {
            if (kept.contains(dep)) {
                out.add(dep);
                continue;
            }
            log.info("节点 seq={} 依赖的 seq={} 不在生成范围内（阶段过滤），已丢弃该依赖边", (Object)seq, (Object)dep);
        }
        return out;
    }

    private static String resolveStage(Object rawStage, Object rawType) {
        String s;
        String string = s = rawStage == null ? "" : String.valueOf(rawStage).trim().toUpperCase();
        if (STAGE_ORDER.contains(s)) {
            return s;
        }
        String t = rawType == null ? "" : String.valueOf(rawType).trim().toUpperCase();
        return TYPE_STAGE.getOrDefault(t, "DEVELOP");
    }

    private static String stageLabel(String stage) {
        return STAGE_LABEL.getOrDefault(stage, stage);
    }

    private static String nodeStage(Map<String, Object> node) {
        return ProjectService.resolveStage(node.get("stage"), node.get("type"));
    }

    private static int iterationOf(Map<String, Object> node) {
        int n;
        Object it = node.get("iteration");
        if (it instanceof Number) {
            Number num = (Number)it;
            n = num.intValue();
        } else {
            n = 1;
        }
        return n;
    }

    private Map<String, Object> requireProject(LoginUser user, String wsId) {
        Map ws = (Map)this.workspaceRepo.findById(wsId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "project not found"));
        this.requireOwner(user, ws);
        if (!"PROJECT".equals(ws.get("binding_type"))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该 workspace 不是项目（binding_type=" + String.valueOf(ws.get("binding_type")) + "）");
        }
        return ws;
    }

    private String resolveCurrentStage(Object rawStage, List<Map<String, Object>> nodes) {
        String s;
        String string = s = rawStage == null ? "" : String.valueOf(rawStage).trim().toUpperCase();
        if (STAGE_ORDER.contains(s)) {
            return s;
        }
        for (String stage : STAGE_ORDER) {
            if (STAGE_OPERATE.equals(stage)) break;
            boolean hasNode = false;
            for (Map<String, Object> n : nodes) {
                if (!stage.equals(ProjectService.nodeStage(n)) || ProjectService.iterationOf(n) != 1) continue;
                hasNode = true;
                if ("DONE".equals(String.valueOf(n.get("status")))) continue;
                return stage;
            }
            if (hasNode) continue;
            return stage;
        }
        return STAGE_OPERATE;
    }

    private List<Map<String, Object>> buildStageViews(String wsId, String currentStage, List<Map<String, Object>> nodes) {
        ArrayList<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (String stage : STAGE_ORDER) {
            ArrayList<Map<String, Object>> stageNodes = new ArrayList<Map<String, Object>>();
            ArrayList<Map<String, Object>> firstRound = new ArrayList<Map<String, Object>>();
            TreeSet<Integer> iterations = new TreeSet<Integer>();
            for (Map<String, Object> n2 : nodes) {
                if (!stage.equals(ProjectService.nodeStage(n2))) continue;
                stageNodes.add(n2);
                int it = ProjectService.iterationOf(n2);
                iterations.add(it);
                if (it != 1) continue;
                firstRound.add(n2);
            }
            long done = firstRound.stream().filter(n -> "DONE".equals(String.valueOf(n.get("status")))).count();
            String status = this.stageStatusFromRun(wsId, stage);
            if (status == null) {
                status = this.stageStatus(firstRound);
            }
            LinkedHashMap<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("stage", stage);
            v.put("label", ProjectService.stageLabel(stage));
            v.put("status", status);
            v.put("resident", STAGE_OPERATE.equals(stage));
            v.put("current", stage.equals(currentStage));
            v.put("iterations", new ArrayList(iterations));
            v.put("node_count", stageNodes.size());
            v.put("done_count", done);
            v.put("nodes", stageNodes);
            views.add(v);
        }
        return views;
    }

    private String stageStatus(List<Map<String, Object>> firstRound) {
        if (firstRound.isEmpty()) {
            return "PENDING";
        }
        boolean allDone = true;
        boolean running = false;
        boolean blocked = false;
        for (Map<String, Object> n : firstRound) {
            String st = String.valueOf(n.get("status"));
            if ("RUNNING".equals(st) || "WAITING_APPROVAL".equals(st)) {
                running = true;
            } else if ("FAILED".equals(st) || "REJECTED".equals(st)) {
                blocked = true;
            }
            if ("DONE".equals(st)) continue;
            allDone = false;
        }
        if (running) {
            return "RUNNING";
        }
        if (blocked) {
            return "BLOCKED";
        }
        return allDone ? "DONE" : "PENDING";
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
        List<Map<String, Object>> nodes = this.repo.findNodesByWorkspace(wsId);
        String current = this.resolveCurrentStage(ws.get("current_stage"), nodes);
        if (STAGE_OPERATE.equals(current)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "运维阶段为常驻阶段，不可继续推进，请在运维阶段内发起迭代");
        }
        String next = STAGE_ORDER.get(STAGE_ORDER.indexOf(current) + 1);
        int iteration = this.currentIteration(wsId, current);
        Map stagePlan = this.repo.findCurrentStagePlan(wsId, current, iteration).stream().findFirst().orElse(null);
        if (stagePlan == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, ProjectService.stageLabel(current) + "阶段还没有规划，无法推进：先让 PM 出规划并经人批准");
        }
        if (!"APPROVED".equals(String.valueOf(stagePlan.get("status")))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, ProjectService.stageLabel(current) + "阶段规划当前是 " + String.valueOf(stagePlan.get("status")) + "，需人批准并开始执行后才能推进");
        }
        if (stagePlan.get("exec_run_id") == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, ProjectService.stageLabel(current) + "阶段规划已批准但没有生成执行体（exec_run_id 为空），请驳回该规划后重发，或检查服务端日志中的编译失败原因");
        }
        String stageRunId = String.valueOf(stagePlan.get("exec_run_id"));
        Map<String, Object> stageRun = this.runService.getRun(stageRunId);
        String runStatus = String.valueOf(stageRun.get("status"));
        if (!"SUCCESS".equals(runStatus)) {
            ArrayList<String> open = new ArrayList<String>();
            for (Map<String, Object> nr : this.mapList(stageRun.get("nodes"))) {
                String st = String.valueOf(nr.get("status"));
                if ("SUCCESS".equals(st)) continue;
                open.add(this.strOr(nr.get("node_name"), String.valueOf(nr.get("node_id"))) + "（" + st + "）");
            }
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, ProjectService.stageLabel(current) + "阶段流程尚未跑完（run " + stageRunId + " 状态 " + runStatus + "）" + (String)(open.isEmpty() ? "" : "，未走完的节点：" + String.join("、", open)) + "；若停在决策门，请先在待决策中完成审批");
        }
        Map summary = (Map)this.repo.findCurrentStageSummary(wsId, current, iteration).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "请先由 PM 产出「" + ProjectService.stageLabel(current) + "阶段收口总结」并经人批准，再推进阶段"));
        if (!"APPROVED".equals(String.valueOf(summary.get("status")))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "收口总结当前为 " + String.valueOf(summary.get("status")) + "，需人批准后才能推进阶段");
        }
        List<Object> openItems = this.objList(summary.get("pending_items"));
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
        this.repo.lockStageSession(wsId, current, iteration);
        long planId = this.draftStagePlan(user, wsId, next, this.currentIteration(wsId, next));
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("current_stage", next);
        result.put("advanced", true);
        result.put("from_stage", current);
        result.put("label", ProjectService.stageLabel(next));
        result.put("locked_stage", current);
        result.put("locked_iteration", iteration);
        result.put("plan_id", planId);
        result.put("plan_status", "PENDING");
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
        List<Map<String, Object>> nodes = this.repo.findNodesByWorkspace(wsId);
        String stage = this.resolveCurrentStage(ws.get("current_stage"), nodes);
        int iteration = this.currentIteration(wsId, stage);
        int done = 0;
        ArrayList<String> unfinished = new ArrayList<String>();
        ArrayList<String> artifacts = new ArrayList<String>();
        Map stagePlan = this.repo.findCurrentStagePlan(wsId, stage, iteration).stream().findFirst().orElse(null);
        Object v0 = execRunId = stagePlan == null ? null : stagePlan.get("exec_run_id");
        if (execRunId != null) {
            for (Map<String, Object> nr : this.mapList(this.runService.getRun(String.valueOf(execRunId)).get("nodes"))) {
                String nid = String.valueOf(nr.get("node_id"));
                if (nid.equalsIgnoreCase("start") || nid.equalsIgnoreCase("end")) continue;
                if ("SUCCESS".equals(String.valueOf(nr.get("status")))) {
                    ++done;
                    continue;
                }
                unfinished.add(this.strOr(nr.get("node_name"), nid) + "(" + String.valueOf(nr.get("status")) + ")");
            }
        } else {
            for (Map<String, Object> n : nodes) {
                if (!stage.equals(ProjectService.nodeStage(n)) || ProjectService.iterationOf(n) != iteration) continue;
                if ("DONE".equals(String.valueOf(n.get("status")))) {
                    ++done;
                    continue;
                }
                unfinished.add(this.strOr(n.get("name"), String.valueOf(n.get("type"))) + "(" + String.valueOf(n.get("status")) + ")");
            }
        }
        for (Map<String, Object> a : this.repo.findArtifactsByWorkspace(wsId)) {
            if (!stage.equals(String.valueOf(a.get("stage")))) continue;
            artifacts.add(String.valueOf(a.get("name")));
        }
        StringBuilder md = new StringBuilder();
        md.append("## ").append(ProjectService.stageLabel(stage)).append("阶段收口（第 ").append(iteration).append(" 轮）\n\n").append("- 子任务完成：").append(done).append(" / ").append(done + unfinished.size()).append("\n").append("- 未完成子任务：").append(unfinished.isEmpty() ? "无" : String.join("、", unfinished)).append("\n").append("- 本阶段留档产物：").append(artifacts.isEmpty() ? "无" : String.join("、", artifacts)).append("\n").append("- 结论与交付：待 PM 补充（当前为系统按库内事实自动汇总）\n").append("- 风险与遗留：见下方未决项，需人确认是否可带入下一阶段\n");
        ArrayList pendingItems = new ArrayList(unfinished);
        long id = this.repo.insertStageSummary(wsId, stage, iteration, md.toString(), this.toJson(pendingItems));
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

    /*
     * WARNING - void declaration
     */
    @Transactional
    public Map<String, Object> openIteration(LoginUser user, String wsId, Map<String, Object> body) {
        Map<String, Object> ws = this.requireProject(user, wsId);
        List<Map<String, Object>> exist = this.repo.findNodesByWorkspace(wsId);
        String current = this.resolveCurrentStage(ws.get("current_stage"), exist);
        if (!STAGE_OPERATE.equals(current)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "仅运维阶段可发起迭代，当前处于「" + ProjectService.stageLabel(current) + "」阶段");
        }
        Object rawTemplate = ws.get("template_code");
        if (rawTemplate == null || String.valueOf(rawTemplate).isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "该项目未关联模板，无法按模板生成迭代节点链");
        }
        Map<String, Object> tpl = this.requireTemplate(String.valueOf(rawTemplate));
        long templateId = ((Number)tpl.get("id")).longValue();
        ArrayList<Map<String, Object>> pick = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> tn : this.repo.findTemplateNodes(templateId)) {
            String st = ProjectService.resolveStage(tn.get("stage"), tn.get("type"));
            if (!STAGE_REQUIREMENT.equals(st) && !"DEVELOP".equals(st)) continue;
            pick.add(tn);
        }
        if (pick.isEmpty()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "模板「" + String.valueOf(tpl.get("name")) + "」没有需求/开发阶段节点，无法生成迭代链");
        }
        int iteration = this.repo.maxIteration(wsId) + 1;
        int maxSeq = 0;
        for (Map map : exist) {
            maxSeq = Math.max(maxSeq, ((Number)map.get("seq")).intValue());
        }
        LinkedHashMap<Integer, Integer> seqMap = new LinkedHashMap<Integer, Integer>();
        int n = maxSeq;
        for (Map map : pick) {
            seqMap.put(((Number)map.get("seq")).intValue(), ++n);
        }
        String name = body == null || body.get("name") == null ? "" : body.get("name").toString().trim();
        String string = String.valueOf(tpl.get("code"));
        ArrayList created = new ArrayList();
        for (Map map : pick) {
            int oldSeq = ((Number)map.get("seq")).intValue();
            int newSeq = (Integer)seqMap.get(oldSeq);
            ArrayList<Integer> deps = new ArrayList<Integer>();
            for (Integer dep : this.parseIntList(map.get("depends_on"))) {
                Integer mapped = (Integer)seqMap.get(dep);
                if (mapped == null) continue;
                deps.add(mapped);
            }
            String scope = name.isEmpty() ? this.blankToNull(map.get("deliverable")) : name;
            Map<String, Object> workflowRef = this.snapshotWorkflowRef(map.get("workflow_def_id"), string, oldSeq);
            this.repo.insertNode(this.depId(wsId, newSeq), wsId, (String)map.get("type"), newSeq, "NOT_STARTED", this.toJson(this.mapSeqToIds(wsId, deps)), this.toJson(this.parseStringList(map.get("preconditions"))), this.toJson(workflowRef), this.toJson(List.of()), this.toJson(List.of()), (String)map.get("owner_role"), scope, STAGE_OPERATE, iteration, user.username());
            LinkedHashMap<String, Object> n2 = new LinkedHashMap<String, Object>();
            n2.put("id", this.depId(wsId, newSeq));
            n2.put("type", map.get("type"));
            n2.put("seq", newSeq);
            n2.put("status", "NOT_STARTED");
            n2.put("depends_on", this.mapSeqToIds(wsId, deps));
            n2.put("owner_role", map.get("owner_role"));
            n2.put("stage", STAGE_OPERATE);
            n2.put("iteration", iteration);
            n2.put("workflow_ref", workflowRef);
            n2.put("scope", scope);
            created.add(n2);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("iteration", iteration);
        result.put("stage", STAGE_OPERATE);
        result.put("name", name);
        result.put("node_count", created.size());
        result.put("nodes", created);
        return result;
    }

    @Transactional
    public long draftStagePlan(LoginUser user, String wsId, String stage, int iteration) {
        Map<String, Object> plan = this.defaultPlanFor(stage);
        long planId = this.repo.insertStagePlan(wsId, stage, iteration, this.toJson(plan), (String)plan.get("deviation"));
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
        List<Map<String, Object>> nodes = this.repo.findNodesByWorkspace(wsId);
        String stage = this.resolveCurrentStage(ws.get("current_stage"), nodes);
        List<Map<String, Object>> rows = this.repo.findCurrentStagePlan(wsId, stage, iteration = this.currentIteration(wsId, stage));
        if (rows.isEmpty()) {
            long fresh = this.draftStagePlan(user, wsId, stage, iteration);
            rows = this.repo.findStagePlan(fresh);
        }
        Map<String, Object> row = rows.get(0);
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("plan_id", ((Number)row.get("id")).longValue());
        out.put("stage", stage);
        out.put("stage_label", ProjectService.stageLabel(stage));
        out.put("iteration", iteration);
        out.put("status", row.get("status"));
        out.put("plan", this.parseJsonMap(row.get("plan_json")));
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
            long fresh = this.repo.insertStagePlan(wsId, stage, iteration, this.toJson(draft), "已按驳回理由重发默认草稿（人工可改）：" + reason);
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
        this.materializePlan(wsId, row, user.username());
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
                draft.put("requirement", this.trimTo(requirement, 2000));
            }
            int iteration = STAGE_OPERATE.equals(stage) ? Math.max(1, this.repo.maxIteration(wsId)) : 1;
            planId = this.repo.insertStagePlan(wsId, stage, iteration, this.toJson(draft), "AI 自动生成（对话驱动）" + (requirement == null || requirement.isBlank() ? "" : "：用户需求已带入执行输入"));
        } else {
            planId = ((Number)planRow.get("id")).longValue();
            if (!"APPROVED".equals(String.valueOf(planRow.get("status"))) && requirement != null && !requirement.isBlank() && this.parseJsonMap(planRow.get("plan_json")).get("requirement") == null) {
                LinkedHashMap<String, Object> pj = new LinkedHashMap<String, Object>(this.parseJsonMap(planRow.get("plan_json")));
                pj.put("requirement", this.trimTo(requirement, 2000));
                this.repo.updatePlanJson(planId, this.toJson(pj));
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
        Map<String, Object> plan = this.parseJsonMap(planRow.get("plan_json"));
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
        String projectName = this.strOr(ws.get("name"), wsId);
        Map<String, Object> plan = this.parseJsonMap(planRow.get("plan_json"));
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
        StagePlanCompiler.Compiled compiled = this.planCompiler.compile(projectName, stage, iteration, plan, this.parseJsonMap(capability.get("capability")));
        String flowName = this.trimTo("[阶段] " + projectName + " · " + stage + " 第" + iteration + "轮", 128);
        long definitionId = this.definitionService.create(flowName, "PM 阶段规划编译生成（plan_id=" + planId + "）", compiled.canvasJson(), user);
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
        input.put("quality_gates", this.objList(plan.get("quality_gates")));
        input.put("requirement", this.strOr(plan.get("requirement"), ""));
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
        Map<String, Object> cap = this.parseJsonMap(capability.get("capability"));
        ArrayList<String> refs = new ArrayList<String>();
        for (String key : List.of("tools", "connectors", "artifacts")) {
            for (Object o : this.objList(cap.get(key))) {
                if (o == null || String.valueOf(o).isBlank()) continue;
                refs.add(String.valueOf(o).trim());
            }
        }
        ArrayList<Map<String, Object>> brief = new ArrayList<Map<String, Object>>(this.repo.describeCapabilityRefs(refs));
        for (Object o : this.objList(cap.get("urls"))) {
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

    private List<Map<String, Object>> materializePlan(String wsId, Map<String, Object> planRow, String createdBy) {
        long planId = ((Number)planRow.get("id")).longValue();
        String stage = String.valueOf(planRow.get("stage"));
        int iteration = ((Number)planRow.get("iteration")).intValue();
        ArrayList<Map<String, Object>> existing = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> n : this.repo.findNodesByWorkspace(wsId)) {
            Number num;
            Object object = n.get("plan_id");
            if (!(object instanceof Number) || (num = (Number)object).longValue() != planId) continue;
            existing.add(n);
        }
        if (!existing.isEmpty()) {
            return existing;
        }
        Map<String, Object> plan = this.parseJsonMap(planRow.get("plan_json"));
        List<Map<String, Object>> tasks = this.mapList(plan.get("tasks"));
        int cursor = this.repo.maxSeq(wsId);
        LinkedHashMap<Integer, Integer> keyToSeq = new LinkedHashMap<Integer, Integer>();
        for (Map<String, Object> t : tasks) {
            keyToSeq.put(this.intOr(t.get("key"), keyToSeq.size() + 1), ++cursor);
        }
        LinkedHashMap keyToId = new LinkedHashMap();
        keyToSeq.forEach((k, s) -> keyToId.put(k, this.depId(wsId, (int)s)));
        ArrayList<Map<String, Object>> created = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> t : tasks) {
            int seq = keyToSeq.getOrDefault(this.intOr(t.get("key"), 0), ++cursor);
            ArrayList<String> deps = new ArrayList<String>();
            for (Object d : this.objList(t.get("depends"))) {
                String id = (String)keyToId.get(this.intOr(d, -1));
                if (id == null) continue;
                deps.add(id);
            }
            String type = this.strOr(t.get("type"), "DEVELOP").toUpperCase();
            String name = this.strOr(t.get("name"), type);
            List<String> agents = this.strList(t.get("agents"));
            List<String> participants = this.strList(t.get("participants"));
            String trigger = this.strOr(t.get("trigger_mode"), "AUTO").toUpperCase();
            String approval = this.strOr(t.get("approval_mode"), "REVIEW").toUpperCase();
            if (FORCE_TYPES.contains(type)) {
                approval = "FORCE";
                trigger = "MANUAL_START";
            }
            LinkedHashMap<String, Object> ext = new LinkedHashMap<String, Object>();
            ext.put("name", name);
            ext.put("trigger_mode", trigger);
            ext.put("approval_mode", approval);
            ext.put("participants", this.toJson(participants));
            ext.put("plan_id", planId);
            String nodeId = this.depId(wsId, seq);
            this.repo.insertNode(nodeId, wsId, type, seq, "NOT_STARTED", this.toJson(deps), this.toJson(List.of()), null, this.toJson(List.of()), this.toJson(List.of()), agents.isEmpty() ? null : agents.get(0), this.strOr(t.get("scope"), null), stage, iteration, createdBy, ext);
            LinkedHashMap<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("id", nodeId);
            n.put("type", type);
            n.put("name", name);
            n.put("seq", seq);
            n.put("stage", stage);
            n.put("iteration", iteration);
            n.put("status", "NOT_STARTED");
            n.put("depends_on", deps);
            n.put("agents", agents);
            n.put("participants", participants);
            n.put("trigger_mode", trigger);
            n.put("approval_mode", approval);
            n.put("scope", t.get("scope"));
            created.add(n);
        }
        log.info("规划落子任务: project={} plan_id={} stage={} iteration={} nodes={}", new Object[]{wsId, planId, stage, iteration, created.size()});
        return created;
    }

    public Map<String, Object> stageExecution(LoginUser user, String wsId) {
        Map<String, Object> ws = this.requireProject(user, wsId);
        List<Map<String, Object>> nodes = this.repo.findNodesByWorkspace(wsId);
        String current = this.resolveCurrentStage(ws.get("current_stage"), nodes);
        int iteration = this.currentIteration(wsId, current);
        Map plan = this.repo.findCurrentStagePlan(wsId, current, iteration).stream().findFirst().orElse(null);
        Map<String, Object> clarify = this.chatService.latestClarification(wsId, current, iteration);
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        List<StageStep> steps = this.stepRepo.findByStage(wsId, current, iteration);
        boolean orchestrationMode = !steps.isEmpty();
        out.put("stage", current);
        out.put("stage_label", ProjectService.stageLabel(current));
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
        List<Map<String, Object>> nodeRuns = this.mapList(run.get("nodes"));
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
        boolean waiting = steps.stream().anyMatch(s -> s.status() == StepStatus.WAITING);
        boolean executing = steps.stream().anyMatch(s -> s.status() == StepStatus.EXECUTING);
        long settled = steps.stream().filter(s -> s.status() == StepStatus.DONE || s.status() == StepStatus.SKIPPED).count();
        String state = waiting ? "WAITING_DECISION" : (executing ? "EXECUTING" : (settled == (long)steps.size() ? "FINISHED" : "EXECUTING"));
        out.put("state", state);
        out.put("steps", steps);
        out.put("node_runs", List.of());
        ArrayList waitingList = new ArrayList();
        for (StageStep s2 : steps) {
            if (s2.status() != StepStatus.WAITING) continue;
            LinkedHashMap<String, Object> w = new LinkedHashMap<String, Object>();
            w.put("node_run_id", s2.id());
            w.put("node_id", String.valueOf(s2.seq()));
            w.put("node_name", s2.name());
            w.put("output", s2.instruction());
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
        b.put("tasks", this.mapList(this.parseJsonMap(plan.get("plan_json")).get("tasks")));
        return b;
    }

    public Map<String, Object> decideStageGate(LoginUser user, String wsId, Map<String, Object> body) {
        String gateNodeId;
        String comment;
        String decision;
        String nodeId;
        int iteration;
        Map<String, Object> ws = this.requireProject(user, wsId);
        List<Map<String, Object>> nodes = this.repo.findNodesByWorkspace(wsId);
        String current = this.resolveCurrentStage(ws.get("current_stage"), nodes);
        Map plan = (Map)this.repo.findCurrentStagePlan(wsId, current, iteration = this.currentIteration(wsId, current)).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, ProjectService.stageLabel(current) + "阶段还没有规划，无门可审"));
        if (plan.get("exec_run_id") == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, ProjectService.stageLabel(current) + "阶段尚未开始执行，无门可审");
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
        out.put("capability", rows.isEmpty() ? new LinkedHashMap() : this.parseJsonMap(rows.get(0).get("capability_json")));
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
            cap.put(k, this.strList(body == null ? null : body.get(k)));
        }
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note")).trim();
        this.repo.upsertStageCapability(wsId, st, this.toJson(cap), note, user.username());
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
        Long definitionId = this.optionalLong(rawDefId, "template " + template + " seq=" + seq + " workflow_def_id");
        if (definitionId == null) {
            return null;
        }
        if (!this.repo.isDefinitionPublished(definitionId)) {
            log.warn("template {} seq={} 默认工作流 #{} 不存在或未发布，跳过快照（节点不预绑流程）", new Object[]{template, seq, definitionId});
            return null;
        }
        return Map.of("definitionId", definitionId);
    }

    private Map<String, Object> parseScopes(Object raw) {
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<?, ?> map = (Map<?, ?>)raw;
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }

    private String blankToNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private List<String> mapSeqToIds(String wsId, List<Integer> seqs) {
        ArrayList<String> ids = new ArrayList<String>();
        for (Integer seq : seqs) {
            ids.add(this.depId(wsId, seq));
        }
        return ids;
    }

    private List<Integer> parseIntList(Object json) {
        if (json == null) {
            return List.of();
        }
        try {
            Object parsed = this.objectMapper.readValue(json.toString(), Object.class);
            if (parsed instanceof List) {
                List list = (List)parsed;
                ArrayList<Integer> result = new ArrayList<Integer>();
                for (Object o : list) {
                    if (o instanceof Number) {
                        Number num = (Number)o;
                        result.add(num.intValue());
                        continue;
                    }
                    if (o == null) continue;
                    try {
                        result.add(Integer.parseInt(o.toString().trim()));
                    }
                    catch (NumberFormatException numberFormatException) {}
                }
                return result;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return List.of();
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

    private String depId(String wsId, int seq) {
        return wsId + "_n" + seq;
    }

    private void requireWorkspaceVisible(LoginUser user, String wsId) {
        Map ws = (Map)this.workspaceRepo.findById(wsId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "project not found"));
        this.requireOwner(user, ws);
    }

    private Map<String, Object> requireNodeInWorkspace(String wsId, String nodeId) {
        Map node = (Map)this.repo.findNodeById(nodeId).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "node not found"));
        if (!wsId.equals(node.get("workspace_id"))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "node 不属于该项目");
        }
        return node;
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

    private List<String> parseStringList(Object json) {
        if (json == null) {
            return List.of();
        }
        try {
            Object parsed = this.objectMapper.readValue(json.toString(), Object.class);
            if (parsed instanceof List) {
                List list = (List)parsed;
                ArrayList<String> result = new ArrayList<String>();
                for (Object o : list) {
                    result.add(o == null ? null : o.toString());
                }
                return result;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return List.of();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return this.objectMapper.writeValueAsString(value);
        }
        catch (Exception e) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "JSON 序列化失败");
        }
    }

    private Integer parseVersion(Object v) {
        if (v instanceof Number) {
            Number n = (Number)v;
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return null;
    }

    private Map<String, Object> parseJsonMap(Object raw) {
        if (raw == null) {
            return new LinkedHashMap<String, Object>();
        }
        if (raw instanceof Map) {
            Map map = (Map)raw;
            return new LinkedHashMap<String, Object>(map);
        }
        String text = raw.toString().trim();
        if (text.isEmpty() || "null".equals(text)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object parsed = this.objectMapper.readValue(text, Object.class);
            if (parsed instanceof Map) {
                Map m = (Map)parsed;
                return new LinkedHashMap<String, Object>(m);
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return new LinkedHashMap<String, Object>();
    }

    private List<Object> objList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List) {
            List list = (List)raw;
            return list;
        }
        try {
            Object parsed = this.objectMapper.readValue(raw.toString(), Object.class);
            if (parsed instanceof List) {
                List list = (List)parsed;
                return list;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return List.of();
    }

    private List<Map<String, Object>> mapList(Object raw) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object o : this.objList(raw)) {
            if (!(o instanceof Map)) continue;
            Map m = (Map)o;
            result.add(new LinkedHashMap(m));
        }
        return result;
    }

    private List<String> strList(Object raw) {
        ArrayList<String> result = new ArrayList<String>();
        for (Object o : this.objList(raw)) {
            result.add(o == null ? null : o.toString());
        }
        return result;
    }

    private String strOr(Object v, String def) {
        if (v == null) {
            return def;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? def : s;
    }

    private int intOr(Object v, int def) {
        if (v instanceof Number) {
            Number n = (Number)v;
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return def;
    }
}
