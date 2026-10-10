package io.xuanji.server.report;

import io.xuanji.server.auth.DataScopeFilter;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.repository.ReportRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 报表（主文档 1.5 工作报表场景）：可创建的定时任务实体——
 * 命名 + 来源 workflow（版本钉住，必须已发布）+ 定时执行计划（SCHEDULE cron）。
 *
 * <p>数据口径：报表展示外部业务系统真实业务数据（经数据源连接器查询），
 * 非平台自身数据。每次执行 = 一次 run（trigger_type=SCHEDULE）→ 看板快照；
 * 看板渲染节点（board.render）实装前，看板快照区为诚实空态，执行记录
 * 复用 wf_run（按 definition_id 筛选）。调度由 {@link ReportScheduler} 承担：
 * 创建即注册 cron 任务、删除即注销。</p>
 */
@Service
public class ReportService {

    @Autowired
    private ReportRepository repo;

    /** 调度执行器：创建即注册 cron 任务，删除即注销 */
    @Autowired
    private ReportScheduler scheduler;

    /* ---------- 查询（数据权限过滤） ---------- */

    public List<Map<String, Object>> list(LoginUser user) {
        var c = DataScopeFilter.of(user, "org_id", "created_by");
        return repo.findByScope(c.sql(), c.args().toArray());
    }

    /* ---------- 创建 ---------- */

    @Transactional
    public Map<String, Object> create(LoginUser user, Map<String, Object> body) {
        String name = body.get("name") == null ? null : body.get("name").toString().trim();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        long definitionId = requireLong(body, "definition_id");
        String cronExpr = body.get("cron_expr") == null ? null : body.get("cron_expr").toString().trim();
        if (cronExpr == null || cronExpr.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cron_expr is required");
        }
        Integer pinned = validateWorkflow(definitionId);

        String id = "rp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        repo.insert(id, name, definitionId, pinned, cronExpr, true, user.orgId(), user.username());
        // 创建即纳入调度（cron 非法时调度器记日志跳过，报表定义仍生效）
        scheduler.register(Map.of("id", id, "definition_id", definitionId,
                "pinned_version", pinned, "cron_expr", cronExpr));
        return Map.of("id", id, "pinned_version", pinned);
    }

    @Transactional
    public void delete(LoginUser user, String id) {
        get(user, id);
        repo.deleteSnapshots(id);
        repo.delete(id);
        scheduler.cancel(id);
    }

    /* ---------- 更新 / 启停（调度同步：enabled=false 注销，true 注册） ---------- */

    @Transactional
    public Map<String, Object> update(LoginUser user, String id, Map<String, Object> body) {
        Map<String, Object> row = get(user, id);
        String name = body.get("name") == null ? null : body.get("name").toString().trim();
        String cronExpr = body.get("cron_expr") == null ? null : body.get("cron_expr").toString().trim();
        Boolean enabled = body.get("enabled") == null ? null : Boolean.parseBoolean(body.get("enabled").toString());
        if (name == null && cronExpr == null && enabled == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nothing to update (name/cron_expr/enabled)");
        }
        if (name != null) {
            if (name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
            }
            repo.updateName(id, name, Timestamp.from(Instant.now()));
        }
        if (cronExpr != null) {
            if (cronExpr.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cron_expr is required");
            }
            repo.updateCron(id, cronExpr, Timestamp.from(Instant.now()));
        }
        if (enabled != null) {
            repo.updateEnabled(id, enabled, Timestamp.from(Instant.now()));
        }
        // 调度同步：以最终状态重建注册（cron 变更/启停统一走 re-register）
        boolean active = enabled == null ? Boolean.TRUE.equals(row.get("enabled")) : enabled;
        if (active) {
            scheduler.register(repo.findLatestForSchedule(id));
        } else {
            scheduler.cancel(id);
        }
        return Map.of("id", id, "enabled", active);
    }

    /* ---------- 看板快照（最新 + 历史） ---------- */

    /** 快照列表（最新在前，上限 50）：报表详情默认展示第一条，历史可回看审计 */
    public List<Map<String, Object>> snapshots(LoginUser user, String id) {
        get(user, id);
        return repo.findSnapshots(id);
    }

    /** 立即执行一次（异步触发 SCHEDULE run + 固化看板快照）：让用户建报表后无需干等 cron 即可验证真实数据 */
    public Map<String, Object> runNow(LoginUser user, String id) {
        Map<String, Object> row = get(user, id);
        long definitionId = ((Number) row.get("definition_id")).longValue();
        int version = ((Number) row.get("pinned_version")).intValue();
        scheduler.runNow(id, definitionId, version);
        return Map.of("id", id, "triggered", true);
    }

    /* ---------- 内部 ---------- */

    private Map<String, Object> get(LoginUser user, String id) {
        Map<String, Object> row = repo.findById(id)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "report not found"));
        requireOwner(user, row);
        return row;
    }

    /** 绑定校验：workflow 存在且已发布，返回钉住版本（与 WorkspaceService.validateBinding 同语义） */
    private Integer validateWorkflow(long definitionId) {
        var rows = repo.findWorkflowForValidate(definitionId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源 workflow 不存在: " + definitionId);
        }
        String status = (String) rows.get(0).get("status");
        Integer ver = (Integer) rows.get(0).get("current_version");
        if (!"PUBLISHED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "来源 workflow 未发布（status=" + status + "）");
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该报表");
        }
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

    /** 时间戳占位（updated_at 统一由调用方写入；当前无 update 端点，预留） */
    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
