package io.xuanji.agent.scope;

/**
 * 工具会话级授权检查（依赖倒置接口，agent-scope 定义、server 实现）。
 *
 * <p>shell 等危险工具的两级授权中「项目内当前 session 授权」的检查点：
 * 用户在项目对话里批准一次后，授权记录在 server 侧内存（按项目维度，重启失效），
 * agent 实例重建（同会话恢复）时经本接口重新装配为 ALLOW 规则。</p>
 */
public interface ToolApprovalChecker {

    /** 该项目会话内是否已授权某工具（如 "shell"）。 */
    boolean sessionGranted(String workspaceId, String tool);
}
