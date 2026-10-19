package io.xuanji.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目（workspace 即项目）数据访问层：project_work_node / project_artifact 的 SQL。
 *
 * <p>边界：本层只做 SQL 与结果读取；节点链编排、DAG 依赖判定、留档登记等业务在
 * {@code ProjectService}。JSONB 列查询统一 {@code ::text}（规避 PGobject 序列化），
 * 写入用 {@code ?::jsonb} 占位符。</p>
 */
@Repository
public class ProjectRepository {

    private static final String NODE_COLS =
            "id, workspace_id, type, seq, status, depends_on::text AS depends_on, "
                    + "preconditions::text AS preconditions, workflow_ref::text AS workflow_ref, "
                    + "input_refs::text AS input_refs, output_refs::text AS output_refs, "
                    + "owner_role, scope, stage, iteration, name, trigger_mode, approval_mode, "
                    + "participants::text AS participants, plan_id, created_by, created_at, updated_at";

    private static final String ARTIFACT_COLS =
            "id, workspace_id, node_id, kind, name, type, file_path, content_md, "
                    + "version, status, stage, created_by, created_at";

    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------- project_template / project_template_node ---------- */

    private static final String TEMPLATE_COLS = "id, code, name, description, builtin, created_at, stages_json::text AS stages_json";

    /**
     * 模板节点列：通用层知识（standards/requirements/methodology/deliverable）+ 执行主体
     * （owner_role 职能、agent_name 内置智能体）+ 默认流程（workflow_def_id）+ DAG 结构。
     * standards/requirements/depends_on/preconditions 为 JSONB，统一 {@code ::text} 读取。
     */
    private static final String TEMPLATE_NODE_COLS =
            "id, template_id, type, seq, standards::text AS standards, "
                    + "requirements::text AS requirements, methodology, deliverable, "
                    + "depends_on::text AS depends_on, "
                    + "preconditions::text AS preconditions, owner_role, "
                    + "agent_name, workflow_def_id, stage, created_at";

    /** 按 code 查模板（含 builtin），不存在返回空列表。 */
    public List<Map<String, Object>> findTemplateByCode(String code) {
        return jdbc.queryForList(
                "SELECT " + TEMPLATE_COLS + " FROM project_template WHERE code = ?", code);
    }

    /** 查模板全部节点（按 seq 排序），JSONB 列为 ::text JSON 字符串。 */
    public List<Map<String, Object>> findTemplateNodes(long templateId) {
        return jdbc.queryForList(
                "SELECT " + TEMPLATE_NODE_COLS + " FROM project_template_node "
                        + "WHERE template_id = ? ORDER BY seq", templateId);
    }

    /** 模板列表（供前端下拉与模板管理页加载）：附 node_count 与 builtin。 */
    public List<Map<String, Object>> findTemplates() {
        return jdbc.queryForList(
                "SELECT t.id, t.code, t.name, t.description, t.builtin, "
                        + "t.stages_json::text AS stages_json, "
                        + "(SELECT COUNT(*) FROM project_template_node n WHERE n.template_id = t.id) "
                        + "AS node_count FROM project_template t ORDER BY t.id");
    }

    /** 新建模板（builtin 恒为 false：内置模板只由迁移种子产生），RETURNING id。 */
    public long insertTemplate(String code, String name, String description, String stagesJson) {
        Long id = jdbc.queryForObject(
                "INSERT INTO project_template (code, name, description, builtin, stages_json) "
                        + "VALUES (?, ?, ?, false, ?::jsonb) RETURNING id",
                Long.class, code, name, description, stagesJson);
        return id == null ? 0L : id;
    }

    /** 更新模板主信息（仅 name/description，code 与 builtin 不可改）。 */
    public void updateTemplate(long id, String name, String description, String stagesJson) {
        jdbc.update("UPDATE project_template SET name = ?, description = ?, stages_json = ?::jsonb WHERE id = ?",
                name, description, stagesJson, id);
    }

    /** 按 code 删除模板（节点由 ON DELETE CASCADE 连带删除）。 */
    public void deleteTemplateByCode(String code) {
        jdbc.update("DELETE FROM project_template WHERE code = ?", code);
    }

    /** 整体替换节点前的清空（更新模板时按新节点集重建）。 */
    public void deleteTemplateNodes(long templateId) {
        jdbc.update("DELETE FROM project_template_node WHERE template_id = ?", templateId);
    }

    /**
     * 插入模板节点：JSONB 列用 ?::jsonb，workflow_def_id 用 ?::bigint（便于 null 类型推断），
     * methodology/deliverable/agent_name 为文本，允许 null。
     */
    public void insertTemplateNode(long templateId, String type, int seq,
                                   String standardsJson, String requirementsJson,
                                   String methodology, String deliverable,
                                   String dependsOnJson, String preconditionsJson, String ownerRole,
                                   String agentName, Long workflowDefId, String stage) {
        jdbc.update("INSERT INTO project_template_node (template_id, type, seq, standards, requirements, "
                        + "methodology, deliverable, depends_on, preconditions, owner_role, "
                        + "agent_name, workflow_def_id, stage) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::bigint, ?)",
                templateId, type, seq, standardsJson, requirementsJson,
                methodology, deliverable, dependsOnJson, preconditionsJson, ownerRole,
                agentName, workflowDefId, stage);
    }

    /** 该 workspace 现有最大迭代号；无节点返回 0（发起迭代时 +1 即为新轮次）。 */
    public int maxIteration(String workspaceId) {
        Integer mx = jdbc.queryForObject(
                "SELECT MAX(iteration) FROM project_work_node WHERE workspace_id = ?",
                Integer.class, workspaceId);
        return mx == null ? 0 : mx;
    }

    /* ---------- project_work_node ---------- */

    public List<Map<String, Object>> findNodesByWorkspace(String workspaceId) {
        return jdbc.queryForList(
                "SELECT " + NODE_COLS + " FROM project_work_node WHERE workspace_id = ? ORDER BY seq",
                workspaceId);
    }

    public List<Map<String, Object>> findNodeById(String nodeId) {
        return jdbc.queryForList("SELECT " + NODE_COLS + " FROM project_work_node WHERE id = ?", nodeId);
    }

    /**
     * 插入子任务节点（V17/V26 基础字段版）：委托给带扩展字段的实现，保持既有调用点不动。
     */
    public void insertNode(String id, String workspaceId, String type, int seq, String status,
                           String dependsOnJson, String preconditionsJson, String workflowRefJson,
                           String inputRefsJson, String outputRefsJson, String ownerRole,
                           String scope, String stage, Integer iteration, String createdBy) {
        insertNode(id, workspaceId, type, seq, status, dependsOnJson, preconditionsJson, workflowRefJson,
                inputRefsJson, outputRefsJson, ownerRole, scope, stage, iteration, createdBy, Map.of());
    }

    /**
     * 插入子任务节点（V27 扩展）：{@code ext} 可带 name（界面主标题）、trigger_mode（何时开始）、
     * approval_mode（完成后要不要人点头）、participants（实际参与的 agent 数组 JSON）、plan_id（来源规划）。
     *
     * <p>参数已到 15 个，扩展字段改走 Map 而不是继续加位置参数 —— 再加就没人能读对了。</p>
     */
    public void insertNode(String id, String workspaceId, String type, int seq, String status,
                           String dependsOnJson, String preconditionsJson, String workflowRefJson,
                           String inputRefsJson, String outputRefsJson, String ownerRole,
                           String scope, String stage, Integer iteration, String createdBy,
                           Map<String, Object> ext) {
        Map<String, Object> e = ext == null ? Map.of() : ext;
        jdbc.update("INSERT INTO project_work_node (id, workspace_id, type, seq, status, depends_on, "
                        + "preconditions, workflow_ref, input_refs, output_refs, owner_role, scope, "
                        + "stage, iteration, name, trigger_mode, approval_mode, participants, plan_id, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, "
                        + "COALESCE(?, 1), ?, COALESCE(?, 'AUTO'), COALESCE(?, 'REVIEW'), ?::jsonb, ?, ?)",
                id, workspaceId, type, seq, status, dependsOnJson, preconditionsJson, workflowRefJson,
                inputRefsJson, outputRefsJson, ownerRole, scope, stage, iteration,
                e.get("name"), e.get("trigger_mode"), e.get("approval_mode"),
                e.get("participants"), e.get("plan_id"), createdBy);
    }

    public void updateNodeStatus(String nodeId, String status) {
        jdbc.update("UPDATE project_work_node SET status = ?, updated_at = now() WHERE id = ?",
                status, nodeId);
    }

    public void updateNodeRefs(String nodeId, String inputRefsJson, String outputRefsJson) {
        jdbc.update("UPDATE project_work_node SET input_refs = ?::jsonb, output_refs = ?::jsonb, "
                        + "updated_at = now() WHERE id = ?",
                inputRefsJson, outputRefsJson, nodeId);
    }

    /** 写入节点绑定的阶段工作流引用（workflow_ref JSONB，形如 {"definitionId":<id>}）。 */
    public void updateNodeWorkflowRef(String nodeId, String workflowRefJson) {
        jdbc.update("UPDATE project_work_node SET workflow_ref = ?::jsonb, updated_at = now() WHERE id = ?",
                workflowRefJson, nodeId);
    }

    /** 查 wf_definition 是否已发布（status=PUBLISHED），供节点绑定阶段工作流时校验。 */
    public boolean isDefinitionPublished(long definitionId) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wf_definition WHERE id = ? AND status = 'PUBLISHED'",
                Integer.class, definitionId);
        return cnt != null && cnt > 0;
    }

        /* ---------- project_artifact ---------- */

    public List<Map<String, Object>> findArtifactsByWorkspace(String workspaceId) {
        return jdbc.queryForList(
                "SELECT " + ARTIFACT_COLS + " FROM project_artifact WHERE workspace_id = ? ORDER BY created_at DESC",
                workspaceId);
    }

    public List<Map<String, Object>> findArtifactsByNode(String nodeId) {
        return jdbc.queryForList(
                "SELECT " + ARTIFACT_COLS + " FROM project_artifact WHERE node_id = ? ORDER BY created_at DESC",
                nodeId);
    }

    public void insertArtifact(String workspaceId, String nodeId, String kind, String name, String type,
                               String filePath, String contentMd, Integer version, String status, String createdBy) {
        jdbc.update("INSERT INTO project_artifact (workspace_id, node_id, kind, name, type, file_path, "
                        + "content_md, version, status, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspaceId, nodeId, kind, name, type, filePath, contentMd, version, status, createdBy);
    }

    /** 带 stage 的登记（文档双落用）：project_artifact.stage 由 V27/V29 引入，旧重载保持兼容。 */
    public void insertArtifactWithStage(String workspaceId, String nodeId, String kind, String name, String type,
                                        String filePath, String contentMd, Integer version, String status,
                                        String createdBy, String stage) {
        jdbc.update("INSERT INTO project_artifact (workspace_id, node_id, kind, name, type, file_path, "
                        + "content_md, version, status, created_by, stage) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspaceId, nodeId, kind, name, type, filePath, contentMd, version, status, createdBy, stage);
    }

    /** 回填产出文档的相对路径（文档双落：文件落盘后把路径写进既有索引）。 */
    public void updateArtifactFilePath(String workspaceId, String nodeId, String filePath) {
        jdbc.update("UPDATE project_artifact SET file_path = ? WHERE workspace_id = ? AND node_id = ?",
                filePath, workspaceId, nodeId);
    }

        /** 带阶段归属的留档登记（V27：左栏「本阶段资料」按 stage 归集）。 */
    public void insertArtifact(String workspaceId, String nodeId, String kind, String name, String type,
                               String filePath, String contentMd, Integer version, String status,
                               String stage, String createdBy) {
        jdbc.update("INSERT INTO project_artifact (workspace_id, node_id, kind, name, type, file_path, "
                        + "content_md, version, status, stage, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspaceId, nodeId, kind, name, type, filePath, contentMd, version, status, stage, createdBy);
    }

    /* ---------- V27：PM 全局会话 / 阶段会话与锁定 / 规划 / 收口 / 能力绑定 ---------- */

    private static final String STAGE_PLAN_COLS =
            "id, workspace_id, stage, iteration, plan_json::text AS plan_json, deviation, status, "
                    + "decided_by, decided_at, reject_reason, exec_definition_id, exec_run_id, created_at";

    private static final String STAGE_SUMMARY_COLS =
            "id, workspace_id, stage, iteration, content_md, pending_items::text AS pending_items, "
                    + "status, decided_by, decided_at, waived_by, waive_reason, created_at";

    /** PM 全局会话：一项目一条，重复写入只刷新 updated_at（会话密钥由调用方维护）。 */
            /** 阶段执行会话（同一 stage+iteration 一条；locked_at 非空即该阶段已结束不可再沟通）。 */
            /** 锁定阶段会话：幂等，已锁定则保留首次锁定时间（避免重复推进把时间刷没）。 */
            /** 登记 PM 阶段规划（PENDING），RETURNING id。 */
    public long insertStagePlan(String workspaceId, String stage, int iteration,
                                String planJson, String deviation) {
        Long id = jdbc.queryForObject("INSERT INTO project_stage_plan (workspace_id, stage, iteration, "
                        + "plan_json, deviation, status) VALUES (?, ?, ?, ?::jsonb, ?, 'PENDING') RETURNING id",
                Long.class, workspaceId, stage, iteration, planJson, deviation);
        return id == null ? 0L : id;
    }

    public List<Map<String, Object>> findStagePlan(long planId) {
        return jdbc.queryForList("SELECT " + STAGE_PLAN_COLS + " FROM project_stage_plan WHERE id = ?", planId);
    }

    /** 按执行定义反查规划（阶段自动流转：run SUCCESS 事件只带 definitionId，须定位到项目/阶段）。 */
    public List<Map<String, Object>> findStagePlanByExecDefinition(long definitionId) {
        return jdbc.queryForList("SELECT " + STAGE_PLAN_COLS + " FROM project_stage_plan "
                + "WHERE exec_definition_id = ? ORDER BY id DESC LIMIT 1", definitionId);
    }

    /** 该阶段当前生效的规划：优先已批准的最新一条，其次待审最新一条（前端要知道该展示哪份）。 */
    public List<Map<String, Object>> findCurrentStagePlan(String workspaceId, String stage, int iteration) {
        return jdbc.queryForList("SELECT " + STAGE_PLAN_COLS + " FROM project_stage_plan "
                + "WHERE workspace_id = ? AND stage = ? AND iteration = ? "
                + "ORDER BY CASE status WHEN 'APPROVED' THEN 0 WHEN 'PENDING' THEN 1 ELSE 2 END, id DESC "
                + "LIMIT 1", workspaceId, stage, iteration);
    }

    /** 当前阶段最新一轮规划（重跑定位用：不管状态，取 iteration 最大、id 最大的那条）。 */
    public List<Map<String, Object>> findLatestStagePlan(String workspaceId, String stage) {
        return jdbc.queryForList("SELECT " + STAGE_PLAN_COLS + " FROM project_stage_plan "
                + "WHERE workspace_id = ? AND stage = ? ORDER BY iteration DESC, id DESC LIMIT 1", workspaceId, stage);
    }

        /** 审批规划（approve/reject 都走这里，驳回理由落库以便 PM 带理由重规划）。 */
    public int decideStagePlan(long planId, String status, String decidedBy, String rejectReason) {
        return jdbc.update("UPDATE project_stage_plan SET status = ?, decided_by = ?, decided_at = now(), "
                        + "reject_reason = ? WHERE id = ? AND status = 'PENDING'",
                status, decidedBy, rejectReason, planId);
    }

    /**
     * 回写规划的执行体：批准后编译出的工作流定义与首次 run。
     *
     * <p>这两个字段同时是幂等锚点——审批按钮被重复点击时，凭 exec_definition_id 判断
     * "这条规划已经变成流程跑起来了"，避免同一规划发布出两条工作流、跑两次阶段。</p>
     */
    /** 补写规划内容（AI 驱动：用户首次对话的需求摘要回填 plan_json.requirement）。 */
    public void updatePlanJson(long planId, String planJson) {
        jdbc.update("UPDATE project_stage_plan SET plan_json = ?::jsonb WHERE id = ?", planJson, planId);
    }

    public void updatePlanExec(long planId, Long definitionId, String runId) {
        jdbc.update("UPDATE project_stage_plan SET exec_definition_id = ?, exec_run_id = ? WHERE id = ?",
                definitionId, runId, planId);
    }

    /**
     * 该项目已批准的阶段总结（按产生顺序）。
     *
     * <p>只取 APPROVED：被驳回的总结不是阶段结论，注进下游 agent 的输入只会误导。</p>
     */
    public List<Map<String, Object>> findApprovedSummaries(String workspaceId) {
        return jdbc.queryForList("SELECT " + STAGE_SUMMARY_COLS + " FROM project_stage_summary "
                + "WHERE workspace_id = ? AND status = 'APPROVED' ORDER BY id", workspaceId);
    }

    /** 登记 PM 阶段收口总结（PENDING），RETURNING id。 */
    public long insertStageSummary(String workspaceId, String stage, int iteration,
                                   String contentMd, String pendingItemsJson) {
        Long id = jdbc.queryForObject("INSERT INTO project_stage_summary (workspace_id, stage, iteration, "
                        + "content_md, pending_items, status) VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING') RETURNING id",
                Long.class, workspaceId, stage, iteration, contentMd, pendingItemsJson);
        return id == null ? 0L : id;
    }

    public List<Map<String, Object>> findCurrentStageSummary(String workspaceId, String stage, int iteration) {
        return jdbc.queryForList("SELECT " + STAGE_SUMMARY_COLS + " FROM project_stage_summary "
                + "WHERE workspace_id = ? AND stage = ? AND iteration = ? ORDER BY id DESC LIMIT 1",
                workspaceId, stage, iteration);
    }

    public List<Map<String, Object>> findStageSummary(long summaryId) {
        return jdbc.queryForList("SELECT " + STAGE_SUMMARY_COLS + " FROM project_stage_summary WHERE id = ?",
                summaryId);
    }

    /** 批准 / 驳回收口总结：批准人记 decided_by，与"带遗留项推进"的豁免人 waived_by 分开留档。 */
    public int decideStageSummary(long summaryId, String status, String decidedBy) {
        return jdbc.update("UPDATE project_stage_summary SET status = ?, decided_by = ?, decided_at = now() "
                        + "WHERE id = ? AND status = 'PENDING'",
                status, decidedBy, summaryId);
    }

    /** 遗留项非空仍要推进：显式豁免并留档（谁豁免、为什么）。 */
    public int waiveStageSummary(long summaryId, String waivedBy, String reason) {
        return jdbc.update("UPDATE project_stage_summary SET waived_by = ?, waive_reason = ? WHERE id = ?",
                waivedBy, reason, summaryId);
    }

    /** 阶段能力与资源绑定：一阶段一条，重复保存整体覆盖。 */
    public void upsertStageCapability(String workspaceId, String stage, String capabilityJson,
                                      String note, String createdBy) {
        jdbc.update("INSERT INTO project_stage_capability (workspace_id, stage, capability_json, note, created_by) "
                        + "VALUES (?, ?, ?::jsonb, ?, ?) ON CONFLICT (workspace_id, stage) DO UPDATE "
                        + "SET capability_json = EXCLUDED.capability_json, note = EXCLUDED.note, updated_at = now()",
                workspaceId, stage, capabilityJson, note, createdBy);
    }

        public List<Map<String, Object>> findStageCapability(String workspaceId, String stage) {
        return jdbc.queryForList("SELECT id, workspace_id, stage, capability_json::text AS capability_json, "
                + "note, created_by, created_at, updated_at FROM project_stage_capability "
                + "WHERE workspace_id = ? AND stage = ?", workspaceId, stage);
    }

    /** 现有最大 seq：PM 规划落子任务时从 maxSeq+1 顺延，避免与既有节点撞 id。 */
    public int maxSeq(String workspaceId) {
        Integer mx = jdbc.queryForObject(
                "SELECT MAX(seq) FROM project_work_node WHERE workspace_id = ?", Integer.class, workspaceId);
        return mx == null ? 0 : mx;
    }

    /**
     * agent name → 最新<b>已发布</b>版本（画布 AGENT 节点的 refId 来源）。
     *
     * <p>只认 PUBLISHED：发布期校验 {@code AgentNodeExecutor.validateRef} 同样只认 PUBLISHED，
     * 这里若放过 DRAFT，规划能存下来、publish 时才 400，报错点离原因太远。查不到返回空列表，
     * 由调用方决定跳过并回报——规划里出现不可执行的 agent 必须让人看见，而不是静默少一个子任务。</p>
     */
    public List<Map<String, Object>> findPublishedAgent(String agentName) {
        return jdbc.queryForList("SELECT id, name, version FROM agent_config WHERE name = ? "
                + "AND status = 'PUBLISHED' ORDER BY version DESC LIMIT 1", agentName);
    }

    /**
     * 资产最新已发布版本——画布 capabilityBinding 的引用必须写成 {@code skill:5@2}，
     * {@code CanvasJsonParser.parseRefs} 遇到不带 {@code @version} 的裸 id 直接抛异常。
     *
     * <p>kind 走白名单，SQL 全部写死：这里若允许拼表名，等于给外部开了一条能查任意表的口子。
     * 查不到返回 null，由调用方决定丢弃该绑定并告警——一条过期引用不该把整个阶段的流程发布卡死。</p>
     */
    public Integer latestPublishedVersion(String kind, long id) {
        String sql = switch (kind) {
            case "skill" -> "SELECT version FROM skill_repo WHERE id = ? AND status = 'PUBLISHED' "
                    + "ORDER BY version DESC LIMIT 1";
            case "mcp" -> "SELECT version FROM mcp_config WHERE id = ? AND status = 'PUBLISHED' "
                    + "ORDER BY version DESC LIMIT 1";
            case "tool" -> "SELECT version FROM tool_registry WHERE id = ? AND status = 'PUBLISHED' "
                    + "ORDER BY version DESC LIMIT 1";
            default -> null;
        };
        if (sql == null) {
            return null;
        }
        List<Integer> versions = jdbc.queryForList(sql, Integer.class, id);
        return versions.isEmpty() ? null : versions.get(0);
    }

    /**
     * 能力引用 → 可读清单（类型 + 名称 + 用途），供注入模型上下文。
     *
     * <p>只取展示字段：{@code connector_config.endpoint / credential_cipher}、
     * {@code mcp_config.endpoint / auth_cipher} 一律不查。凭证或内网地址一旦进入模型上下文，
     * 就等于把它交给了第三方 API 与调用日志，事后无法收回；一期让 agent 知道
     * "有哪些资源、干什么用、什么平台"即可，真正的连接由服务端在受控路径完成。</p>
     */
    public List<Map<String, Object>> describeCapabilityRefs(List<String> refs) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (refs == null) {
            return out;
        }
        for (String raw : refs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String ref = raw.trim();
            int colon = ref.indexOf(':');
            String kind = colon < 0 ? "custom" : ref.substring(0, colon).trim().toLowerCase();
            String idPart = colon < 0 ? ref : ref.substring(colon + 1);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", ref);
            item.put("kind", kind);
            switch (kind) {
                case "tool" -> fillDescriptive(item, "SELECT name, description FROM tool_registry WHERE id = ?",
                        numeric(idPart));
                case "skill" -> fillDescriptive(item, "SELECT name, description FROM skill_repo WHERE id = ?",
                        numeric(idPart.contains("@") ? idPart.substring(0, idPart.indexOf('@')) : idPart));
                case "mcp" -> fillDescriptive(item, "SELECT name, NULL::text AS description FROM mcp_config WHERE id = ?",
                        numeric(idPart));
                case "connector" -> fillDescriptive(item,
                        "SELECT name, platform AS description FROM connector_config WHERE id = ?", numeric(idPart));
                case "artifact" -> fillDescriptive(item,
                        "SELECT name, kind AS description FROM project_artifact WHERE id = ?", numeric(idPart));
                default -> {
                    item.put("name", ref);
                    item.put("description", null);
                }
            }
            out.add(item);
        }
        return out;
    }

    /** 按 id 查展示字段并回填；id 非数字或资产不存在时给出可读原因，不抛异常（上下文注入不该打断对话）。 */
    private void fillDescriptive(Map<String, Object> item, String sql, Long id) {
        if (id == null) {
            item.put("name", item.get("ref"));
            item.put("description", "引用 id 非数字，无法解析");
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(sql, id);
        if (rows.isEmpty()) {
            item.put("name", item.get("ref"));
            item.put("description", "资产不存在");
            return;
        }
        item.put("name", rows.get(0).get("name"));
        item.put("description", rows.get(0).get("description"));
    }

    private Long numeric(String s) {
        try {
            String v = s == null ? "" : s.trim();
            return v.isEmpty() ? null : Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}