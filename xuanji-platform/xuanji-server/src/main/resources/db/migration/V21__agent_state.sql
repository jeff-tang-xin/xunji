-- ============================================================
-- V21: Agent 状态持久化（agent 记忆跨重启不丢）
--   新建 agent_state 表：AgentStateStore 的 PostgreSQL 后端
--   （PostgresAgentStateStore，替换 InMemoryAgentStateStore）
--   每个 State 一行；列表槽同 (agent_id, session_id, key) 多行
--   userId 为 null/blank 时归一化为 "__anon__"（匿名命名空间）
-- ============================================================

-- 状态表：主键 (agent_id, session_id, key)
--   agent_id   用户/租户命名空间（匿名 = "__anon__"）
--   session_id 会话标识（非空）
--   key        状态键（如 "agent_state"、"memory_messages"）
--   state_json 序列化后的状态 JSON（TEXT，不强制 JSONB 以兼容任意 State 子类）
--   updated_at 更新时间，用于 get 时取最新一行
CREATE TABLE IF NOT EXISTS agent_state (
  agent_id    VARCHAR(255) NOT NULL,
  session_id  VARCHAR(255) NOT NULL,
  key         VARCHAR(255) NOT NULL,
  state_json  TEXT NOT NULL,
  updated_at  TIMESTAMPTZ DEFAULT now(),
  PRIMARY KEY (agent_id, session_id, key)
);

-- 按用户命名空间列出会话（listSessionIds）走该索引
CREATE INDEX IF NOT EXISTS idx_agent_state_agent ON agent_state(agent_id);