package io.xuanji.server.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.xuanji.server.auth.DataScopeFilter;
import io.xuanji.server.auth.LoginUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产统一 CRUD（阶段 2：agent / tool / skill / mcp / model-provider）。
 *
 * <p>五类资产共用一张规格表驱动：列白名单 + JSON 列标记，list 走数据权限过滤
 * （DataScopeFilter），create 填 org_id/created_by，update/delete 校验归属
 * （管理员或本人），publish 做 version+1 + 状态推进（完整发布流水线随阶段 3）。</p>
 *
 * <p>附加能力：MCP 工具发现（streamable HTTP JSON-RPC：initialize → tools/list）、
 * 模型供应商连通性测试（OPENAI_PROTOCOL GET /models；ANTHROPIC_PROTOCOL 最小 messages 请求）。</p>
 */
@Service
public class AssetService {

    /** 列定义：json=true 落库时加 ::jsonb 转换 */
    private record Col(String name, boolean json) {
        static Col of(String n) { return new Col(n, false); }
        static Col json(String n) { return new Col(n, true); }
    }

    private record AssetSpec(String table, List<Col> cols, boolean credential) {
        AssetSpec(String table, List<Col> cols) { this(table, cols, false); }
    }

    private static final Map<String, AssetSpec> SPECS = Map.of(
            "agents", new AssetSpec("agent_config", List.of(
                    Col.of("name"), Col.of("description"), Col.of("model_provider_id"),
                    Col.of("system_prompt"), Col.json("tools_json"), Col.json("permission_json"),
                    Col.json("params_json"), Col.json("skills_json"))),
            "tools", new AssetSpec("tool_registry", List.of(
                    Col.of("name"), Col.of("description"), Col.of("source_type"),
                    Col.of("source_ref"), Col.json("input_schema"), Col.json("output_schema"))),
            "skills", new AssetSpec("skill_repo", List.of(
                    Col.of("name"), Col.of("description"), Col.of("content_uri"), Col.of("content_md"))),
            "mcp", new AssetSpec("mcp_config", List.of(
                    Col.of("name"), Col.of("endpoint"), Col.json("discovered_tools_json"))),
            "model-providers", new AssetSpec("model_provider_config", List.of(
                    Col.of("name"), Col.of("protocol"), Col.of("base_url"), Col.of("model"),
                    Col.json("params_json")), true));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CryptoUtil crypto;

    public AssetService(JdbcTemplate jdbc, ObjectMapper objectMapper, CryptoUtil crypto) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.crypto = crypto;
    }

    private AssetSpec spec(String asset) {
        AssetSpec s = SPECS.get(asset);
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown asset: " + asset);
        }
        return s;
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "仅资产创建者或平台管理员可操作");
    }

    // ---------- 查询 ----------

    /**
     * JSONB 列归一化：JdbcTemplate 对 JSONB 列返回驱动对象（PGobject），直接交给 Jackson
     * 会序列化成 {@code {"type":"jsonb","value":"...","null":false}} 坏结构——前端拿到的是
     * 对象而非数组，任何 {@code .filter}/{@code .map} 都会抛 TypeError（表现为编辑抽屉打不开）。
     * 这里统一解析回 List/Map，使接口返回真实的 JSON 结构。
     */
    private List<Map<String, Object>> normalizeJson(AssetSpec s, List<Map<String, Object>> rows) {
        List<String> jsonCols = s.cols().stream().filter(Col::json).map(Col::name).toList();
        if (jsonCols.isEmpty()) {
            return rows;
        }
        for (Map<String, Object> row : rows) {
            for (String col : jsonCols) {
                Object v = row.get(col);
                if (v == null) {
                    continue;
                }
                String text = v instanceof String str ? str : v.toString();
                try {
                    row.put(col, objectMapper.readValue(text, Object.class));
                } catch (Exception e) {
                    // 库内存量非法 JSON：退回原文，不让整页查询失败
                    row.put(col, text);
                }
            }
        }
        return rows;
    }

    public List<Map<String, Object>> list(String asset, LoginUser user) {
        AssetSpec s = spec(asset);
        DataScopeFilter.Clause c = DataScopeFilter.of(user, "org_id", "created_by");
        List<String> selectCols = new ArrayList<>(List.of("id", "version", "status", "org_id", "created_by", "updated_at"));
        s.cols().forEach(col -> selectCols.add(col.name()));
        if (s.credential()) {
            selectCols.add("(credential_cipher IS NOT NULL) AS has_credential");
        }
        return normalizeJson(s, jdbc.queryForList(
                "SELECT " + String.join(", ", selectCols) + " FROM " + s.table()
                        + " WHERE " + c.sql() + " ORDER BY id",
                c.args().toArray()));
    }

    public Map<String, Object> get(String asset, long id, LoginUser user) {
        AssetSpec s = spec(asset);
        Map<String, Object> row = fetchRow(s, id);
        requireOwner(row, user);
        return row;
    }

    // ---------- 写入 ----------

    public long create(String asset, Map<String, Object> body, LoginUser user) {
        AssetSpec s = spec(asset);
        Object name = body.get("name");
        if (name == null || name.toString().isBlank()) {
            throw bad("name is required");
        }
        List<String> cols = new ArrayList<>(List.of("org_id", "created_by", "updated_by"));
        List<Object> vals = new ArrayList<>();
        vals.add(body.get("orgId")); // 可空：未指定组织则留空（ONLY_CREATE 用户仍可见自己创建的）
        vals.add(user.username());
        vals.add(user.username());
        List<String> marks = new ArrayList<>(List.of("?", "?", "?"));
        appendBodyCols(s, body, cols, vals, marks);
        if (s.credential() && body.get("credential") != null) {
            cols.add("credential_cipher");
            vals.add(crypto.encrypt(body.get("credential").toString()));
            marks.add("decode(?, 'base64')"); // Base64 字符串 → bytea
        }
        Long id = jdbc.queryForObject(
                "INSERT INTO " + s.table() + " (" + String.join(", ", cols) + ") "
                        + "VALUES (" + String.join(", ", marks) + ") RETURNING id",
                Long.class, vals.toArray());
        return id;
    }

    public void update(String asset, long id, Map<String, Object> body, LoginUser user) {
        AssetSpec s = spec(asset);
        requireOwner(fetchRow(s, id), user);
        List<String> sets = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        appendBodySets(s, body, sets, vals);
        if (sets.isEmpty()) {
            throw bad("no updatable fields");
        }
        if (s.credential() && body.get("credential") != null) {
            sets.add("credential_cipher = decode(?, 'base64')");
            vals.add(crypto.encrypt(body.get("credential").toString()));
        }
        sets.add("updated_by = ?");
        vals.add(user.username());
        vals.add(id);
        jdbc.update("UPDATE " + s.table() + " SET " + String.join(", ", sets) + " WHERE id = ?", vals.toArray());
    }

    public void delete(String asset, long id, LoginUser user) {
        AssetSpec s = spec(asset);
        Map<String, Object> row = fetchRow(s, id);
        requireOwner(row, user);
        if (!"DRAFT".equals(row.get("status"))) {
            throw bad("仅 DRAFT 资产可删除（先下线再删）");
        }
        jdbc.update("DELETE FROM " + s.table() + " WHERE id = ?", id);
    }

    /** 发布：version+1 + 状态推进（完整发布流水线随阶段 3：引用校验/审批/快照） */
    public Map<String, Object> publish(String asset, long id, LoginUser user) {
        AssetSpec s = spec(asset);
        requireOwner(fetchRow(s, id), user);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "UPDATE " + s.table() + " SET version = version + 1, status = 'PUBLISHED', updated_by = ?, "
                        + "updated_at = now() WHERE id = ? RETURNING id, version",
                user.username(), id);
        return rows.get(0);
    }

    // ---------- 归属校验 ----------

    private Map<String, Object> fetchRow(AssetSpec s, long id) {
        List<String> selectCols = new ArrayList<>(List.of("id", "version", "status", "org_id", "created_by", "updated_at"));
        s.cols().forEach(col -> selectCols.add(col.name()));
        if (s.credential()) {
            selectCols.add("(credential_cipher IS NOT NULL) AS has_credential");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + String.join(", ", selectCols) + " FROM " + s.table() + " WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, s.table() + " #" + id + " 不存在");
        }
        return normalizeJson(s, rows).get(0);
    }

    private void requireOwner(Map<String, Object> row, LoginUser user) {
        if (user.isPlatformAdmin()) {
            return;
        }
        Object createdBy = row.get("created_by");
        if (createdBy == null || !createdBy.equals(user.username())) {
            throw forbidden();
        }
    }

    /** 公开归属校验（供 discoverMcp/testModelProvider 等操作端点复用）：资产不存在或非本人/管理员抛异常。 */
    public void requireOwner(String asset, long id, LoginUser user) {
        requireOwner(fetchRow(spec(asset), id), user);
    }

    // ---------- body 列装配 ----------

    private void appendBodyCols(AssetSpec s, Map<String, Object> body,
                                List<String> cols, List<Object> vals, List<String> marks) {
        for (Col col : s.cols()) {
            if (!body.containsKey(col.name())) {
                continue;
            }
            cols.add(col.name());
            marks.add(col.json() ? "?::jsonb" : "?");
            vals.add(col.json() ? toJson(body.get(col.name())) : body.get(col.name()));
        }
    }

    private void appendBodySets(AssetSpec s, Map<String, Object> body, List<String> sets, List<Object> vals) {
        for (Col col : s.cols()) {
            if (!body.containsKey(col.name())) {
                continue;
            }
            sets.add(col.name() + " = " + (col.json() ? "?::jsonb" : "?"));
            vals.add(col.json() ? toJson(body.get(col.name())) : body.get(col.name()));
        }
    }

    private String toJson(Object v) {
        if (v instanceof String str) {
            return str; // 前端直接传 JSON 字符串
        }
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            throw bad("invalid json field: " + e.getMessage());
        }
    }

    // ---------- MCP 工具发现（streamable HTTP JSON-RPC） ----------

    public Map<String, Object> discoverMcp(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT endpoint FROM mcp_config WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "mcp_config #" + id + " 不存在");
        }
        String endpoint = (String) rows.get(0).get("endpoint");
        List<Map<String, Object>> tools = mcpToolsList(endpoint);
        jdbc.update("UPDATE mcp_config SET discovered_tools_json = ?::jsonb, updated_at = now() WHERE id = ?",
                toJson(tools), id);
        return Map.of("tools", tools);
    }

    private List<Map<String, Object>> mcpToolsList(String endpoint) {
        try {
            rpc(endpoint, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                    + "\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"xuanji\",\"version\":\"0.1.0\"}}}");
            rpc(endpoint, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            String raw = rpc(endpoint, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            Map<?, ?> resp = objectMapper.readValue(raw, Map.class);
            Map<?, ?> result = (Map<?, ?>) resp.get("result");
            if (result == null) {
                throw bad("MCP tools/list 无 result: " + raw);
            }
            List<Map<String, Object>> tools = new ArrayList<>();
            Object toolsRaw = result.get("tools");
            for (Object t : (toolsRaw instanceof List<?> list) ? list : List.<Object>of()) {
                Map<?, ?> m = (Map<?, ?>) t;
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", m.get("name"));
                tool.put("description", m.get("description"));
                tool.put("inputSchema", m.get("inputSchema"));
                tools.add(tool);
            }
            return tools;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "MCP 连接失败（" + endpoint + "）: " + e.getMessage());
        }
    }

    /** 单次 JSON-RPC 调用；兼容 application/json 与 text/event-stream（data: 行）两种响应 */
    private String rpc(String endpoint, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw bad("MCP HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/event-stream")) {
            for (String line : resp.body().split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) {
                    String data = trimmed.substring(5).trim();
                    if (data.contains("\"result\"") || data.contains("\"error\"")) {
                        return data;
                    }
                }
            }
            throw bad("MCP SSE 响应中无 JSON-RPC 结果");
        }
        return resp.body();
    }

    private String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 200));
    }

    // ---------- 模型供应商连通性测试 ----------

    public Map<String, Object> testModelProvider(long id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT protocol, base_url, model, credential_cipher FROM model_provider_config WHERE id = ?", id);
        String protocol = (String) row.get("protocol");
        String baseUrl = trimSlash((String) row.get("base_url"));
        String model = (String) row.get("model");
        String cipher = row.get("credential_cipher") == null ? null
                : row.get("credential_cipher") instanceof byte[] b
                ? java.util.Base64.getEncoder().encodeToString(b)
                : row.get("credential_cipher").toString();
        if (cipher == null) {
            throw bad("未配置 api_key");
        }
        String apiKey = crypto.decrypt(cipher);
        if (apiKey == null) {
            throw bad("api_key 解密失败（加密密钥是否变更？）");
        }
        long start = System.currentTimeMillis();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req;
            if ("ANTHROPIC_PROTOCOL".equals(protocol)) {
                req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"" + model + "\",\"max_tokens\":1,"
                                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                        .build();
            } else {
                req = HttpRequest.newBuilder(URI.create(baseUrl + "/models"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + apiKey)
                        .GET().build();
            }
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            return Map.of("ok", ok, "httpStatus", resp.statusCode(), "latencyMs", latency,
                    "detail", ok ? "连通正常" : truncate(resp.body()));
        } catch (Exception e) {
            return Map.of("ok", false, "latencyMs", System.currentTimeMillis() - start,
                    "detail", "连接失败: " + e.getMessage());
        }
    }

    private String trimSlash(String s) {
        return s == null ? "" : (s.endsWith("/") ? s.substring(0, s.length() - 1) : s);
    }
}
