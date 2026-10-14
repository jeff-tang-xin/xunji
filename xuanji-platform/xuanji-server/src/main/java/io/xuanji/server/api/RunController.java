package io.xuanji.server.api;

import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.engine.RunService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** run 触发与查询（工作区执行入口的 P0 形态；SSE 事件流随工作区阶段落地） */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    @Autowired
    private RunService runService;

    @Autowired
    private io.xuanji.server.engine.RunEventBus runEventBus;

    public record CreateRunRequest(long definitionId, Map<String, Object> input, String triggerType) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRunRequest req,
                                      @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        // 数据权限：仅可触发自己可见的 definition
        runService.requireDefinitionVisible(req.definitionId(), user);
        String trigger = req.triggerType() == null ? "MANUAL" : req.triggerType();
        return runService.createRun(req.definitionId(), req.input(), trigger);
    }

    /** run 列表（运行监控）：?definitionId=&status=&limit=50，按开始时间倒序；数据权限经 definition 归属过滤 */
    @GetMapping
    public Object list(@org.springframework.web.bind.annotation.RequestParam(required = false) Long definitionId,
                       @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
                       @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit,
                       @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        return runService.listRuns(definitionId, status, limit, user);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String runId,
                                                   @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        try {
            runService.requireRunVisible(runId, user);
            return ResponseEntity.ok(runService.getRun(runId));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** run 事件流（SSE）：挂起后订阅，决策恢复/完成实时推送（run.suspended / run.resumed / run.finished） */
    @GetMapping(value = "/{runId}/events", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(
            @PathVariable String runId,
            @RequestAttribute(LoginUser.ATTR) LoginUser user) {
        runService.requireRunVisible(runId, user);
        return runEventBus.subscribe(runId);
    }
}
