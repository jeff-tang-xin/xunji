/**
 * AgentScope 适配层（占位）。
 *
 * <p>本模块是 {@code io.agentscope} 唯一允许出现的模块（docs/detail/01 §1 模块依赖规则）。
 * Agent 集成阶段将落地：</p>
 * <ul>
 *   <li>AgentRuntime 接口的 AgentScope 实现（core.agent 包定义接口）</li>
 *   <li>31 种事件流 → 平台标准 SSE 事件的映射</li>
 *   <li>权限系统（HITL 工具审批）→ 平台审批中心的桥接</li>
 *   <li>provider:model 双轨协议（OPENAI_PROTOCOL / ANTHROPIC_PROTOCOL）的模型装配</li>
 * </ul>
 */
package io.xuanji.agent.scope;
