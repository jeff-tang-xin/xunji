package io.xuanji.server.project.orchestrator;

import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.server.engine.AgentNodeExecutor;
import io.xuanji.server.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Map;

/**
 * 任务型小流程执行器：单 agent 后台执行，产出登记为交付物。
 *
 * <p>执行模型（2026-10-17 同步化）：主控调 execute_step → {@link #runSync} 在主控
 * 工具调用线程内阻塞执行到完成（无超时，thinking 保留），产出经返回值直接回到主控手里；
 * 失败原因同样直接上抛给主控——当场决策重试/换路/跳过，不再依赖唤醒链路。
 * {@link #submit} 仅人工重试端点使用（后台跑 + 完成唤醒主控）。</p>
 *
 * <p>人为终止：Future 注册进 {@link StepInterruption}，终止端点 cancel 后落 SKIPPED
 * 并抛 {@link DiscussionEngine.UserInterrupted}，主控直接进入下一步。</p>
 */
@Component
public class TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final AgentInvoker invoker;
    private final AgentNodeExecutor agentNodes;
    private final StageStepRepository stepRepo;
    private final ProjectRepository projectRepo;
    private final PeerConsultService peerConsult;
    private final RequirementRepository requirementRepo;
    private final StageDocStore docStore;
    private final StageOrchestrator orchestrator;
    private final ProjectEventBus projectEvents;
    private final StageChatEventBus chatEvents;
    private final OpsConnectionManager ops;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 自主执行纪律（2026-10-19「每个智能体都在敷衍」）：追加在每个执行任务 prompt 末尾——
     * 先探索再动手、产出必须真实落盘、交付前自检、产出会被机器验收。 */
    private static final String AUTONOMY_DISCIPLINE = """

            【自主执行纪律——违反=任务失败】
            1. 动手前先探索：先 list_directory 看项目结构，读相关文件了解现状与既有约定，不要凭空猜接口和格式。
            2. 一切产出必须真实落盘：写文件用 write_text_file，写完读回确认内容完整。没调工具=没做——
               禁止声称做了没做的事（「已创建」「已完成」必须以真实工具调用为前提）。
            3. 交付前自检：对照任务验收断言逐条自查；能跑测试/构建就跑（execute_shell_command），失败先修再交。
            4. 你的产出会被平台机器验收（产物存在性与内容核对），验收不过=任务失败打回——敷衍等于白干。
            """;

    /** 双池防自等死锁：调度池跑 run（含限时等待），工作池跑阻塞的 invoke。
     * 工作池 4 线程：单线程时一个 8 分钟任务就把全平台任务型步骤串成队，多项目并发必排队。 */
    private final ExecutorService dispatchPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stage-task-dispatch");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService workPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "stage-task-worker");
        t.setDaemon(true);
        return t;
    });

    public TaskRunner(AgentInvoker invoker, AgentNodeExecutor agentNodes,
                      StageStepRepository stepRepo, ProjectRepository projectRepo,
                      PeerConsultService peerConsult, RequirementRepository requirementRepo,
                      StageDocStore docStore, ProjectEventBus projectEvents,
                      StageChatEventBus chatEvents, OpsConnectionManager ops,
                      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                      @org.springframework.context.annotation.Lazy StageOrchestrator orchestrator) {
        this.invoker = invoker;
        this.agentNodes = agentNodes;
        this.stepRepo = stepRepo;
        this.projectRepo = projectRepo;
        this.peerConsult = peerConsult;
        this.requirementRepo = requirementRepo;
        this.docStore = docStore;
        this.projectEvents = projectEvents;
        this.chatEvents = chatEvents;
        this.ops = ops;
        this.objectMapper = objectMapper;
        this.orchestrator = orchestrator;
    }

    /**
     * 提交后台执行（人工重试端点专用）：立即返回，结果落黑板 + 唤醒主控。
     * 主控的 execute_step 已改走 {@link #runSync} 同步链路（产出直接回主控手里）。
     */
    public void submit(StageStep step, TopicBrief brief) {
        java.util.concurrent.CompletableFuture<Void> f = java.util.concurrent.CompletableFuture
                .runAsync(() -> {
                    try {
                        runSync(step, brief);
                        orchestrator.wakeIfSettled(step.workspaceId(), step.stage(), step.iteration(),
                                "任务 #" + step.seq() + "「" + step.name() + "」已完成（人工重试），产出已留档。请消化后继续推进。");
                    } catch (DiscussionEngine.UserInterrupted ui) {
                        orchestrator.wakeIfSettled(step.workspaceId(), step.stage(), step.iteration(),
                                "任务 #" + step.seq() + "「" + step.name() + "」已被用户终止。请评估后续。");
                    } catch (Exception e) {
                        orchestrator.wakeIfSettled(step.workspaceId(), step.stage(), step.iteration(),
                                "任务 #" + step.seq() + "「" + step.name() + "」重试失败（" + TaskRunner.rootMessage(e) + "）。请评估后调整。");
                    }
                }, dispatchPool);
        // 项目级注册：删除项目时 terminateAll 一键终止（含人工重试后台任务）
        TaskRegistry.register(step.workspaceId(), f);
        f.whenComplete((v, e) -> TaskRegistry.unregister(step.workspaceId(), f));
    }

    /**
     * 同步执行（主控 execute_step 链路）：阻塞到任务完成，产出直接返回主控。
     *
     * <p><b>无超时</b>（2026-10-17 定稿）：模型想多久想多久（thinking 保留），做完为止——
     * TimeoutException 失败形态废除。Future 仅为人为终止留 cancel 通道
     * （{@link StepInterruption#terminate}），终止后落 SKIPPED 并抛「用户已终止」，
     * 主控直接进入下一步。</p>
     *
     * @return 产出全文
     * @throws IllegalStateException 任务失败（message 为可读原因，主控当场决策重试/换路/跳过）
     * @throws DiscussionEngine.UserInterrupted 用户终止
     */
    public String runSync(StageStep step, TopicBrief brief) {
        stepRepo.updateStatus(step.withStatus(StepStatus.EXECUTING));
        log.info("任务执行开始: ws={} {}#{} seq={} agent={}",
                step.workspaceId(), step.stage(), step.iteration(), step.seq(), step.agent());
        // 执行过程收集（每步执行都可视）：agent 的思考/回复增量与工具调用进轨迹，
        // 节流落 output_full（轮询兜底）+ 广播 step.process（在线前端实时看）；
        // 完成时 output_full 被纯产出覆盖，轨迹另落 CHATLOG 文件留档（回放通道）
        StringBuilder trace = new StringBuilder();
        final long[] lastFlush = {System.currentTimeMillis()};
        final StringBuilder pending = new StringBuilder();
        java.util.function.Consumer<String> flush = (force) -> {
            String buf = pending.toString();
            if (buf.isEmpty()) {
                return;
            }
            if (!"now".equals(force) && System.currentTimeMillis() - lastFlush[0] < 2000) {
                return; // 节流：delta 每 token 一帧，2s 落一次库/推一次
            }
            pending.setLength(0);
            lastFlush[0] = System.currentTimeMillis();
            trace.append(buf);
            try {
                stepRepo.appendSpeech(step.id(), buf);
            } catch (Exception ignore) {
                // 落库失败不阻断执行（在线实时不受影响）
            }
            try {
                chatEvents.publish(StageChatEventBus.key(step.workspaceId(), step.stage(), step.iteration()),
                        "step.process", java.util.Map.of(
                                "seq", step.seq(), "stage", step.stage(),
                                "stepType", "TASK", "name", step.name(),
                                "agent", step.agent(), "delta", buf));
            } catch (Exception ignore) {
                // 广播失败不阻断执行
            }
        };
        java.util.function.Consumer<io.xuanji.core.runtime.AgentStreamEvent> onEvent = evt -> {
            String type = evt.type();
            if ("agent.delta".equals(type) && evt.delta() != null && !evt.delta().isEmpty()) {
                pending.append(evt.delta());
                flush.accept("throttle");
            } else if ("tool.call".equals(type)) {
                String tool = evt.meta() == null ? "" : String.valueOf(evt.meta().getOrDefault("tool", evt.meta().get("name")));
                if (!tool.isBlank()) {
                    pending.append("\n🔧 调用 ").append(tool).append(" …\n");
                    flush.accept("now");
                }
            } else if ("tool.result".equals(type)) {
                // 工具执行结果帧：轨迹里只有「调用 …」没有结果的话，回放时看不出工具成败
                String tool = evt.meta() == null ? "" : String.valueOf(evt.meta().getOrDefault("tool", ""));
                if (!tool.isBlank()) {
                    boolean ok = !"ERROR".equals(String.valueOf(evt.meta() == null ? "" : evt.meta().getOrDefault("state", "")));
                    pending.append(ok ? "✅ " : "❌ ").append(tool).append(ok ? " 完成\n" : " 失败\n");
                    flush.accept("now");
                }
            }
        };
        try {
            // 挂 ask_peer：执行中遇到需求/架构问题可 @对应方解答（三方结构，问答留档）——
            // 讨论式开发核心：执行 agent 不清楚就问，禁止靠猜
            // 四参重载传 workspaceId：启用内置文件工具（agent_config.builtin_tools_json 白名单，
            // developer=fs.read+fs.write）——任务执行是「写盘交付」主路径，不启用的话 agent 想写
            // 文件没工具，只能嘴上说「已创建」实际目录是空的（2026-10-19 用户反馈「AI 总是报没有工具」实锤）。
            // 白名单现无 shell（DB 实况 5 agent 均 fs.*），无 ASK 挂死风险；若未来配 shell 需先做无人值守批准通道
            AgentSpec base = agentNodes.buildSpecByAgentName(step.agent(), null, null, step.workspaceId());
            // 挂运维工具三件套（ops_remote_shell/db_query/db_dml）：地址与凭证不暴露给 AI，
            // 工具参数只收引用 ID（server-1/db-1），连接由 OpsConnectionManager 平台侧代理——
            // 用不用由 AI 按任务决定（部署/排障任务才会调）
            AgentSpec spec = withTools(base, java.util.stream.Stream.concat(
                    List.of(peerConsult.askPeerDef(step, brief)).stream(),
                    OpsTools.defsFor(step.workspaceId(), ops).stream()).toList());
            String prompt = brief.render(step.name(), step.instruction())
                    + AUTONOMY_DISCIPLINE;
            Future<String> future = workPool.submit(() -> invoker.invoke(spec, prompt, onEvent));
            StepInterruption.register(step.id(), future);
            String output;
            try {
                output = future.get(); // 无超时：做完为止
            } catch (java.util.concurrent.CancellationException ce) {
                stepRepo.updateStatus(step.withStatus(StepStatus.SKIPPED).withSkip("用户终止"));
                projectEvents.publish(step.workspaceId(), "step.updated", Map.of(
                        "stage", step.stage(), "seq", step.seq(), "stepType", "TASK",
                        "status", "SKIPPED", "name", step.name()));
                throw new DiscussionEngine.UserInterrupted();
            } catch (java.util.concurrent.ExecutionException ee) {
                throw new IllegalStateException(TaskRunner.rootMessage(ee), ee);
            } finally {
                StepInterruption.unregister(step.id());
            }
            // nodeId 带阶段与迭代：跨阶段 seq 会重合（REQUIREMENT seq4 与 DEVELOP seq4），
            // 裸 "step-{seq}" 会让追溯关联与 file_path 回填串阶段（trace 端点按全键匹配）
            String stepNodeId = "step-" + step.stage() + "-" + step.iteration() + "-" + step.seq();
            // 11 参重载带 stage：交付物必须标阶段归属，否则生命周期「交付物 N 份」按 stage 统计时
            // 交付物全被漏掉、DOC 被误当交付物计数（测试003 实锤：交付物 tab 5 条 vs 生命周期报 24）
            projectRepo.insertArtifact(step.workspaceId(), stepNodeId,
                    "DELIVERABLE", step.name(), "TEXT", null, output, 1, "ACTIVE", step.stage(), step.agent());
            String summary = output.length() > 300 ? output.substring(0, 300) + "…" : output;
            // 机器验收（质量闸，2026-10-19「没有人为代码/质量负责」）：acceptance 里 type=artifact
            // 的断言由平台逐条核对（产物存在且非空）——AI 自述「已创建」而文件不存在 = 验收失败，
            // 任务落 FAILED（产出照留档），主控拿到失败明细自主重试。全过则 verified_by=tool
            // （机器判定），需求闭环只认 tool/human，self 自述不再算数。
            List<String> failedChecks = verifyArtifactAssertions(step);
            if (!failedChecks.isEmpty()) {
                String detail = "机器验收未通过（" + failedChecks.size() + " 项）："
                        + String.join("；", failedChecks);
                stepRepo.updateStatus(step.withError(detail));
                log.warn("任务机器验收失败: ws={} seq={} -> {}", step.workspaceId(), step.seq(), detail);
                projectEvents.publish(step.workspaceId(), "step.updated", Map.of(
                        "stage", step.stage(), "seq", step.seq(), "stepType", "TASK",
                        "status", "FAILED", "name", step.name()));
                throw new IllegalStateException(detail + "——产出已留档但产物未落盘/不合规，"
                        + "重试时在 instruction 里带上本验收明细");
            }
            // 验收责任链（P0）：human 验收步执行完不直接 DONE——进 WAITING_ACCEPT 等用户
            // 通过（验收端点）才 DONE（verified_by=human）；打回=FAILED 带原因可重试。
            // tool 验收步走原机器验收通道直接 DONE。
            if ("human".equals(step.acceptanceOwner())) {
                stepRepo.updateStatus(step.withOutput(summary, output, null)
                        .withStatus(StepStatus.WAITING_ACCEPT));
                log.info("任务执行完成待用户验收: ws={} seq={}", step.workspaceId(), step.seq());
                projectEvents.publish(step.workspaceId(), "step.updated", Map.of(
                        "stage", step.stage(), "seq", step.seq(), "stepType", "TASK",
                        "status", "WAITING_ACCEPT", "name", step.name()));
                // 同步链路不唤醒：主控就阻塞在本方法调用上，产出经返回值直接回到它手里
                return output + "\n\n[系统提示] 本步骤验收人=用户：产出已留档，等待用户在验收卡通过后才算 DONE；"
                        + "请继续推进与验收无关的后续步骤，不要重复等待。";
            }
            stepRepo.updateStatus(step.withOutput(summary, output, null)
                    .withVerifiedBy(hasArtifactAssertion(step) ? "tool" : "self"));
            // 执行轨迹留档（每步可视的回放通道）：output_full 已被纯产出覆盖，
            // 轨迹落 CHATLOG 文件——前端过程留档区可点击回看完整执行轨迹
            flush.accept("now");
            String trajectory = trace.toString();
            if (!trajectory.isBlank()) {
                docStore.writeFile(step.workspaceId(), step.stage(), step.iteration(), "CHATLOG",
                        step.seq(), step.name() + "-执行轨迹", trajectory);
            }
            // 文档双落：产出落项目目录（docs/{stage}/iter{n}/），相对路径回填 DELIVERABLE 索引
            // （不另插 DOC 索引——同一产出两条索引会让交付物 tab 重复）
            String docPath = docStore.writeFile(step.workspaceId(), step.stage(), step.iteration(),
                    "TASK", step.seq(), step.name(), output);
            if (docPath != null) {
                projectRepo.updateArtifactFilePath(step.workspaceId(), stepNodeId, docPath);
            }
            settleRequirements(step);
            log.info("任务执行完成: ws={} seq={} 产出 {} 字", step.workspaceId(), step.seq(), output.length());
            // 项目状态实时推送：步骤落终态，在线前端收到即刷新台账（离线靠拉全量兜底）
            projectEvents.publish(step.workspaceId(), "step.updated", Map.of(
                    "stage", step.stage(), "seq", step.seq(), "stepType", "TASK",
                    "status", "DONE", "name", step.name()));
            // 同步链路不唤醒：主控就阻塞在本方法调用上，产出经返回值直接回到它手里
            return output;
        } catch (DiscussionEngine.UserInterrupted ui) {
            // 已落 SKIPPED；终止前的轨迹同样落档（用户看到执行到哪一步被终止）
            flush.accept("now");
            if (!trace.isEmpty()) {
                docStore.writeFile(step.workspaceId(), step.stage(), step.iteration(), "CHATLOG",
                        step.seq(), step.name() + "-执行轨迹(终止)", trace + "\n\n⏹ 用户终止\n");
            }
            throw ui;
        } catch (Exception e) {
            String msg = rootMessage(e);
            stepRepo.updateStatus(step.withError(msg));
            // 失败也要把已产生的轨迹落档（排障需要）+ 广播终止
            flush.accept("now");
            String trajectory = trace.toString();
            if (!trajectory.isBlank()) {
                docStore.writeFile(step.workspaceId(), step.stage(), step.iteration(), "CHATLOG",
                        step.seq(), step.name() + "-执行轨迹(失败)", trajectory + "\n\n❌ 失败原因：" + msg + "\n");
            }
            log.warn("任务执行失败: ws={} seq={} -> {}", step.workspaceId(), step.seq(), msg);
            // 项目状态实时推送：失败也是终态
            projectEvents.publish(step.workspaceId(), "step.updated", Map.of(
                    "stage", step.stage(), "seq", step.seq(), "stepType", "TASK",
                    "status", "FAILED", "name", step.name()));
            // 失败原因上抛：主控当场拿到，立即决策（重试/换路/跳过），不停摆
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 机器验收：acceptance 断言里 type=artifact 的条目由平台逐条核对——
     * 产物（文件/目录）存在且非空才过。返回失败明细（空列表=全过或无可机器判断言）。
     *
     * <p>判定口径：target 相对项目根解析（normalize 后必须仍在根内，防越界）；
     * 文件 size&gt;0、目录含至少 1 个条目视为「存在且非空」。解析异常按无断言处理
     * （验收是闸不是雷，格式坏了不能把正常任务打失败——弱断言治理后续批次做标记）。</p>
     */
    private List<String> verifyArtifactAssertions(StageStep step) {
        List<Map<String, Object>> assertions = parseAcceptance(step.acceptanceJson());
        if (assertions.isEmpty()) {
            return List.of();
        }
        String root = agentNodes.workspaceRoot(step.workspaceId());
        if (root == null) {
            log.warn("机器验收跳过（工作区根缺失）: ws={} seq={}", step.workspaceId(), step.seq());
            return List.of();
        }
        java.nio.file.Path base = java.nio.file.Path.of(root).toAbsolutePath().normalize();
        List<String> failed = new java.util.ArrayList<>();
        for (Map<String, Object> a : assertions) {
            String type = String.valueOf(a.get("type"));
            // shell 断言（P0）：{"type":"shell","command":"...","expect_contains":"..."}——
            // 在项目根目录真跑命令核对输出（测试/构建/健康检查），输出含期望串=过。
            // 超时 120s；命令执行失败或输出不含期望=验收失败（明细进 error 供重试）
            if ("shell".equalsIgnoreCase(type)) {
                String command = String.valueOf(a.getOrDefault("command", "")).trim();
                String expect = String.valueOf(a.getOrDefault("expect_contains", "")).trim();
                if (command.isEmpty()) {
                    continue;
                }
                String check = runShellAssertion(base, command, expect);
                if (check != null) {
                    failed.add("shell[" + command + "]：" + check);
                }
                continue;
            }
            if (!"artifact".equalsIgnoreCase(type)) {
                continue;
            }
            String target = String.valueOf(a.getOrDefault("target", "")).trim();
            if (target.isEmpty()) {
                continue;
            }
            java.nio.file.Path p = base.resolve(target).normalize();
            if (!p.startsWith(base)) {
                failed.add(target + "（路径越界）");
                continue;
            }
            if (!java.nio.file.Files.exists(p)) {
                failed.add(target + "（产物不存在——声称创建但未落盘）");
            } else if (isPathEmpty(p)) {
                failed.add(target + "（产物为空）");
            }
        }
        return failed;
    }

    /** acceptance 里是否存在 artifact/shell 断言（决定 verified_by=tool 还是 self）。 */
    private boolean hasArtifactAssertion(StageStep step) {
        return parseAcceptance(step.acceptanceJson()).stream()
                .anyMatch(a -> ("artifact".equalsIgnoreCase(String.valueOf(a.get("type")))
                        && String.valueOf(a.getOrDefault("target", "")).trim().isEmpty() == false)
                        || ("shell".equalsIgnoreCase(String.valueOf(a.get("type")))
                        && String.valueOf(a.getOrDefault("command", "")).trim().isEmpty() == false));
    }

    /**
     * shell 断言执行：项目根目录跑命令，核对输出含期望串。
     *
     * @return null=通过；非 null=失败原因（命令失败/超时/输出不含期望）
     */
    private String runShellAssertion(java.nio.file.Path base, String command, String expect) {
        try {
            Process proc = new ProcessBuilder("sh", "-c", command)
                    .directory(base.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "执行超时（120s）";
            }
            String out = new String(proc.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (proc.exitValue() != 0) {
                String tail = out.length() > 300 ? out.substring(out.length() - 300) : out;
                return "退出码 " + proc.exitValue() + "——" + tail.replace('\n', ' ');
            }
            if (!expect.isEmpty() && !out.contains(expect)) {
                String tail = out.length() > 300 ? out.substring(out.length() - 300) : out;
                return "输出不含期望串「" + expect + "」——末尾输出：" + tail.replace('\n', ' ');
            }
            return null;
        } catch (Exception e) {
            return "执行异常：" + TaskRunner.rootMessage(e);
        }
    }

    /** acceptance_json（JSONB 数组 [{type,target,expect}]）→ List；解析失败按无断言处理。 */
    private List<Map<String, Object>> parseAcceptance(String acceptanceJson) {
        if (acceptanceJson == null || acceptanceJson.isBlank()) {
            return List.of();
        }
        try {
            List<?> raw = objectMapper.readValue(acceptanceJson, List.class);
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (Object o : raw) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    m.forEach((k, v) -> row.put(String.valueOf(k), v));
                    out.add(row);
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean isPathEmpty(java.nio.file.Path p) {
        try {
            if (java.nio.file.Files.isDirectory(p)) {
                try (var s = java.nio.file.Files.list(p)) {
                    return s.findFirst().isEmpty();
                }
            }
            return java.nio.file.Files.size(p) == 0;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 需求状态回写：步骤完成时，检查其声明的需求项——若该需求全部关联步骤均已产出
     * （无 PLANNED/EXECUTING/WAITING），置 DONE；首个关联步骤开工时置 IN_PROGRESS。
     * 回写失败只记日志（需求状态是看板信息，不阻塞任务完成）。
     */
    public void settleRequirements(StageStep doneStep) {
        try {
            for (String code : doneStep.requirementCodes()) {
                requirementRepo.findByCode(doneStep.workspaceId(), code).ifPresent(r -> {
                    if ("PROPOSED".equals(r.status()) || "CONFIRMED".equals(r.status())) {
                        requirementRepo.updateStatus(doneStep.workspaceId(), code, "IN_PROGRESS");
                    }
                    List<StageStep> linked = stepRepo.findByStage(doneStep.workspaceId(),
                            doneStep.stage(), doneStep.iteration()).stream()
                            .filter(s -> s.requirementCodes().contains(code))
                            .toList();
                    // 需求闸（2026-10-19「没有人为需求负责」）：DONE = 关联步骤全产出 且
                    // TASK 步全部经机器/人工验收（verified_by=tool/human）——AI 自述（self）
                    // 不算数，产出≠满足需求。DISCUSS 步无断言合理，不参与 verified 判定。
                    boolean allProduced = linked.stream().allMatch(StageStep::produced);
                    boolean allVerified = linked.stream()
                            .filter(s -> s.stepType() == StepType.TASK)
                            .allMatch(s -> "tool".equals(s.verifiedBy()) || "human".equals(s.verifiedBy()));
                    if (allProduced && allVerified && !linked.isEmpty()) {
                        requirementRepo.updateStatus(doneStep.workspaceId(), code, "DONE");
                        log.info("需求项完成回写: ws={} {} DONE（{} 个关联步骤全部产出且经验收）",
                                doneStep.workspaceId(), code, linked.size());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("需求状态回写失败（不阻塞任务）: ws={} seq={} -> {}",
                    doneStep.workspaceId(), doneStep.seq(), rootMessage(e));
        }
    }

    /** 重建 spec：把会话级工具（BEAN 形态 ToolDef，payload 携带 Tool 实例）追加进 resolvedTools。 */
    private static AgentSpec withTools(AgentSpec base, List<io.xuanji.core.runtime.ToolDef> tools) {
        List<io.xuanji.core.runtime.ToolDef> merged = new java.util.ArrayList<>(
                base.resolvedTools() == null ? List.of() : base.resolvedTools());
        merged.addAll(tools);
        // 全参构造：透传内置工具上下文（同 StageOrchestrator.withTools，旧 10 参会丢批六新字段）
        return new AgentSpec(base.agentConfigId(), base.name(), base.systemPrompt(), base.model(),
                base.tools(), base.permission(), base.runtime(), base.sessionId(),
                merged, base.skills(), base.builtinTools(), base.workspaceRoot(), base.workspaceId());
    }

    static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        if (cur.getMessage() != null) {
            return cur.getMessage();
        }
        // 裸类名用户看不懂（2026-10-19 反馈）：常见无 message 异常给可读文案
        if (cur instanceof InterruptedException) {
            return "执行被中断（服务重启或人为终止）——可重试";
        }
        if (cur instanceof java.util.concurrent.TimeoutException) {
            return "执行超时";
        }
        if (cur instanceof java.net.ConnectException) {
            return "连接不上上游服务（模型网关/网络）";
        }
        return cur.toString();
    }
}
