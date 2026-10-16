package io.xuanji.agent.scope;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AgentStateStore 的 PostgreSQL 实现（docs/detail/04 §1）——agent 记忆跨重启持久化。
 *
 * <p>存储设计（V21__agent_state.sql）：表 {@code agent_state(agent_id, session_id, key,
 * state_json, updated_at)}，主键 {@code (agent_id, session_id, key)}。每个 State 一行；
 * {@link #save(String, String, String, List)} 保存列表时同 key 多行（列表槽），
 * {@link #getList} 按 key 取回全部行。userId 为 null/blank 时归一化为 {@code "__anon__"}
 * （对齐 {@link io.agentscope.core.state.InMemoryAgentStateStore} 的匿名命名空间），
 * 匿名会话不互相串扰。</p>
 *
 * <p>P0 不做乐观并发：{@link #supportsVersioning()} 返回 {@code false}，
 * {@link #getVersioned}/{@link #saveIfVersion} 走接口默认实现（无版本语义）。</p>
 */
public class PostgresAgentStateStore implements AgentStateStore {

    /** 匿名用户命名空间（对齐 InMemoryAgentStateStore.ANON_USER）。 */
    private static final String ANON_USER = "__anon__";

    private final JdbcTemplate jdbc;

    public PostgresAgentStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        String agentId = normalizeUser(userId);
        requireSessionId(sessionId);
        jdbc.update(
                "INSERT INTO agent_state (agent_id, session_id, key, state_json, updated_at) "
                        + "VALUES (?, ?, ?, ?, now()) "
                        + "ON CONFLICT (agent_id, session_id, key) "
                        + "DO UPDATE SET state_json = EXCLUDED.state_json, updated_at = now()",
                agentId, sessionId, key, toJson(value));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String agentId = normalizeUser(userId);
        requireSessionId(sessionId);
        // 列表槽：整列表替换——先删旧行再逐元素插入（同 key 多行）
        jdbc.update("DELETE FROM agent_state WHERE agent_id = ? AND session_id = ? AND key = ?",
                agentId, sessionId, key);
        for (State value : values) {
            jdbc.update(
                    "INSERT INTO agent_state (agent_id, session_id, key, state_json, updated_at) "
                            + "VALUES (?, ?, ?, ?, now())",
                    agentId, sessionId, key, toJson(value));
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        String agentId = normalizeUser(userId);
        requireSessionId(sessionId);
        List<String> rows = jdbc.queryForList(
                "SELECT state_json FROM agent_state "
                        + "WHERE agent_id = ? AND session_id = ? AND key = ? "
                        + "ORDER BY updated_at LIMIT 1",
                String.class, agentId, sessionId, key);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromJson(rows.get(0), type));
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        String agentId = normalizeUser(userId);
        requireSessionId(sessionId);
        List<String> rows = jdbc.queryForList(
                "SELECT state_json FROM agent_state "
                        + "WHERE agent_id = ? AND session_id = ? AND key = ? "
                        + "ORDER BY updated_at",
                String.class, agentId, sessionId, key);
        List<T> result = new ArrayList<>(rows.size());
        for (String json : rows) {
            result.add(fromJson(json, itemType));
        }
        return result;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String agentId = normalizeUser(userId);
        requireSessionId(sessionId);
        Integer count = jdbc.queryForObject(
                "SELECT 1 FROM agent_state WHERE agent_id = ? AND session_id = ? LIMIT 1",
                Integer.class, agentId, sessionId);
        return count != null;
    }

    @Override
    public void delete(String userId, String sessionId) {
        String agentId = normalizeUser(userId);
        requireSessionId(sessionId);
        jdbc.update("DELETE FROM agent_state WHERE agent_id = ? AND session_id = ?",
                agentId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        String agentId = normalizeUser(userId);
        requireSessionId(sessionId);
        jdbc.update("DELETE FROM agent_state WHERE agent_id = ? AND session_id = ? AND key = ?",
                agentId, sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String agentId = normalizeUser(userId);
        return Set.copyOf(jdbc.queryForList(
                "SELECT DISTINCT session_id FROM agent_state WHERE agent_id = ?",
                String.class, agentId));
    }

    @Override
    public boolean supportsVersioning() {
        return false;
    }

    @Override
    public void close() {
        // JdbcTemplate 由 Spring 容器管理，无需在此释放
    }

    /** 序列化：AgentState 用其自带 toJson()（pretty），其余 State 走通用 codec。 */
    private static String toJson(State state) {
        if (state instanceof AgentState as) {
            return as.toJson();
        }
        return JsonUtils.getJsonCodec().toJson(state);
    }

    /** 反序列化：按目标类型还原（AgentState 与任意 State 子类通用）。 */
    private static <T extends State> T fromJson(String json, Class<T> type) {
        return JsonUtils.getJsonCodec().fromJson(json, type);
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be null or blank");
        }
    }
}