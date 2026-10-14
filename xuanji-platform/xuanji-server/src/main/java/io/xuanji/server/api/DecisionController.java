package io.xuanji.server.api;

import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.engine.RunService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 决策门决策 API（docs/detail/03 §5；审批中心数据源）。
 *
 * <p>POST /api/decisions：决策提交（APPROVE → run 从 gate 节点续跑；
 * REJECT → run 保持 SUSPENDED，批注入审计，主循环=批注→再决策）。
 * TOOL_APPROVAL（Agent 工具审批回调）随权限系统实装扩展。</p>
 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionController {

    @Autowired
    private RunService runService;

    public record DecisionRequest(String runId, String nodeId, String decision, String comment) {
    }

    /** 提交决策：{runId, nodeId, decision: APPROVE|REJECT, comment} */
    @PostMapping
    public Map<String, Object> decide(@RequestBody DecisionRequest req,
                                      @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return runService.resume(req.runId(), req.nodeId(), req.decision(), req.comment(), user.username());
    }

    /** 审批中心待办：SUSPENDED run 列表（数据权限经 workflow 定义归属过滤） */
    @GetMapping("/pending")
    public List<Map<String, Object>> pending(@RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return runService.pendingDecisions(user);
    }

    /** 决策历史：?runId=；数据权限经 run 归属校验 */
    @GetMapping
    public List<Map<String, Object>> history(@RequestParam String runId,
                                             @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        runService.requireRunVisible(runId, user);
        return runService.decisionHistory(runId);
    }
}
