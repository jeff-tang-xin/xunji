package io.xuanji.plugin.basic;

import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.Tool;
import io.xuanji.core.api.ToolMetadata;
import io.xuanji.core.api.ToolRequest;
import io.xuanji.core.api.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通知工具（内置示例）——演示「工具只面向抽象编程」：
 * P0 仅打日志；后续接入邮件/钉钉等真实通道时，通道作为独立连接器插件接入，
 * 本工具契约与核心代码均不变。
 *
 * <p>以 Tool SPI 实现（{@code @Component} 由 RegistryConfiguration 自动扫描），
 * 画布 refId = {@code tool.notify}，同时作为 Agent 可调用函数。</p>
 */
@Component
public class NotifyNode implements Tool {

    public static final String ID = "notify";

    private static final Logger log = LoggerFactory.getLogger(NotifyNode.class);

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ID, "通知",
                "向指定目标发送通知消息（P0 原型：仅记录日志）",
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{"message":{"type":"string","description":"通知内容"}},
                         "required":["message"]}"""),
                JsonSchema.anyObject());
    }

    @Override
    public ToolResult invoke(ToolRequest request) {
        String message = String.valueOf(request.params().getOrDefault("message", ""));
        log.info("[notify] message={}", message);
        return ToolResult.of(Map.of("sent", true, "channel", "log"));
    }
}
