package io.xuanji.server.workspace;

import io.xuanji.server.auth.DataScopeFilter;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.engine.RunService;
import io.xuanji.server.repository.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作区（主文档 4.6 / Q11 口径）：执行的交互容器与授权边界。
 *
 * <p>创建只填项目名，root_path = 配置根目录/name 动态生成；绑定 agent 或 workflow
 * 二选一并钉住已发布版本（引用钉住语义，同 App 绑定）；Agent 决策授权
 * （authorization_json）在绑定时一次性配置。从工作区发起执行：WORKFLOW 绑定
 * 按钉住版本触发 run（triggerType=WORKSPACE）；AGENT 绑定的对话式执行依赖
 * chat-messages SSE（未实装 → 501 语义化）。</p>
 */
@Service
public class WorkspaceService {

    @Autowired
    private WorkspaceRepository repo;

    @Autowired
    private RunService runService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${xuanji.workspace.root:./workspaces}")
    private String workspaceRoot;

    /* ---------- 查询（数据权限过滤） ---------- */

    public List<Map<String, Object>> list(LoginUser user) {
        var c = DataScopeFilter.of(user, "org_id", "created_by");
        return repo.findByScope(c.sql(), c.args().toArray());
    }

    public Map<String, Object> get(LoginUser user, String id) {
        Map<String, Object> ws = repo.findById(id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace not found"));
        requireOwner(user, ws);
        return ws;
    }

    /* ---------- 创建 ---------- */

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(LoginUser user, Map<String, Object> body) {
        String name = body.get("name") == null ? null : body.get("name").toString().trim();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        validateName(name);
        String type = str(body, "binding_type");
        if (!"WORKFLOW".equals(type) && !"AGENT".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "binding_type must be WORKFLOW or AGENT");
        }
        long assetId = requireLong(body, "bound_asset_id");
        Integer pinned = validateBinding(type, assetId);

        String id = "ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String rootPath = (workspaceRoot.endsWith("/") || workspaceRoot.endsWith("\\")
                ? workspaceRoot : workspaceRoot + "/") + name;

        String authJson = body.get("authorization_json") != null ? toJson(body.get("authorization_json")) : null;
        repo.insert(id, name, rootPath, type, assetId, pinned, authJson, user.orgId(), user.username());
        return Map.of("id", id, "root_path", rootPath, "bound_asset_version", pinned);
    }

    /**
     * 更新工作区：名称（工作目录随名称重算）与绑定资产（重新校验并重新钉住版本）。
     *
     * <p>绑定字段可省略（省略即保持原绑定不变）。归属校验沿用 {@link #get}：
     * 无权访问或不存在均 404。历史 run 不受改绑影响——每条 run 记录自身钉住的版本。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> update(LoginUser user, String id, Map<String, Object> body) {
        Map<String, Object> current = get(user, id);
        String name = str(body, "name");
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        name = name.trim();
        validateName(name);
        String type = str(body, "binding_type");
        long assetId;
        Integer pinned;
        if (type == null && body.get("bound_asset_id") == null) {
            // 未提交绑定字段：保持原绑定
            type = (String) current.get("binding_type");
            assetId = ((Number) current.get("bound_asset_id")).longValue();
            Object pv = current.get("bound_asset_version");
            pinned = pv instanceof Number n ? n.intValue() : null;
        } else {
            if (!"WORKFLOW".equals(type) && !"AGENT".equals(type)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "binding_type must be WORKFLOW or AGENT");
            }
            assetId = requireLong(body, "bound_asset_id");
            pinned = validateBinding(type, assetId);
        }
        String rootPath = (workspaceRoot.endsWith("/") || workspaceRoot.endsWith("\\")
                ? workspaceRoot : workspaceRoot + "/") + name;
        repo.update(id, name, rootPath, type, assetId, pinned);
        return get(user, id);
    }

    @Transactional
    public void delete(LoginUser user, String id) {
        get(user, id);
        repo.delete(id);
    }

    /* ---------- 从工作区发起执行 ---------- */

    /**
     * WORKFLOW 绑定：按钉住版本触发 run（triggerType=WORKSPACE，审计可区分来源）。
     * AGENT 绑定：对话式执行走 chat-messages SSE（POST /api/workspaces/{id}/chat），
     * 本端点仅承接 WORKFLOW 绑定——AGENT 绑定 400 语义化提示。
     */
    public Map<String, Object> createRun(LoginUser user, String wsId, Map<String, Object> input) {
        Map<String, Object> ws = get(user, wsId);
        String type = (String) ws.get("binding_type");
        long assetId = ((Number) ws.get("bound_asset_id")).longValue();
        int version = ((Number) ws.get("bound_asset_version")).intValue();
        if ("AGENT".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "AGENT 绑定的工作区请使用对话接口 POST /api/workspaces/" + wsId + "/chat（SSE 流式）");
        }
        // 异步触发：立即返回 runId（RUNNING），前端订阅 /api/runs/{id}/events 实时渲染
        // （AGENT 节点 LLM 耗时 30s+，同步等待会让工作区对话长时间停在「已开始执行」）
        return runService.createRunAsync(assetId, version, input == null ? Map.of() : input, "WORKSPACE");
    }

    /* ---------- 内部 ---------- */

    /** 绑定校验：资产存在且已发布，返回钉住版本（与 AppService.validateBinding 同语义） */
    private Integer validateBinding(String type, long assetId) {
        if ("WORKFLOW".equals(type)) {
            var rows = repo.findWorkflowBinding(assetId);
            if (rows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定的 workflow 不存在: " + assetId);
            }
            String status = (String) rows.get(0).get("status");
            Integer ver = (Integer) rows.get(0).get("current_version");
            if (!"PUBLISHED".equals(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定的 workflow 未发布（status=" + status + "）");
            }
            return ver;
        }
        var rows = repo.findAgentBinding(assetId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定的 agent 不存在: " + assetId);
        }
        String status = (String) rows.get(0).get("status");
        Integer ver = (Integer) rows.get(0).get("version");
        if (!"PUBLISHED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定的 agent 未发布（status=" + status + "）");
        }
        return ver;
    }

    private void requireOwner(LoginUser user, Map<String, Object> row) {
        if (user.isPlatformAdmin() || "ALL".equals(user.dataScope())) {
            return;
        }
        String createdBy = (String) row.get("created_by");
        String scope = user.dataScope() == null ? "ONLY_CREATE" : user.dataScope();
        boolean orgVisible = ("ORG".equals(scope) || "UNDER_ORG".equals(scope))
                && user.orgId() != null && user.orgId().equals(row.get("org_id"));
        if (!orgVisible && !user.username().equals(createdBy)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该工作区");
        }
    }

    /**
     * 工作区名路径安全校验：root_path = 配置根目录/name 动态生成，name 含路径分隔符或
     * 相对路径片段（../、./、空、.、..）会逃逸出配置根目录（路径穿越）。仅允许
     * 字母数字、中文、下划线、连字符、空格与点（不含路径分隔符）。
     */
    private void validateName(String name) {
        if (name.equals(".") || name.equals("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作区名不能为 . 或 ..");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作区名不能包含路径分隔符");
        }
        if (!name.matches("[\\p{L}\\p{N}_\\- .]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作区名含非法字符（仅允许字母/数字/中文/下划线/连字符/空格/点）");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authorization_json 格式错误");
        }
    }

    private String str(Map<String, Object> body, String key) {
        return body.get(key) == null ? null : body.get(key).toString();
    }

    private long requireLong(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required");
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a number");
        }
    }
}
