package io.xuanji.core.runtime;

import java.util.List;

/**
 * Agent 工具权限策略（agent_config.permission_json）：default 兜底 + requireApproval
 * 模式列表（工具粒度 HITL，Agent 内挂起不切断节点）+ deny 显式拒绝。
 *
 * @param def             ALLOW / DENY——未匹配工具的兜底动作
 * @param requireApproval 需人工审批的工具名模式（如 "ops.*"、"connector.jira.delete*"）
 * @param deny            显式拒绝的工具名模式
 */
public record PermissionPolicy(String def, List<String> requireApproval, List<String> deny) {

    public static PermissionPolicy allowAll() {
        return new PermissionPolicy("ALLOW", List.of(), List.of());
    }

    public PermissionPolicy {
        requireApproval = requireApproval == null ? List.of() : List.copyOf(requireApproval);
        deny = deny == null ? List.of() : List.copyOf(deny);
    }
}
