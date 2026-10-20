package io.xuanji.server.project.orchestrator;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 阶段小流程实体（黑板上的最小世界状态单元）。
 *
 * <p>不可变值对象：状态迁移通过 {@link #withStatus} 产生新实例，
 * 持久化由 {@link StageStepRepository} 负责。主控的每次编排动作
 * （规划/执行/讨论/跳过/决策）都在黑板上留下对应记录。</p>
 *
 * <p>DISCUSS 型的 {@code participants} 为参与者名单（含主控指定的执行 agent 视角）；
 * DECISION 型的 {@code instruction}=问题、{@code outputSummary}=用户答复。</p>
 */
public record StageStep(
        Long id,
        String workspaceId,
        String stage,
        int iteration,
        int seq,
        String name,
        StepType stepType,
        String agent,
        List<String> participants,
        StepStatus status,
        String instruction,
        String outputSummary,
        String outputFull,
        Long artifactId,
        String skipReason,
        String error,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String verifiedBy,
        String acceptanceJson,
        String deviationJson,
        List<String> requirementCodes,
        String acceptanceOwner) {

    /** 23 参兼容构造器：acceptance_owner 默认 tool（机器验收）——存量调用点不用改。 */
    public StageStep(Long id, String workspaceId, String stage, int iteration, int seq, String name,
                     StepType stepType, String agent, List<String> participants, StepStatus status,
                     String instruction, String outputSummary, String outputFull, Long artifactId,
                     String skipReason, String error, LocalDateTime createdAt, LocalDateTime startedAt,
                     LocalDateTime finishedAt, String verifiedBy, String acceptanceJson,
                     String deviationJson, List<String> requirementCodes) {
        this(id, workspaceId, stage, iteration, seq, name, stepType, agent, participants, status,
                instruction, outputSummary, outputFull, artifactId, skipReason, error,
                createdAt, startedAt, finishedAt, verifiedBy, acceptanceJson, deviationJson,
                requirementCodes, "tool");
    }

    public StageStep {
        participants = participants == null ? List.of() : List.copyOf(participants);
        // 需求先行：步骤声明「为了哪个需求」（code 列表，规划时锁定）
        requirementCodes = requirementCodes == null ? List.of() : List.copyOf(requirementCodes);
        // 信任链：验证来源默认 self（AI 自述）——tool/human 只能由平台判定器与人工确认路径写入
        verifiedBy = verifiedBy == null || verifiedBy.isBlank() ? "self" : verifiedBy;
        // 验收责任链（P0）：验收人默认 tool（机器验收）；human=执行完进 WAITING_ACCEPT 等用户通过
        acceptanceOwner = "human".equalsIgnoreCase(acceptanceOwner) ? "human" : "tool";
    }

    /** 换 id（insert 后回填），其余不变。 */
    public StageStep withId(long newId) {
        return new StageStep(newId, workspaceId, stage, iteration, seq, name, stepType, agent,
                participants, status, instruction, outputSummary, outputFull, artifactId,
                skipReason, error, createdAt, startedAt, finishedAt,
                verifiedBy, acceptanceJson, deviationJson, requirementCodes, acceptanceOwner);
    }

    /** 是否活跃（执行中或等待用户）——收口校验：存在活跃步骤则不可收口。 */
    public boolean active() {
        return status.active();
    }

    /** 是否已有产出（产物登记或纪要落档）。 */
    public boolean produced() {
        return status == StepStatus.DONE;
    }

    /** 状态迁移：返回带新状态与时间戳的新实例（不改变身份字段）。 */
    public StageStep withStatus(StepStatus next) {
        LocalDateTime now = LocalDateTime.now();
        return new StageStep(id, workspaceId, stage, iteration, seq, name, stepType, agent,
                participants, next, instruction, outputSummary, outputFull, artifactId,
                skipReason, error,
                createdAt,
                status == StepStatus.PLANNED && next == StepStatus.EXECUTING ? now : startedAt,
                next == StepStatus.DONE || next == StepStatus.SKIPPED
                        || next == StepStatus.FAILED ? now : finishedAt,
                verifiedBy, acceptanceJson, deviationJson, requirementCodes, acceptanceOwner);
    }

    /** 任务/讨论完成：状态 DONE + 产出摘要与全文 + 关联产物。 */
    public StageStep withOutput(String summary, String full, Long artifactId) {
        return new StageStep(id, workspaceId, stage, iteration, seq, name, stepType, agent,
                participants, StepStatus.DONE, instruction, summary, full, artifactId,
                skipReason, error, createdAt, startedAt, LocalDateTime.now(),
                verifiedBy, acceptanceJson, deviationJson, requirementCodes, acceptanceOwner);
    }

    /** 执行失败：状态 FAILED + 错误信息（主控下轮对话可见，自主决定重试或换路）。 */
    public StageStep withError(String message) {
        return new StageStep(id, workspaceId, stage, iteration, seq, name, stepType, agent,
                participants, StepStatus.FAILED, instruction, outputSummary, outputFull,
                artifactId, skipReason, message, createdAt, startedAt, LocalDateTime.now(),
                verifiedBy, acceptanceJson, deviationJson, requirementCodes, acceptanceOwner);
    }

    /** 验收主体变更：tool=机器判定通过 / human=人确认（self=AI 自述，不作为需求闭环依据）。 */
    public StageStep withVerifiedBy(String verifier) {
        return new StageStep(id, workspaceId, stage, iteration, seq, name, stepType, agent,
                participants, status, instruction, outputSummary, outputFull,
                artifactId, skipReason, error, createdAt, startedAt, finishedAt,
                verifier, acceptanceJson, deviationJson, requirementCodes, acceptanceOwner);
    }

    /** seq 变更（重拾复用：新派单步骤对齐到被复用旧步骤的 seq，追溯图节点 key 保持稳定）。 */
    public StageStep withSeq(int newSeq) {
        return new StageStep(id, workspaceId, stage, iteration, newSeq, name, stepType, agent,
                participants, status, instruction, outputSummary, outputFull,
                artifactId, skipReason, error, createdAt, startedAt, finishedAt,
                verifiedBy, acceptanceJson, deviationJson, requirementCodes, acceptanceOwner);
    }

    /** 主控跳过：状态 SKIPPED + 理由留档（能跳就跳，但必须说清为什么）。 */
    public StageStep withSkip(String reason) {
        return new StageStep(id, workspaceId, stage, iteration, seq, name, stepType, agent,
                participants, StepStatus.SKIPPED, instruction, outputSummary, outputFull,
                artifactId, reason, error, createdAt, startedAt, LocalDateTime.now(),
                verifiedBy, acceptanceJson, deviationJson, requirementCodes, acceptanceOwner);
    }

    /** 用户答复决策：状态 DONE + 答复留档。 */
    public StageStep withAnswer(String answer) {
        return new StageStep(id, workspaceId, stage, iteration, seq, name, stepType, agent,
                participants, StepStatus.DONE, instruction, answer, outputFull,
                artifactId, skipReason, error, createdAt, startedAt, LocalDateTime.now(),
                verifiedBy, acceptanceJson, deviationJson, requirementCodes, acceptanceOwner);
    }

    /**
     * 偏差申报落档（不改状态）：主控发现无法按计划完成时经 declare_deviation 申报，
     * {reason, fallback, declaredAt} JSON。静默降级（无申报的实质偏差）由第二批判定器比对抓取。
     */
    public StageStep withDeviation(String deviationJson) {
        return new StageStep(id, workspaceId, stage, iteration, seq, name, stepType, agent,
                participants, status, instruction, outputSummary, outputFull,
                artifactId, skipReason, error, createdAt, startedAt, finishedAt,
                verifiedBy, acceptanceJson, deviationJson, requirementCodes, acceptanceOwner);
    }

    /** 关联需求编号（ask_peer/discuss 留痕到需求：需求看板据此统计讨论/澄清次数）。 */
    public StageStep withRequirementCodes(List<String> codes) {
        return new StageStep(id, workspaceId, stage, iteration, seq, name, stepType, agent,
                participants, status, instruction, outputSummary, outputFull,
                artifactId, skipReason, error, createdAt, startedAt, finishedAt,
                verifiedBy, acceptanceJson, deviationJson,
                codes == null || codes.isEmpty() ? List.of() : codes, acceptanceOwner);
    }

    /** participants 列的存储形态（逗号分隔），解析与序列化收敛在此。 */
        /** 声明验收人（'tool'=机器验收 | 'human'=用户验收）——规划时锁定。 */
    public StageStep withAcceptanceOwner(String owner) {
        return new StageStep(id, workspaceId, stage, iteration, seq, name, stepType, agent,
                participants, status, instruction, outputSummary, outputFull,
                artifactId, skipReason, error, createdAt, startedAt, finishedAt,
                verifiedBy, acceptanceJson, deviationJson, requirementCodes, owner);
    }

public static List<String> parseParticipants(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 从文本提取需求编号（REQ-001 形态）——LLM 漏传 requirement_codes 时的确定性兜底
     * （实测 PM 讨论名带 REQ-005 但参数没带，不能依赖 LLM 自觉）。
     */
    public static List<String> extractRequirementCodes(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.regex.Pattern.compile("REQ-\\d+").matcher(text).results()
                .map(m -> m.group()).distinct().toList();
    }

    /** requirement_codes JSONB 写侧：白名单元素（字母数字下划线连字符）拼 JSON 数组，防注入。 */
    public static String joinRequirementCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        List<String> safe = codes.stream().filter(c -> c.equals("*") || c.matches("[A-Za-z0-9_-]+")).distinct().toList();
        return safe.isEmpty() ? null : "[\"" + String.join("\",\"", safe) + "\"]";
    }

    /** requirement_codes JSONB 读侧：提取 JSON 数组里的字符串 token（兼容历史逗号串形态）。 */
    public static List<String> parseRequirementCodes(String stored) {
        if (stored == null || stored.isBlank() || "null".equals(stored)) {
            return List.of();
        }
        String s = stored.trim();
        if (s.startsWith("[")) {
            return java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(s).results()
                    .map(m -> m.group(1)).distinct().toList();
        }
        // 历史兼容：非 JSON 形态按逗号分隔
        return parseParticipants(s);
    }

    public static String joinParticipants(List<String> participants) {
        return participants == null ? "" : String.join(",", participants);
    }
}
