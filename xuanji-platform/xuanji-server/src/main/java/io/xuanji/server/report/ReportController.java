package io.xuanji.server.report;

import io.xuanji.server.auth.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 报表端点（主文档 1.5 工作报表场景）：管理台 token 鉴权。
 * GET /api/reports 列表（数据权限过滤）｜ POST 创建（校验 workflow 已发布并钉版本）
 * ｜ PUT /{id} 更新（name/cron_expr/enabled，启停同步调度注册）｜ DELETE /{id}
 * ｜ GET /{id}/snapshots 看板快照（最新在前）。执行记录复用 GET /api/runs?definitionId=。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    @Autowired
    private ReportService reports;

    @GetMapping
    public Object list(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return reports.list(user);
    }

    @PostMapping
    public Object create(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                         @RequestBody Map<String, Object> body) {
        return reports.create(user, body);
    }

    @PutMapping("/{id}")
    public Object update(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                         @PathVariable String id,
                         @RequestBody Map<String, Object> body) {
        return reports.update(user, id, body);
    }

    @GetMapping("/{id}/snapshots")
    public List<Map<String, Object>> snapshots(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                                               @PathVariable String id) {
        return reports.snapshots(user, id);
    }

    @PostMapping("/{id}/run")
    public Object runNow(@RequestAttribute(LoginUser.ATTR) LoginUser user,
                         @PathVariable String id) {
        return reports.runNow(user, id);
    }

    @DeleteMapping("/{id}")
    public Object delete(@RequestAttribute(LoginUser.ATTR) LoginUser user, @PathVariable String id) {
        reports.delete(user, id);
        return Map.of("deleted", id);
    }
}
