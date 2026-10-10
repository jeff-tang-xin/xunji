package io.xuanji.core.api;

/**
 * 工作区绑定目标类型（决议 Q11）：工作区<b>仅能绑定 agent 或 workflow</b>。
 *
 * <ul>
 *   <li>{@code AGENT} —— 绑定单个 Agent：工作区即与该 Agent 的对话执行容器</li>
 *   <li>{@code WORKFLOW} —— 绑定工作流：工作区即该流程的阶段化执行容器
 *       （SSE 事件流 + 决策门交互）</li>
 * </ul>
 *
 * <p>绑定发生在创建工作区时（项目名 + 绑定目标 + Agent 决策授权一次性完成），
 * 绑定目标必须是<b>已发布版本</b>（{@link AssetRef} 钉住）。SKILL/MCP/TOOL
 * 不作为工作区绑定目标——它们是 Agent 的能力装配内容（见 AgentCapabilityBinding）。</p>
 */
public enum WorkspaceBindingType {
    AGENT, WORKFLOW
}
