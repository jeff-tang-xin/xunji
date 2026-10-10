package io.xuanji.core.runtime;

/**
 * Agent 会话句柄（不透明）：由 {@link AgentRuntime#createAgent} 构建，
 * 携带运行时内部状态（AgentScope 实例等），平台其余代码不得解包。
 * run 结束经 {@link AgentRuntime#close} 释放。
 */
public interface AgentHandle {

    /** 构建规格（审计与追溯） */
    AgentSpec spec();
}
