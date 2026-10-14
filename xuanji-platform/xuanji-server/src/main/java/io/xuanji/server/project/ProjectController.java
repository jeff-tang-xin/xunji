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

    public ProjectController(ProjectService projects, ProjectChatService projectChat,
                             ProjectTemplateService templates) {
        this.projects = projects;
        this.projectChat = projectChat;
        this.templates = templates;
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