package io.xuanji.plugin.basic;

import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.NodeCapability;
import io.xuanji.core.api.NodeContext;
import io.xuanji.core.api.NodeKind;
import io.xuanji.core.api.NodeMetadata;
import io.xuanji.core.api.NodeOutput;
import io.xuanji.core.api.WorkflowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 通知节点（内置示例）——演示「节点只面向抽象编程」：
 * P0 仅打日志；后续接入邮件/钉钉等真实通道时，通道作为独立连接器插件接入，
 * 本节点契约与核心代码均不变。
 */
@Component
public class NotifyNode implements WorkflowNode {

    public static final String ID = "basic.notify";
    private static final Logger log = LoggerFactory.getLogger(NotifyNode.class);

    @Override
    public NodeMetadata metadata() {
        return new NodeMetadata(
                ID, "通知", NodeKind.ATOMIC, "basic", "bell",
                "向指定目标发送通知消息（P0 原型：仅记录日志）",
                JsonSchema.anyObject(),
                JsonSchema.anyObject(),
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{"message":{"type":"string","description":"通知内容"}},
                         "required":["message"]}"""),
                Set.of(NodeCapability.SUPPORTS_RETRY, NodeCapability.IDEMPOTENT));
    }

    @Override
    public NodeOutput execute(NodeContext ctx) {
        String message = String.valueOf(ctx.params().getOrDefault("message", ""));
        log.info("[notify] run={} node={} message={}", ctx.runId(), ctx.nodeRunId(), message);
        return new NodeOutput(Map.of("sent", true), Map.of("channel", "log"));
    }
}
