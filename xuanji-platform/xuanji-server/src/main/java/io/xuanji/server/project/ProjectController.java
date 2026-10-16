package io.xuanji.server.project;

import io.xuanji.server.auth.LoginUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 项目端点（设计文档 docs/sdlc-lifecycle-design.md 阶段 A2）：创建/加载双线路。
 *
 * <p>创建线路（POST /api/projects）：模板驱动，一次性生成节点链，用户不感知节点细节。
 * 加载线路（GET /api/projects/{id}）：数据驱动，返回节点链全貌（进度/留档/决策门），
 * 用户进入工作现场。节点操作：执行节点 / 查看节点留档 / 登记留档 / 节点内对话。</p>
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projects;
    private final ProjectChatService projectChat;
    private final ProjectTemplateService templates;
    private final io.xuanji.server.project.orchestrator.StageOrchestrator orchestrator;
    private final io.xuanji.core.runtime.AgentRuntime agentRuntime;
    private final io.xuanji.server.engine.ToolApprovalStore toolApprovals;
    private final io.xuanji.server.project.orchestrator.StageChatEventBus chatEventBus;
    private final io.xuanji.server.project.orchestrator.ProjectEventBus projectEventBus;

    public ProjectController(ProjectService projects, ProjectChatService projectChat,
                             ProjectTemplateService templates,
                             io.xuanji.server.project.orchestrator.StageOrchestrator orchestrator,
                             io.xuanji.core.runtime.AgentRuntime agentRuntime,
                             io.xuanji.server.engine.ToolApprovalStore toolApprovals,
                             io.xuanji.server.project.orchestrator.StageChatEventBus chatEventBus,
                             io.xuanji.server.project.orchestrator.ProjectEventBus projectEventBus) {
        this.projects = projects;
        this.projectChat = projectChat;
        this.templates = templates;
        this.orchestrator = orchestrator;
        this.agentRuntime = agentRuntime;
        this.toolApprovals = toolApprovals;
        this.chatEventBus = chatEventBus;
        this.projectEventBus = projectEventBus;
    }

    /** 项目列表（数据权限过滤，仅 PROJECT 类型）。 */
    @GetMapping
    public Object list(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return projects.list(user);
    }

    /** 模板列表（供前端创建项目下拉动态加载）。注意须在 /{id} 之前声明，避免被路径变量捕获。 */
    @GetMapping("/templates")
    public Object templates(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return templates.templates();
    }

    /** 模板详情：主信息 + 全部节点（规范/要求/方法论/交付物），供模板编辑表单回填。 */
    @GetMapping("/templates/{code}")
    public Object templateDetail(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                 @PathVariable String code) {
        return templates.templateDetail(code);
    }

    /**
     * 创建模板（通用层）：body = {code, name, description, nodes[]}，
     * 每个节点带 standards/requirements/methodology/deliverable/owner_role/seq/depends_on/preconditions。
     */
    @PostMapping("/templates")
    public Object createTemplate(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                 @RequestBody Map<String, Object> body) {
        return templates.createTemplate(body);
    }

    /** 更新模板：改 name/description，nodes 整体替换；code 不可改，内置模板允许改知识内容。 */
    @PutMapping("/templates/{code}")
    public Object updateTemplate(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                 @PathVariable String code,
                                 @RequestBody Map<String, Object> body) {
        return templates.updateTemplate(code, body);
    }

    /** 删除模板（连带删除其节点）；内置模板 builtin=true 拒绝删除。 */
    @DeleteMapping("/templates/{code}")
    public Object deleteTemplate(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                 @PathVariable String code) {
        return templates.deleteTemplate(code);
    }

    /** 创建项目（模板驱动生成节点链）。 */
    @PostMapping
    public Object create(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                         @RequestBody Map<String, Object> body) {
        return projects.create(user, body);
    }

    /** 加载项目（返回节点链全貌 + 四阶段分组视图）。 */
    @GetMapping("/{id}")
    public Object get(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return projects.get(user, id);
    }

    /**
     * 推进大阶段（需求→开发→运维，单向不可回退）。放行条件见
     * {@link ProjectService#advanceStage}：子任务全完成 + 收口总结已批准 + 遗留项已清或被显式豁免。
     * 运维阶段为常驻终态，再推进返回 400。
     */
    @PostMapping("/{id}/stage/advance")
    public Object advanceStage(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                               @PathVariable String id,
                               @RequestBody(required = false) Map<String, Object> body) {
        return projects.advanceStage(user, id, body);
    }

    /**
     * 运维阶段发起迭代（body 可选 {"name":"新需求：xxx"}）。
     * 大阶段不回退，迭代可重复；仅 current_stage=OPERATE 时可用。
     */
    @PostMapping("/{id}/iterations")
    public Object openIteration(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                @PathVariable String id,
                                @RequestBody(required = false) Map<String, Object> body) {
        return projects.openIteration(user, id, body);
    }

    /* ---------- V27：PM 阶段规划 / 收口总结 / 阶段能力与资源绑定 ---------- */

    /** 当前阶段的 PM 规划（生效那份 + 能力绑定）；尚无规划时后端自动补一份待审草稿。 */
    @GetMapping("/{id}/plan")
    public Object plan(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return projects.planDetail(user, id);
    }

    /**
     * 审核 PM 规划：{@code {"decision":"APPROVE"}} 落子任务节点；
     * {@code {"decision":"REJECT","reason":"..."}} 驳回（理由必填）并回一份新草稿。
     */
    @PostMapping("/{id}/plan/{planId}/decide")
    public Object decidePlan(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                             @PathVariable String id, @PathVariable long planId,
                             @RequestBody Map<String, Object> body) {
        return projects.decidePlan(user, id, planId, body);
    }

    /**
     * 阶段执行视图：规划 → 编译出的工作流 → run 与节点序列 + 待决策门。
     * 前端中栏（执行流程、子任务状态、AI 输出、决策卡）都从这里取。
     */
    @GetMapping("/{id}/stage-execution")
    public Object stageExecution(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return projects.stageExecution(user, id);
    }

    /**
     * 需求清单（V34 需求先行）：需求项 + 依赖拓扑深度（前端分层画拓扑图）。
     * depth = 依赖链长度（无依赖 0，依赖 depth 最大值 +1）；循环依赖理论上有 declare 防环兜底，此处防御性封顶。
     */
    @GetMapping("/{id}/requirements")
    public Object requirements(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return projects.requirements(user, id);
    }

    /** 追溯链：需求 → 三阶段步骤 → 产出证据 的端到端关联（主视图，用户定调）。 */
    @GetMapping("/{id}/trace")
    public Object trace(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return projects.trace(user, id);
    }

    /** 项目文件树：列出项目目录（root_path）下的文件与子目录——代码/文档产出直接可见。 */
    @GetMapping("/{id}/files")
    public Object files(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return projects.listFiles(user, id);
    }

    /** 编辑项目：body = {"name":"新名","servers":[…],"connectors":["connector:{id}"]}（servers/connectors 显式传入即覆盖，空数组=清空）。 */
    @PutMapping("/{id}")
    public Object update(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id,
                         @RequestBody Map<String, Object> body) {
        return projects.updateProject(user, id, body);
    }

    /** 删除项目：级联清理项目域数据 + 递归删除项目目录（破坏性，前端须二次确认）。 */
    @DeleteMapping("/{id}")
    public Object delete(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return projects.deleteProject(user, id);
    }

    /** 读项目文件内容：path 为相对项目根的相对路径（≤200KB 文本）。 */
    @GetMapping("/{id}/files/content")
    public Object fileContent(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id,
                              @RequestParam("path") String path) {
        return projects.readFile(user, id, path);
    }

    /**
     * 审批阶段流程里的决策门：{@code {"node_id":"t2","decision":"APPROVE","comment":"..."}}。
     *
     * <p>直接交给引擎的 resume（驳回带批注会触发该节点按意见重生成）。项目侧不另写一套审批状态——
     * 否则迟早出现"人在项目里批了、引擎还在等"的两边不同步。</p>
     */
    @PostMapping("/{id}/gate/decide")
    public Object decideGate(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id,
                             @RequestBody Map<String, Object> body) {
        return projects.decideStageGate(user, id, body);
    }

    /**
     * 重跑当前阶段：清掉旧执行（进程中断遗留的孤儿 RUNNING / FAILED），按既有执行路径直接重新起跑。
     * SUCCESS/SUSPENDED 不允许（前者走「开启迭代」，后者走决策门审批）。
     */
    @PostMapping("/{id}/stage/{stage}/rerun")
    public Object rerunStage(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id,
                             @PathVariable String stage) {
        return projects.rerunStage(user, id, stage);
    }

    /** 让 PM 产出当前阶段收口总结（PENDING，待人批准）。 */
    @PostMapping("/{id}/summary/draft")
    public Object draftSummary(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        return projects.draftStageSummary(user, id);
    }

    /** 批准 / 驳回收口总结：批准是推进阶段的硬前置，驳回则留在本阶段继续干活。 */
    @PostMapping("/{id}/summary/{summaryId}/decide")
    public Object decideSummary(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                @PathVariable String id, @PathVariable long summaryId,
                                @RequestBody Map<String, Object> body) {
        return projects.decideStageSummary(user, id, summaryId, body);
    }

    /** 读取某阶段的能力与资源绑定（未绑定返回空结构）。 */
    @GetMapping("/{id}/stage/{stage}/capability")
    public Object capability(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                             @PathVariable String id, @PathVariable String stage) {
        return projects.capabilityOf(user, id, stage);
    }

    /**
     * 保存某阶段的能力与资源绑定（整体覆盖）：
     * body {"tools":[…],"skills":[…],"mcps":[…],"connectors":[…],"artifacts":[…],"urls":[…],"note":"…"}
     */
    @PutMapping("/{id}/stage/{stage}/capability")
    public Object saveCapability(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                 @PathVariable String id, @PathVariable String stage,
                                 @RequestBody(required = false) Map<String, Object> body) {
        return projects.saveCapability(user, id, stage, body);
    }

    /** 执行某节点（校验 DAG 依赖）。 */
    @PostMapping("/{id}/nodes/{nodeId}/run")
    public Object runNode(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                          @PathVariable String id, @PathVariable String nodeId) {
        return projects.runNode(user, id, nodeId);
    }

    /** 节点完成（显式置 DONE，驱动 DAG 链推进）。 */
    @PostMapping("/{id}/nodes/{nodeId}/complete")
    public Object completeNode(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                               @PathVariable String id, @PathVariable String nodeId) {
        return projects.completeNode(user, id, nodeId);
    }

    /** 绑定阶段工作流到节点（body 里 definitionId），绑定后 runNode 真正触发子工作流异步执行。 */
    @PostMapping("/{id}/nodes/{nodeId}/bind-workflow")
    public Object bindWorkflow(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                               @PathVariable String id, @PathVariable String nodeId,
                               @RequestBody Map<String, Object> body) {
        Long definitionId = body.get("definitionId") == null ? null
                : Long.valueOf(body.get("definitionId").toString().trim());
        return projects.bindWorkflowRef(user, id, nodeId, definitionId);
    }

    /** 查看节点留档。 */
    @GetMapping("/{id}/nodes/{nodeId}/artifacts")
    public List<Map<String, Object>> nodeArtifacts(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                                   @PathVariable String id, @PathVariable String nodeId) {
        return projects.nodeArtifacts(user, id, nodeId);
    }

    /** 登记节点留档（过程/交付两形态）。 */
    @PostMapping("/{id}/nodes/{nodeId}/artifacts")
    public Object addArtifact(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                              @PathVariable String id, @PathVariable String nodeId,
                              @RequestBody Map<String, Object> body) {
        return projects.addArtifact(user, id, nodeId, body);
    }

    /** 节点内对话（SSE 流式）：按 owner_role 匹配内置 agent，结束后自动登记 DELIVERABLE 留档。 */
    @PostMapping(value = "/{id}/nodes/{nodeId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                           @PathVariable String id, @PathVariable String nodeId,
                           @RequestBody(required = false) Map<String, Object> body) {
        String query = body == null || body.get("query") == null ? null : body.get("query").toString();
        return projectChat.chatByNode(user, id, nodeId, query);
    }

    /**
     * 阶段级对话（SSE 流式）：对话对象是「当前阶段的子任务」，不依赖 {@code project_work_node} 台账。
     * V27 起规划不再落台账，新项目一个节点都没有，上面那条节点内对话在界面上已无入口，
     * 项目的主工作路径改走这里。请求体 {@code {message, task_key?}}。
     */
    @PostMapping(value = "/{id}/stage/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stageChat(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                               @PathVariable String id,
                               @RequestBody(required = false) Map<String, Object> body) {
        return projectChat.chatByStage(user, id, body);
    }

    /**
     * 阶段对话事件总线订阅（SSE 常驻）：后端系统唤醒的主控轮（任务/讨论落板、决策答复自动续跑）
     * 没有请求级 SSE——事件发总线，前端订阅本端点实时回显。前端发起的对话轮不进总线（已有请求级流，
     * 双路会重复渲染）。离线时事件已落 agent_state，回放兜底。
     */
    @GetMapping(value = "/{id}/stage/chat/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stageChatEvents(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                      @PathVariable String id,
                                      @RequestParam String stage,
                                      @RequestParam int iteration) {
        return chatEventBus.subscribe(io.xuanji.server.project.orchestrator.StageChatEventBus.key(id, stage, iteration));
    }

    /**
     * 项目状态事件总线订阅（SSE 常驻）：项目域状态变化（步骤落终态/决策答复/需求确认）
     * 实时推给在线前端，替代「状态变了要手动刷新」。离线时数据在库里，重开页面拉全量兜底。
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter projectEvents(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                    @PathVariable String id) {
        return projectEventBus.subscribe(id);
    }

    /**
     * 工具授权确认（HITL）：对话流以 agent.confirm 事件结束后，用户在授权卡上批准/拒绝，
     * 前端调本端点恢复执行。body = {sessionId, decisions:[{toolCallId, tool, approved, rememberSession}]}。
     * rememberSession=true 且 approved 时记入会话级授权（本项目会话内该工具后续调用不再询问，
     * 重启失效）；返回恢复执行期间的全部事件（result 文本前端追加进聊天框）。
     */
    @PostMapping("/{id}/stage/chat/confirm")
    public Object confirmTool(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                              @PathVariable String id,
                              @RequestBody Map<String, Object> body) {
        String sessionId = body.get("sessionId") == null ? "" : body.get("sessionId").toString();
        if (sessionId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "sessionId 必填");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = body.get("decisions") instanceof List ? (List<Map<String, Object>>) body.get("decisions") : List.of();
        List<io.xuanji.core.runtime.AgentConfirm> confirms = new java.util.ArrayList<>();
        for (Map<String, Object> d : decisions) {
            String toolCallId = d.get("toolCallId") == null ? null : d.get("toolCallId").toString();
            if (toolCallId == null) {
                continue;
            }
            boolean approved = Boolean.TRUE.equals(d.get("approved"));
            boolean remember = Boolean.TRUE.equals(d.get("rememberSession"));
            if (approved && remember) {
                String tool = d.get("tool") == null ? null : d.get("tool").toString();
                if (tool != null && !tool.isBlank()) {
                    toolApprovals.grant(id, tool);
                }
            }
            confirms.add(new io.xuanji.core.runtime.AgentConfirm(toolCallId, approved, remember));
        }
        if (confirms.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "decisions 为空");
        }
        List<Object> events = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        agentRuntime.confirmBySession(sessionId, confirms, evt -> events.add(java.util.Map.of(
                "type", evt.type() == null ? "" : evt.type(),
                "delta", evt.delta() == null ? "" : evt.delta(),
                "text", evt.text() == null ? "" : evt.text(),
                "meta", evt.meta() == null ? java.util.Map.of() : evt.meta())));
        return Map.of("events", events);
    }

    /**
     * 项目区域直接答复决策（{@code {seq, answer}}）：DECISION 步置 DONE + 答复留档。
     * 有未处理决策时前端锁定对话输入——这里是唯一解锁通道；主控下一轮从步骤台账看到答复。
     */
    @PostMapping("/{id}/stage/decision/answer")
    public Object answerDecision(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                 @PathVariable String id,
                                 @RequestBody Map<String, Object> body) {
        return orchestrator.answerDecision(user, id, body);
    }

    /**
     * 讨论进行中用户插话（{@code {seq, text}}）：发言以【用户】前缀进讨论流，
     * 后续轮发言者与汇总人可见——用户参与讨论，而不是旁观后台黑盒。
     */
    @PostMapping("/{id}/stage/discuss/speech")
    public Object discussSpeech(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                @PathVariable String id,
                                @RequestBody Map<String, Object> body) {
        return orchestrator.discussSpeech(user, id, body);
    }

    /** 项目内全部阶段会话列表：会话粒度=阶段，供右侧栏「会话」tab 展示与切换。 */
    @GetMapping("/{id}/stage/chat/sessions")
    public List<Map<String, Object>> stageChatSessions(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                                       @PathVariable String id) {
        return projectChat.chatSessions(user, id);
    }

    /** 阶段会话历史回放：不传 stage/iteration 时取当前阶段最新一轮。 */
    @GetMapping("/{id}/stage/chat/history")
    public Map<String, Object> stageChatHistory(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                                @PathVariable String id,
                                                @RequestParam(required = false) String stage,
                                                @RequestParam(required = false) Integer iteration) {
        return projectChat.chatHistory(user, id, stage, iteration);
    }
}