package io.xuanji.server.engine;

import io.xuanji.agent.scope.ToolApprovalChecker;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具会话级授权存储（内存，重启失效=天然 session 语义）。
 *
 * <p>危险工具（shell）两级授权的「项目内当前 session 授权」侧：用户在项目对话的
 * 授权卡上批准（可勾选「本会话记住」）后写入；agent 实例构建时经
 * {@link ToolApprovalChecker} 读出装配为 ALLOW 规则。永久授权不走这里——
 * 持久化在 agent_config.builtin_tools_json（"shell:always"）。</p>
 */
@Component
public class ToolApprovalStore implements ToolApprovalChecker {

    /** wsId → 已授权工具名集合 */
    private final Map<String, Set<String>> granted = new ConcurrentHashMap<>();

    @Override
    public boolean sessionGranted(String workspaceId, String tool) {
        if (workspaceId == null || tool == null) {
            return false;
        }
        Set<String> tools = granted.get(workspaceId);
        return tools != null && tools.contains(tool);
    }

    /** 授权（会话级，幂等）。 */
    public void grant(String workspaceId, String tool) {
        granted.computeIfAbsent(workspaceId, k -> ConcurrentHashMap.newKeySet()).add(tool);
    }

    /** 撤销会话级授权。 */
    public void revoke(String workspaceId, String tool) {
        Set<String> tools = granted.get(workspaceId);
        if (tools != null) {
            tools.remove(tool);
        }
    }

    /** 该项目当前已会话授权的工具清单（前端授权卡回显）。 */
    public Set<String> listGranted(String workspaceId) {
        Set<String> tools = granted.get(workspaceId);
        return tools == null ? Set.of() : Set.copyOf(tools);
    }
}
