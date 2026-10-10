package io.xuanji.core.api;

/**
 * Webhook 事件解析 SPI——外部平台事件 → 平台标准事件的适配点。
 *
 * <p>外部平台接入双轨之一（专用连接器轨）：连接器实现本接口把自家 Webhook
 * 翻译成 {@link ParsedEvent}；通用 MCP 轨见主文档 Q6 决议。
 * 返回 null 表示该请求与本连接器无关（平台继续分发给其他解析器）。</p>
 */
public interface WebhookParser {

    /** 所属连接器 id（如 jira），平台按此路由 webhook 端点 */
    String connectorId();

    /**
     * 解析外部请求。
     *
     * @return 平台标准事件；null 表示与本连接器无关
     * @throws ConnectorException 签名校验失败等解析期错误（AUTH_FAILED）
     */
    ParsedEvent parse(WebhookRequest request);
}
