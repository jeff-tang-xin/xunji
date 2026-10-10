package io.xuanji.server.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.core.api.NodeKind;
import io.xuanji.core.definition.NodeDef;
import io.xuanji.core.engine.CanvasJsonParser;
import io.xuanji.core.engine.LiteFlowRuleCompiler;
import io.xuanji.core.definition.WorkflowDefinition;
import io.xuanji.server.auth.DataScopeFilter;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.repository.DefinitionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定义生命周期：草稿保存 → 发布（解析 + 编译校验 + 版本固化）。
 *
 * <p>发布流水线 P0 简化版（docs/detail/03 §3 七步的 ①②③④⑤）：
 * 语法/结构校验（CanvasJsonParser）→ 图校验与编译（LiteFlowRuleCompiler）→
 * 版本落库 + 指针更新。热刷（⑥）随动态组件注册阶段落地——本版 EL 走
 * execute2RespWithEL 动态执行，天然「新 run 用新版本」。</p>
 */
@Service
public class DefinitionService {

    @Autowired
    private DefinitionRepository repo;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NodeDispatchComponent nodeDispatch;

    /** AGENT 节点执行器（发布期引用校验用；ObjectProvider 防装配顺序耦合） */
    @Autowired
    private ObjectProvider<AgentNodeExecutor> agentExecutorProvider;

    private final LiteFlowRuleCompiler compiler = new LiteFlowRuleCompiler("node_dispatch");

    /** 保存草稿（新建）：归属 = 创建者组织 + 创建者（Q13 数据权限过滤基线） */
    public long create(String name, String description, String canvasJson, LoginUser user) {
        // 先解析一遍：草稿也必须是合法定义（编辑器保存即校验）
        WorkflowDefinition def = CanvasJsonParser.parse(canvasJson);
        if (!def.name().equals(name)) {
            // 以 meta.name 为准，保持一致
            name = def.name();
        }
        return repo.insertDraft(name, description, canvasJson, user.orgId(), user.username());
    }

    /** 更新草稿（管理员或创建者本人） */
    public void update(long id, String name, String description, String canvasJson, LoginUser user) {
        requireOwner(id, user);
        WorkflowDefinition def = CanvasJsonParser.parse(canvasJson);
        if (!def.name().equals(name)) {
            name = def.name();
        }
        repo.updateDraft(id, name, description, canvasJson, Timestamp.from(Instant.now()));
    }

    /** 删除草稿（仅 DRAFT；管理员或创建者本人） */
    public void delete(long id, LoginUser user) {
        requireOwner(id, user);
        String status = repo.findStatus(id);
        if (!"DRAFT".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅 DRAFT 定义可删除（先下线再删）");
        }
        repo.delete(id);
    }

    private void requireOwner(long id, LoginUser user) {
        List<Map<String, Object>> rows = repo.findCreatedBys(id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "wf_definition #" + id + " 不存在");
        }
        if (user.isPlatformAdmin()) {
            return;
        }
        Object createdBy = rows.get(0).get("created_by");
        if (createdBy == null || !createdBy.equals(user.username())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅资产创建者或平台管理员可操作");
        }
    }

    /** 发布：解析 → 编译校验 → 版本固化 + 指针更新（同事务语义，P0 顺序执行） */
    public Map<String, Object> publish(long definitionId) {
        Map<String, Object> def = repo.findForPublish(definitionId);
        String canvasJson = (String) def.get("canvas_json");

        WorkflowDefinition parsed = CanvasJsonParser.parse(canvasJson);
        String el;
        try {
            el = compiler.compile(parsed);      // 图校验在此，失败抛 IllegalArgumentException
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "流程结构校验失败: " + e.getMessage());
        }
        validateRefs(parsed);                   // 引用校验：refId 指向的组件/Agent 必须存在且可用

        Integer current = def.get("current_version") == null ? 0 : ((Number) def.get("current_version")).intValue();
        int next = current + 1;
        repo.insertVersion(definitionId, next, canvasJson, el);
        repo.markPublished(definitionId, next, Timestamp.from(Instant.now()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("definitionId", definitionId);
        result.put("version", next);
        result.put("ruleContent", el);
        return result;
    }

    /**
     * 发布期引用校验：把「refId 指向不存在组件/Agent」的错误从 run 期提前到发布期——
     * AGENT 节点校验 agent_config 该版本存在且 PUBLISHED；其余节点校验组件注册表含该 refId。
     */
    private void validateRefs(WorkflowDefinition parsed) {
        for (NodeDef node : parsed.nodes()) {
            if (node.isStart() || node.isEnd()) {
                continue; // 平台内置
            }
            if (node.kind() == NodeKind.AGENT) {
                agentExecutorProvider.getObject().validateRef(node.refId());
            } else if (!nodeDispatch.hasInstance(node.refId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "节点「" + node.name() + "」(" + node.instanceId() + ") 引用的组件不存在或未注册: " + node.refId());
            }
        }
    }

    /** 定义详情（含版本历史） */
    public Map<String, Object> get(long definitionId) {
        Map<String, Object> def = repo.findDetail(definitionId);
        def.put("versions", repo.findVersions(definitionId));
        return def;
    }

    public List<Map<String, Object>> list(LoginUser user) {
        DataScopeFilter.Clause c = DataScopeFilter.of(user, "org_id", "created_by");
        return repo.findByScope(c.sql(), c.args().toArray());
    }
}
