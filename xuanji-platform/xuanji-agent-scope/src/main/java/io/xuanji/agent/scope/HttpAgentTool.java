package io.xuanji.agent.scope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.xuanji.core.runtime.ToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OPENAPI 形态工具（tool_registry.source_type=OPENAPI 的 AgentScope 适配）：
 * 把 HTTP 端点包装为 {@link AgentTool}——LLM 经 ReAct 循环发起 tool_calls，
 * 本类按 source_ref 约定发真实 HTTP 请求并回填 ToolResultBlock。
 *
 * <p>source_ref 约定：{@code "METHOD URL"}（空格分隔），如
 * {@code "GET https://api.example.com/weather"}。POST/PUT/DELETE 以入参 JSON
 * 为请求体；GET/HEAD 以入参展开为 query string。响应体文本即工具结果；
 * 非 2xx 回填 error 形态（ReAct 循环可见，LLM 可据此重试或改答）。</p>
 *
 * <p>P0 范围：无鉴权头/超时配置化/BEAN/SCRIPT 形态随工具集成深化增补。</p>
 */
class HttpAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentTool.class);

    private final ToolDef def;
    private final HttpClient http;
    private final String method;
    private final String url;

    HttpAgentTool(ToolDef def) {
        this.def = def;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String ref = def.sourceRef() == null ? "" : def.sourceRef().trim();
        int sp = ref.indexOf(' ');
        String maybeMethod = sp > 0 ? ref.substring(0, sp).trim().toUpperCase() : "";
        if (sp > 0 && isHttpMethod(maybeMethod)) {
            this.method = maybeMethod;
            this.url = ref.substring(sp + 1).trim();
        } else {
            // 容错：未写 METHOD 前缀（直接填 URL 很常见）→ 默认 GET，
            // 避免装配期抛异常拖垮整个 run；如需 POST/PUT 等在 source_ref 前加 "POST " 等前缀
            this.method = "GET";
            this.url = ref;
            log.warn("OPENAPI 工具 {} 的 source_ref 未含 METHOD 前缀，按 GET 处理: {}", def.name(), def.sourceRef());
        }
    }

    private static boolean isHttpMethod(String m) {
        return switch (m) {
            case "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS" -> true;
            default -> false;
        };
    }

    @Override
    public String getName() {
        return def.name();
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
        return Mono.fromCallable(() -> invoke(param.getInput()))
                .onErrorResume(e -> {
                    log.warn("tool {} invoke failed: {}", def.name(), e.toString());
                    return Mono.just(ToolResultBlock.error(def.name(), rootMessage(e)));
                });
    }

    private ToolResultBlock invoke(Map<String, Object> input) throws Exception {
        HttpRequest request;
        if ("GET".equals(method) || "HEAD".equals(method)) {
            String qs = input == null || input.isEmpty() ? "" : "?" + input.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            request = HttpRequest.newBuilder().uri(URI.create(url + qs))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
        } else {
            String body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(input == null ? Map.of() : input);
            request = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        }
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            return ToolResultBlock.error(def.name(),
                    "HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        // text() 工厂：id/name 由 ReAct 循环按 ToolUseBlock 回填，避免手拼构造器参数序
        return ToolResultBlock.text(truncate(resp.body()));
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
