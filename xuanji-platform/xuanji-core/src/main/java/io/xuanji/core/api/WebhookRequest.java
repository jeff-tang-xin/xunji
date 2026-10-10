package io.xuanji.core.api;

import java.util.Map;

/**
 * 入站 Webhook 请求的最小抽象（与具体 Servlet/WebFlux 解耦）。
 *
 * @param headers 请求头（小写 key）
 * @param query   查询参数
 * @param body    原始请求体（UTF-8 文本；签名校验所需原文由解析器自行处理）
 */
public record WebhookRequest(Map<String, String> headers, Map<String, String> query, String body) {

    public WebhookRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        query = query == null ? Map.of() : Map.copyOf(query);
    }
}
