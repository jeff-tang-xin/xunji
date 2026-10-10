package io.xuanji.core.runtime;

import java.util.List;
import java.util.Map;

/**
 * Agent 构建规格（docs/detail/04 §1 定稿）：按 agent_config 构建智能体实例，
 * 模型/提示词/工具/权限全部来自配置——Agent 全员可配置、不分主子（主文档 4.4）。
 *
 * @param agentConfigId agent_config.id@version（审计与追溯）
 * @param name          显示名
 * @param systemPrompt  系统提示词
 * @param model         模型配置（协议双轨）
 * @param tools         工具绑定（TOOL / MCP / AGENT 三类引用，钉住已发布版本）
 * @param permission    权限策略（default/requireApproval/deny）
 * @param runtime       运行参数（maxIterations 等，可空）
 * @param sessionId     会话标识（多轮记忆：非空时 Agent 实例挂接 stateStore 按
 *                      (agentId, sessionId) 恢复/持久化对话上下文；AGENT 节点等
 *                      无会话场景传 null。docs/detail/04 §1）
 * @param resolvedTools 解析后的工具定义（AgentNodeExecutor 按 tools_json 查
 *                      tool_registry 装配；空=无工具。docs/detail/04 §1）
 * @param skills        解析后的技能内容（AgentNodeExecutor 按 agent 级 skills_json +
 *                      节点级 capabilityBinding 合并查 skill_repo 装配；运行时注入
 *                      system prompt——Anthropic Agent Skills 形态的 P0 落地）
 */
public record AgentSpec(
        String agentConfigId,
        String name,
        String systemPrompt,
        ModelConfig model,
        List<ToolRef> tools,
        PermissionPolicy permission,
        Map<String, Object> runtime,
        String sessionId,
        List<ToolDef> resolvedTools,
        List<SkillDef> skills) {

    public AgentSpec {
        tools = tools == null ? List.of() : List.copyOf(tools);
        permission = permission == null ? PermissionPolicy.allowAll() : permission;
        runtime = runtime == null ? Map.of() : Map.copyOf(runtime);
        resolvedTools = resolvedTools == null ? List.of() : List.copyOf(resolvedTools);
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
