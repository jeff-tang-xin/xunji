package io.xuanji.agent.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.xuanji.core.runtime.ToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具（{@link ToolDef} sourceType=MCP 的 AgentScope 适配）：把某个已 discover 的
 * MCP server 工具包装成 {@link AgentTool}——LLM 经 ReAct 循环发起 tool_calls，本类按
 * MCP 协议（streamable HTTP JSON-RPC）发 {@code tools/call} 并回填 {@link ToolResultBlock}。
 *
 * <p>sourceRef 约定：{@code "endpoint|toolName"}（endpoint=MCP server URL，
 * toolName=tools/list 发现的原始工具名，由 AgentNodeExecutor.resolveMcpTools 装配）。</p>
 *
 * <p>P0 简化：每次调用做完整握手 initialize → notifications/initialized → tools/call，
 * 兼容无状态 streamable HTTP server（不维护 Mcp-Session-Id）；响应兼容 application/json
 * 与 text/event-stream 两种形态。有状态 server（要求 session 头）随深化增补。</p>
 */
class McpAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(McpAgentTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final ToolDef def;
    private final String endpoint;
    private final String toolName;

    McpAgentTool(ToolDef def) {
        this.def = def;
        String ref = def.sourceRef() == null ? "" : def.sourceRef();
        int bar = ref.lastIndexOf('|');
        if (bar <= 0 || bar >= ref.length() - 1) {
            throw new IllegalArgumentException(
                    "MCP sourceRef 必须形如 \"endpoint|toolName\"，实际: " + def.sourceRef());
        }
        this.endpoint = ref.substring(0, bar);
        this.toolName = ref.substring(bar + 1);
    }

    @Override
    public String getName() {
        String name = def.name();
        // LLM function-calling 函数名硬约束 ^[a-zA-Z0-9_-]+$（中文名被上游 400 InvalidParameter——
        // ToolAgentAdapter 实测踩坑），非法名 sanitize 成下划线
        return name.matches("[a-zA-Z0-9_-]+") ? name : name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public String getDescription() {
        return def.description() == null ? def.name() : def.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        return def.parameters();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
                    rpc(initializeBody());
                    rpc(initializedNotif());
                    String raw = rpc(callToolBody(param.getInput()));
                    return parseResult(raw);
                })
                .onErrorResume(e -> {
                    log.warn("mcp tool {} call failed: {}", def.name(), e.toString());
                    return Mono.just(ToolResultBlock.error(def.name(), rootMessage(e)));
                });
    }

    // ---------- MCP JSON-RPC ----------

    private String initializeBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"xuanji\",\"version\":\"0.1.0\"}}}";
    }

    private String initializedNotif() {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    }

    private String callToolBody(Map<String, Object> args) throws Exception {
        Map<String, Object> callParams = new LinkedHashMap<>();
        callParams.put("name", toolName);
        callParams.put("arguments", args == null ? Map.of() : args);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", 3);
        req.put("method", "tools/call");
        req.put("params", callParams);
        return MAPPER.writeValueAsString(req);
    }

    /** 单次 JSON-RPC 调用；兼容 application/json 与 text/event-stream（data: 行）两种响应 */
    private String rpc(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("MCP HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
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
            throw new IllegalStateException("MCP SSE 响应中无 JSON-RPC 结果");
        }
        return resp.body();
    }

    @SuppressWarnings("unchecked")
    private ToolResultBlock parseResult(String raw) throws Exception {
        Map<String, Object> resp = MAPPER.readValue(raw, Map.class);
        if (resp.get("error") != null) {
            return ToolResultBlock.error(def.name(), truncate(String.valueOf(resp.get("error"))));
        }
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        if (result == null) {
            return ToolResultBlock.error(def.name(), "MCP tools/call 无 result: " + truncate(raw));
        }
        // MCP 约定 result.content = [{type:"text", text:"..."}]
        StringBuilder sb = new StringBuilder();
        if (result.get("content") instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> cm && cm.get("text") != null) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(cm.get("text"));
                }
            }
        }
        String out = sb.length() > 0 ? sb.toString() : MAPPER.writeValueAsString(result);
        boolean isError = Boolean.TRUE.equals(result.get("isError"));
        return isError ? ToolResultBlock.error(def.name(), truncate(out))
                : ToolResultBlock.text(truncate(out));
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 4000 ? s : s.substring(0, 4000) + "...(truncated)";
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.toString() : cur.getMessage();
    }
}