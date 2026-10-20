/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.xuanji.server.project.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;

/**
 * 决策答复闭环服务：决策落定（用户答复）后的确定性联动，两条答复链路共用——
 * <ul>
 *   <li>{@code StageOrchestrator.answerDecision}（前端决策答复端点）</li>
 *   <li>{@code OrchestrationTools.recordAnswer}（主控在对话里落用户答复的工具）</li>
 * </ul>
 * 联动内容：
 * <ol>
 *   <li><b>需求自动确认</b>：决策关联的需求（requirement_codes + 决策文本正则兜底）自动置
 *       CONFIRMED，source 记 decision#seq——用户答复即确认，不依赖主控自觉调
 *       declare_requirements(confirm)（LLM 不可靠，「嘴上说更新库里没写」已有实锤教训）。</li>
 *   <li><b>决策记录落档</b>：问题/答复/关联需求/答复人/时间渲染成 markdown，经 StageDocStore
 *       双落（文件 + DOC 索引）——跨阶段可检索的结构化决策记录（开发拍板的技术方案、
 *       运维阶段的变更授权同此通道）。</li>
 * </ol>
 * 联动失败只记日志不抛——决策答复本身已落库（步骤 DONE），联动是增强不是前置。
 */
@Service
public class DecisionSettleService {

    private static final Logger log = LoggerFactory.getLogger(DecisionSettleService.class);

    /** 需求编号形态：REQ-001（declare_requirements 生成）。 */
    private static final Pattern REQ_CODE = Pattern.compile("\\b(REQ-\\d+)\\b");

    /** 验收类决策名形态：阶段交付验收/交付确认/阶段收口。 */
    private static final Pattern ACCEPT_NAME = Pattern.compile("验收|交付确认|阶段收口");

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RequirementRepository requirementRepo;
    private final StageDocStore docStore;
    private final ProjectEventBus projectEvents;

    public DecisionSettleService(RequirementRepository requirementRepo, StageDocStore docStore,
                                 ProjectEventBus projectEvents) {
        this.requirementRepo = requirementRepo;
        this.docStore = docStore;
        this.projectEvents = projectEvents;
    }

    /**
     * 决策答复闭环：需求自动确认 + 决策记录落档。
     *
     * @param decisionStep 已置 DONE 的 DECISION 步（含 withAnswer 后的产出）
     * @param answer       用户答复文本
     * @param answeredBy   答复人（用户名或系统标识）
     * @return 自动确认的需求 code 列表（供调用方提示主控）
     */
    public List<String> settle(StageStep decisionStep, String answer, String answeredBy) {
        List<String> confirmed = confirmRequirements(decisionStep);
        List<String> accepted = acceptRequirements(decisionStep, answer);
        writeDecisionDoc(decisionStep, answer, answeredBy, confirmed);
        // 项目状态实时推送：决策闭环 + 需求确认，在线前端收到即刷新台账与需求看板
        try {
            projectEvents.publish(decisionStep.workspaceId(), "decision.answered", Map.of(
                    "stage", decisionStep.stage(), "seq", decisionStep.seq(),
                    "name", decisionStep.name(), "confirmed", confirmed));
            if (!confirmed.isEmpty()) {
                projectEvents.publish(decisionStep.workspaceId(), "requirement.updated", Map.of(
                        "stage", decisionStep.stage(), "codes", confirmed, "action", "CONFIRMED"));
            }
            if (!accepted.isEmpty()) {
                projectEvents.publish(decisionStep.workspaceId(), "requirement.updated", Map.of(
                        "stage", decisionStep.stage(), "codes", accepted, "action", "DONE"));
            }
        } catch (Exception e) {
            log.warn("决策闭环事件推送失败（不影响落库）: ws={} -> {}",
                    decisionStep.workspaceId(), e.getMessage());
        }
        return confirmed;
    }

    /** 需求自动确认：显式关联 + 决策文本正则兜底（LLM 漏传 requirement_codes 时从决策名/问题提取）。 */
    private List<String> confirmRequirements(StageStep step) {
        List<String> codes = new ArrayList<>(step.requirementCodes());
        Matcher m = REQ_CODE.matcher(step.name() + " " + step.instruction());
        while (m.find()) {
            String c = m.group(1);
            if (!codes.contains(c)) {
                codes.add(c);
            }
        }
        List<String> confirmed = new ArrayList<>();
        for (String code : codes) {
            try {
                requirementRepo.findByCode(step.workspaceId(), code).ifPresent(r -> {
                    // CONFIRMED/IN_PROGRESS/DONE 不回退；PROPOSED 才需要确认
                    if ("PROPOSED".equals(r.status())) {
                        requirementRepo.confirmByDecision(step.workspaceId(), code, step.seq());
                        confirmed.add(code);
                    }
                });
            } catch (Exception e) {
                log.warn("决策答复回写需求失败: ws={} code={} -> {}", step.workspaceId(), code, e.getMessage());
            }
        }
        if (!confirmed.isEmpty()) {
            log.info("决策 #{} 答复联动: 需求自动确认 {}", step.seq(), confirmed);
        }
        return confirmed;
    }

    /**
     * 验收回写（2026-10-20 全链路诊断）：交付验收类决策获用户批准时，把决策关联的需求
     * （显式 requirement_codes + 决策文本正则兜底）置 DONE——用户批准验收 = human 确认，
     * 符合信任链 verified_by=human 语义。此前需求永远停在 IN_PROGRESS（机器判定器未做、
     * self 不算数），验收卡宣称完成而需求台账全未完成 = 两个真相打架。
     * 仅对名字含「验收/交付确认/阶段收口」的决策生效，且答复不含拒绝语义；失败只记日志。
     */
    private static final Pattern REJECT_WORDS = Pattern.compile("拒绝|不同意|驳回|不通过|打回|重做|不确认|暂不");

    private List<String> acceptRequirements(StageStep step, String answer) {
        if (!ACCEPT_NAME.matcher(step.name() + " " + step.instruction()).find()) {
            return List.of();
        }
        if (answer != null && REJECT_WORDS.matcher(answer).find()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>(step.requirementCodes());
        Matcher m = REQ_CODE.matcher(step.name() + " " + step.instruction());
        while (m.find()) {
            String c = m.group(1);
            if (!codes.contains(c)) {
                codes.add(c);
            }
        }
        List<String> accepted = new ArrayList<>();
        for (String code : codes) {
            try {
                requirementRepo.findByCode(step.workspaceId(), code).ifPresent(r -> {
                    if (!"DONE".equals(r.status()) && !"DROPPED".equals(r.status())) {
                        requirementRepo.updateStatus(step.workspaceId(), code, "DONE");
                        accepted.add(code);
                    }
                });
            } catch (Exception e) {
                log.warn("验收回写需求失败: ws={} code={} -> {}", step.workspaceId(), code, e.getMessage());
            }
        }
        if (!accepted.isEmpty()) {
            log.info("决策 #{} 验收联动: 需求置 DONE {}", step.seq(), accepted);
        }
        return accepted;
    }

    /** 决策记录落档：docs/{stage}/iter{n}/ 双落（文件 + DOC 索引），失败不抛。 */
    private void writeDecisionDoc(StageStep step, String answer, String answeredBy, List<String> confirmed) {
        try {
            StringBuilder md = new StringBuilder();
            md.append("# 决策 #").append(step.seq()).append("：").append(step.name()).append("\n\n");
            md.append("- 阶段：").append(step.stage()).append("（第 ").append(step.iteration()).append(" 轮）\n");
            md.append("- 状态：已答复\n");
            md.append("- 答复人：").append(answeredBy == null || answeredBy.isBlank() ? "user" : answeredBy).append("\n");
            md.append("- 时间：").append(LocalDateTime.now().format(TS_FMT)).append("\n");
            md.append("- 关联需求：").append(confirmed.isEmpty()
                    ? (step.requirementCodes().isEmpty() ? "无" : String.join("、", step.requirementCodes()) + "（此前已确认）")
                    : String.join("、", confirmed) + "（已随本决策自动确认，source=decision#" + step.seq() + "）");
            md.append("\n\n## 问题\n\n").append(step.instruction() == null ? "" : step.instruction()).append("\n\n");
            md.append("## 答复\n\n").append(answer == null ? "" : answer).append("\n");
            docStore.write(step.workspaceId(), step.stage(), step.iteration(), "DECISION",
                    step.seq(), "决策#" + step.seq() + "-" + step.name(), md.toString(),
                    answeredBy == null || answeredBy.isBlank() ? "user" : answeredBy);
        } catch (Exception e) {
            log.warn("决策记录落档失败（答复已落库）: ws={} seq={} -> {}", step.workspaceId(), step.seq(), e.getMessage());
        }
    }
}
