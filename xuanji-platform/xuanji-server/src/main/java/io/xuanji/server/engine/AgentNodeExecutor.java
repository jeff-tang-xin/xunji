package io.xuanji.server.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.core.api.AgentCapabilityBinding;
import io.xuanji.core.api.AssetRef;
import io.xuanji.core.api.AssetType;
import io.xuanji.core.definition.NodeDef;
import io.xuanji.core.runtime.AgentHandle;
import io.xuanji.core.runtime.AgentMessage;
import io.xuanji.core.runtime.AgentResult;
import io.xuanji.core.runtime.AgentRuntime;
import io.xuanji.core.runtime.AgentSpec;
import io.xuanji.core.runtime.ModelConfig;
import io.xuanji.core.runtime.SkillDef;
import io.xuanji.core.runtime.ToolDef;
import io.xuanji.core.runtime.ToolRef;
import io.xuanji.server.asset.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AGENT 节点执行器（NodeKind.AGENT 分派语义，docs/detail/04 §1）：
 * refId = {@code agent:{agentConfigId}@{version}}，按已发布 agent_config +
 * model_provider_config 构建 AgentSpec，委托 {@link AgentRuntime} 执行。
 *
 * <p>输出契约：{@code {"output": 最终文本}}——下游经 edge.mapping 以
 * {@code $.{nodeId}.output} 引入（与 ATOMIC 节点 output 字段约定一致）。</p>
 *
 * <p>P0 简化：提示词 = 上游输入整体 JSON 序列化；工具注册与节点级能力绑定
 * （Q12）随工具集成阶段落地。每次执行构建即用即释放（池化 P3 评估）。</p>
 */
@Component
public class AgentNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentNodeExecutor.class);

    private static final String REF_PREFIX = "agent:";

    private final AgentRuntime runtime;
    private final CryptoUtil cryptoUtil;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AgentNodeExecutor(AgentRuntime runtime, CryptoUtil cryptoUtil,
                             JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.cryptoUtil = cryptoUtil;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 执行 AGENT 节点：返回节点输出（output 字段 = Agent 最终文本） */
    public Map<String, Object> execute(NodeDef def, Map<String, Object> input) {
        Ref ref = parseRef(def.refId());
        AgentSpec spec = buildSpec(ref.agentConfigId(), ref.version(), null, def.capabilityBinding());

        AgentHandle handle = runtime.createAgent(spec);
        try {
            AgentResult result = runtime.call(handle, new AgentMessage(buildUserText(input), null));
            log.info("agent node executed: run-node ref={} -> {} chars", def.refId(),
                    result.text() == null ? 0 : result.text().length());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("output", result.text());
            return out;
        } finally {
            runtime.close(handle);
        }
    }

    /**
     * 构造用户消息文本：
     * <ul>
     *   <li>驳回重生成（含 _revisionComment）：组装「上一版产物 + 修改意见」的明确指令，
     *       要求 Agent 在原版基础上修订产出新版本；</li>
     *   <li>普通输入：走 toJsonText（单 text 透传 / 整体 JSON）。</li>
     * </ul>
     */
    private String buildUserText(Map<String, Object> input) {
        Object comment = input.get("_revisionComment");
        if (comment != null) {
            Object round = input.get("_revisionRound");
            String prevText = extractPrevText(input.get("_previousOutput"));
            StringBuilder sb = new StringBuilder();
            sb.append("你上一版提交的产物未通过评审，请根据以下批注意见修订，产出第 ")
              .append(round).append(" 版。\n\n");
            sb.append("【批注意见】\n").append(comment).append("\n\n");
            sb.append("【你上一版的产物】\n")
              .append(prevText == null || prevText.isBlank() ? "(空)" : prevText).append("\n\n");
            sb.append("请在保留上一版合理内容的基础上，针对批注意见逐条修改，直接给出修订后的完整产物。");
            return sb.toString();
        }
        return toJsonText(input);
    }

    /** 上一版产物可能是 {output:文本} 节点输出包装，尽量取出文本 */
    private String extractPrevText(Object prev) {
        if (prev == null) {
            return null;
        }
        if (prev instanceof String s) {
            return s;
        }
        if (prev instanceof Map<?, ?> m) {
            Object inner = m.get("output");
            if (inner instanceof String si) {
                return si;
            }
            if (inner != null) {
                return String.valueOf(inner);
            }
            return String.valueOf(m);
        }
        return String.valueOf(prev);
    }

    /**
     * 按已发布 agent_config + model_provider_config 构建 AgentSpec
     * （AGENT 节点执行与 chat SSE 共用的装配入口）。
     *
     * @param sessionId 会话标识（多轮记忆；AGENT 节点等无会话场景传 null）
     */
    public AgentSpec buildSpec(long agentConfigId, int version, String sessionId) {
        return buildSpec(agentConfigId, version, sessionId, null);
    }

    /**
     * 构建 AgentSpec。skill 两级合并：agent 级（agent_config.skills_json）为基底，
     * 节点级（workflow agent 节点 capabilityBinding）覆盖同 id——Anthropic 形态下 skill
     * 主要绑在 Agent 本身（chat 与工作流共用），节点级用于工作流内临时增删。
     *
     * @param nodeBinding 节点级能力绑定（chat 等无节点场景传 null）
     */
    public AgentSpec buildSpec(long agentConfigId, int version, String sessionId,
                               AgentCapabilityBinding nodeBinding) {
        Map<String, Object> agentRow = loadAgentConfig(new Ref(agentConfigId, version));
        ModelConfig model = loadModelConfig(agentRow);
        Map<String, Object> params = jsonMap(agentRow.get("params_json"));
        List<ToolRef> toolRefs = parseToolRefs(agentRow.get("tools_json"));
        List<ToolDef> resolved = resolveTools(toolRefs);
        List<AssetRef> agentSkills = parseSkillRefs(agentRow.get("skills_json"));
        List<AssetRef> effSkills = AgentCapabilityBinding
                .effective(new AgentCapabilityBinding(agentSkills, List.of()), nodeBinding).skills();
        List<SkillDef> skills = resolveSkills(effSkills);
        return new AgentSpec(
                agentConfigId + "@" + version,
                (String) agentRow.getOrDefault("name", "agent-" + agentConfigId),
                (String) agentRow.get("system_prompt"),
                model,
                toolRefs,
                null,
                params,
                sessionId,
                resolved,
                skills);
    }

    /** tools_json → ToolRef 列表（JSONB 数组：[{type, ref, override?}]） */
    @SuppressWarnings("unchecked")
    private List<ToolRef> parseToolRefs(Object toolsJson) {
        if (toolsJson == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(toolsJson.toString(), List.class);
            List<ToolRef> refs = new ArrayList<>();
            for (Map<String, Object> item : items) {
                refs.add(new ToolRef(
                        (String) item.get("type"),
                        (String) item.get("ref"),
                        null));
            }
            return refs;
        } catch (Exception e) {
            log.warn("tools_json parse failed, ignored: {}", e.toString());
            return List.of();
        }
    }

    /**
     * AGENT 引用（ref=agent:{id}@{version}）→ 被调 Agent 的可调用工具（agent.invoke 对等互调）。
     * 构建被调 AgentSpec（模型+提示词+技能，钉住已发布版本）放入 ToolDef.payload，运行时
     * AgentInvokeTool 经 AgentRuntime 起子 Agent 完成一次对话并回填结果。为避免递归展开
     * （A→B→A 无限），被调 spec 不解析其自身工具（resolvedTools 恒空）——P0 被调 Agent 为
     * 「模型+提示词+技能」的对话体。
     */
    private List<ToolDef> resolveAgentTools(String ref) {
        String body = ref.startsWith("agent:") ? ref.substring(6) : ref;
        int at = body.indexOf('@');
        long id;
        int ver;
        try {
            id = Long.parseLong((at >= 0 ? body.substring(0, at) : body).trim());
            ver = at >= 0 ? Integer.parseInt(body.substring(at + 1).trim()) : -1;
        } catch (NumberFormatException e) {
            log.warn("agent ref malformed, skipped: {}", ref);
            return List.of();
        }
        try {
            if (ver <= 0) {
                List<Map<String, Object>> vr = jdbc.queryForList(
                        "SELECT version FROM agent_config WHERE id = ? AND status = 'PUBLISHED' "
                                + "ORDER BY version DESC LIMIT 1", id);
                ver = vr.isEmpty() ? -1 : ((Number) vr.get(0).get("version")).intValue();
            }
            Map<String, Object> agentRow = loadAgentConfig(new Ref(id, ver));
            ModelConfig model = loadModelConfig(agentRow);
            Map<String, Object> params = jsonMap(agentRow.get("params_json"));
            List<SkillDef> skills = resolveSkills(parseSkillRefs(agentRow.get("skills_json")));
            String name = (String) agentRow.getOrDefault("name", "agent-" + id);
            AgentSpec target = new AgentSpec(
                    id + "@" + ver, name,
                    (String) agentRow.get("system_prompt"),
                    model, List.of(), null, params, null, List.of(), skills);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("query", Map.of("type", "string", "description", "交给该 Agent 的问题或指令"));
            schema.put("properties", props);
            schema.put("required", List.of("query"));
            Object d = agentRow.get("description");
            String desc = d == null ? ("调用 Agent：" + name) : ("调用 Agent：" + name + " —— " + d);
            log.info("agent #{}@{} resolved as invoke-tool '{}'", id, ver, name);
            return List.of(new ToolDef(name, desc, schema, "AGENT", ref, target));
        } catch (Exception e) {
            log.warn("agent #{} resolve failed, skipped: {} ({})", id, ref, e.toString());
            return List.of();
        }
    }

    /**
     * ToolRef → ToolDef：type=TOOL 查 tool_registry（PUBLISHED 钉版本）；
     * MCP/AGENT 引用 P0 跳过（warn 日志，不阻断装配）。
     */
    private List<ToolDef> resolveTools(List<ToolRef> refs) {
        List<ToolDef> defs = new ArrayList<>();
        for (ToolRef ref : refs) {
            if ("MCP".equals(ref.type())) {
                defs.addAll(resolveMcpTools(ref.ref()));
                continue;
            }
            if ("AGENT".equals(ref.type())) {
                defs.addAll(resolveAgentTools(ref.ref()));
                continue;
            }
            if (!"TOOL".equals(ref.type())) {
                log.warn("tool ref type={} not supported yet, skipped: {}", ref.type(), ref.ref());
                continue;
            }
            // ref 形如 tool:{id}@{version}
            String body = ref.ref().startsWith("tool:") ? ref.ref().substring(5) : ref.ref();
            int at = body.indexOf('@');
            long id;
            int ver;
            try {
                id = Long.parseLong((at >= 0 ? body.substring(0, at) : body).trim());
                ver = at >= 0 ? Integer.parseInt(body.substring(at + 1).trim()) : -1;
            } catch (NumberFormatException e) {
                log.warn("tool ref malformed, skipped: {}", ref.ref());
                continue;
            }
            String sql = "SELECT name, description, source_type, source_ref, input_schema::text AS input_schema "
                    + "FROM tool_registry WHERE id = ? AND status = 'PUBLISHED'"
                    + (ver > 0 ? " AND version = ?" : "");
            List<Map<String, Object>> rows = ver > 0
                    ? jdbc.queryForList(sql, id, ver)
                    : jdbc.queryForList(sql, id);
            if (rows.isEmpty()) {
                log.warn("tool #{} 无已发布版本（或版本不存在），skipped: {}", id, ref.ref());
                continue;
            }
            Map<String, Object> row = rows.get(0);
            defs.add(new ToolDef(
                    (String) row.get("name"),
                    (String) row.get("description"),
                    jsonMap(row.get("input_schema")),
                    (String) row.get("source_type"),
                    (String) row.get("source_ref"),
                    null));
        }
        return defs;
    }

    /**
     * MCP 引用（ref=mcp:{id}@{version}）→ 该 server 已 discover 的工具清单，每个展开为一个
     * {@link ToolDef}（sourceType=MCP，sourceRef=endpoint|toolName）。清单来自 discover 落库的
     * discovered_tools_json；未 discover 则跳过（warn，不阻断装配）。
     */
    @SuppressWarnings("unchecked")
    private List<ToolDef> resolveMcpTools(String ref) {
        String body = ref.startsWith("mcp:") ? ref.substring(4) : ref;
        int at = body.indexOf('@');
        long id;
        try {
            id = Long.parseLong((at >= 0 ? body.substring(0, at) : body).trim());
        } catch (NumberFormatException e) {
            log.warn("mcp ref malformed, skipped: {}", ref);
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT endpoint, discovered_tools_json::text AS tools FROM mcp_config WHERE id = ?", id);
        if (rows.isEmpty()) {
            log.warn("mcp_config #{} 不存在，skipped: {}", id, ref);
            return List.of();
        }
        String endpoint = (String) rows.get(0).get("endpoint");
        Object toolsRaw = rows.get(0).get("tools");
        if (toolsRaw == null || toolsRaw.toString().isBlank()) {
            log.warn("mcp #{} 尚未 discover（discovered_tools_json 空），skipped: {}", id, ref);
            return List.of();
        }
        List<ToolDef> defs = new ArrayList<>();
        try {
            List<Map<String, Object>> tools = objectMapper.readValue(toolsRaw.toString(), List.class);
            for (Map<String, Object> t : tools) {
                Map<String, Object> schema = t.get("inputSchema") instanceof Map<?, ?> m
                        ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of();
                String name = String.valueOf(t.get("name"));
                defs.add(new ToolDef(
                        name,
                        t.get("description") == null ? name : String.valueOf(t.get("description")),
                        schema,
                        "MCP",
                        endpoint + "|" + name,
                        null));
            }
            log.info("mcp #{} resolved {} tool(s): {}", id, defs.size(), ref);
        } catch (Exception e) {
            log.warn("mcp tools_json parse failed, skipped: {} ({})", ref, e.toString());
        }
        return defs;
    }

    /**
     * skill 引用（AssetRef，id 为 skill_repo.id 数字串）→ 查已发布 skill_repo 的
     * name/description/content_md 展开为 {@link SkillDef}；不存在/未发布则 warn 跳过（不阻断装配）。
     */
    private List<SkillDef> resolveSkills(List<AssetRef> refs) {
        List<SkillDef> defs = new ArrayList<>();
        for (AssetRef ref : refs) {
            long id;
            try {
                id = Long.parseLong(ref.id().trim());
            } catch (NumberFormatException e) {
                log.warn("skill ref id 非数字（skill_repo 按数字 id 引用），skipped: {}", ref);
                continue;
            }
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT name, description, content_md FROM skill_repo "
                            + "WHERE id = ? AND version = ? AND status = 'PUBLISHED'",
                    id, ref.version());
            if (rows.isEmpty()) {
                log.warn("skill #{}@{} 不存在或未发布，skipped", id, ref.version());
                continue;
            }
            Map<String, Object> row = rows.get(0);
            defs.add(new SkillDef(
                    (String) row.get("name"),
                    (String) row.get("description"),
                    (String) row.get("content_md")));
        }
        if (!defs.isEmpty()) {
            log.info("resolved {} skill(s) into agent system prompt", defs.size());
        }
        return defs;
    }

    /** skills_json（["skill:{id}@{ver}"] 字符串数组）→ AssetRef 列表 */
    @SuppressWarnings("unchecked")
    private List<AssetRef> parseSkillRefs(Object skillsJson) {
        if (skillsJson == null) {
            return List.of();
        }
        try {
            List<String> items = objectMapper.readValue(skillsJson.toString(), List.class);
            List<AssetRef> refs = new ArrayList<>();
            for (String s : items) {
                AssetRef r = parseSkillRef(s);
                if (r != null) {
                    refs.add(r);
                }
            }
            return refs;
        } catch (Exception e) {
            log.warn("skills_json parse failed, skipped: {}", e.toString());
            return List.of();
        }
    }

    /** "skill:{id}@{version}" → AssetRef（type 前缀可省，version 缺省 1） */
    private AssetRef parseSkillRef(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String rest = s.trim();
        int colon = rest.indexOf(':');
        if (colon > 0) {
            rest = rest.substring(colon + 1);   // 去 "skill:" 前缀
        }
        int at = rest.indexOf('@');
        String id = at >= 0 ? rest.substring(0, at) : rest;
        int version = 1;
        if (at >= 0) {
            try {
                version = Integer.parseInt(rest.substring(at + 1).trim());
            } catch (NumberFormatException e) {
                log.warn("skill ref version 非数字，按 v1 处理: {}", s);
            }
        }
        return new AssetRef(AssetType.SKILL, id.trim(), version);
    }

    /** refId 形如 agent:{id}@{version}；version 缺省取最新已发布版本 */
    private Ref parseRef(String refId) {
        if (refId == null || !refId.startsWith(REF_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "AGENT 节点 refId 必须形如 agent:{id}@{version}，实际: " + refId);
        }
        String body = refId.substring(REF_PREFIX.length());
        int at = body.indexOf('@');
        String idPart = at >= 0 ? body.substring(0, at) : body;
        String verPart = at >= 0 ? body.substring(at + 1) : null;
        long id;
        try {
            id = Long.parseLong(idPart.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "AGENT 节点 refId 的 id 必须是数字，实际: " + refId);
        }
        if (verPart == null || verPart.isBlank()) {
            List<Long> vs = jdbc.queryForList(
                    "SELECT version FROM agent_config WHERE id = ? AND status = 'PUBLISHED' ORDER BY version DESC LIMIT 1",
                    Long.class, id);
            if (vs.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "agent_config #" + id + " 无已发布版本（先发布再引用）");
            }
            return new Ref(id, vs.get(0).intValue());
        }
        try {
            return new Ref(id, Integer.parseInt(verPart.trim()));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "AGENT 节点 refId 的 version 必须是数字，实际: " + refId);
        }
    }

    /** 发布期引用校验：agent:{id}@{version} 是否存在且已发布（version 缺省取最新已发布） */
    public void validateRef(String refId) {
        loadAgentConfig(parseRef(refId));
    }

    private Map<String, Object> loadAgentConfig(Ref ref) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, system_prompt, model_provider_id, tools_json::text AS tools_json, "
                        + "params_json::text AS params_json, skills_json::text AS skills_json "
                        + "FROM agent_config WHERE id = ? AND version = ? AND status = 'PUBLISHED'",
                ref.agentConfigId(), ref.version());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agent_config #" + ref.agentConfigId() + "@" + ref.version() + " 不存在或未发布");
        }
        return rows.get(0);
    }

    private ModelConfig loadModelConfig(Map<String, Object> agentRow) {
        Object providerId = agentRow.get("model_provider_id");
        if (providerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agent_config 未绑定模型供应商");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, protocol, base_url, model, credential_cipher FROM model_provider_config WHERE id = ?",
                providerId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model_provider_config #" + providerId + " 不存在");
        }
        Map<String, Object> row = rows.get(0);
        // credential_cipher 是 BYTEA（decode(?, 'base64') 落库的二进制密文），
        // 读出 byte[] 需 Base64 编码回文本形态再走 CryptoUtil.decrypt
        Object cipherObj = row.get("credential_cipher");
        String cipher = cipherObj == null ? null
                : cipherObj instanceof byte[] b ? java.util.Base64.getEncoder().encodeToString(b)
                : cipherObj.toString();
        String apiKey = cipher == null ? null : cryptoUtil.decrypt(cipher);
        if (apiKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model_provider_config #" + providerId + " 凭证缺失或解密失败");
        }
        Map<String, Object> params = jsonMap(agentRow.get("params_json"));
        Map<String, Object> modelParams = new LinkedHashMap<>();
        for (String k : List.of("temperature", "maxTokens", "topP")) {
            if (params.containsKey(k)) {
                modelParams.put(k, params.get(k));
            }
        }
        return new ModelConfig(((Number) row.get("id")).longValue(),
                (String) row.get("protocol"), (String) row.get("base_url"),
                apiKey, (String) row.get("model"), modelParams);
    }

    /** 上游输入 → 用户消息文本（P0：整体 JSON 序列化） */
    private String toJsonText(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "(无输入)";
        }
        if (input.size() == 1 && input.get("text") instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            return String.valueOf(input);
        }
    }

    private Map<String, Object> jsonMap(Object jsonText) {
        if (jsonText == null || jsonText.toString().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(jsonText.toString(),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private record Ref(long agentConfigId, int version) {
    }
}
