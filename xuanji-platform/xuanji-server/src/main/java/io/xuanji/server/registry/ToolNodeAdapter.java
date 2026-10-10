package io.xuanji.server.registry;

import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.NodeCapability;
import io.xuanji.core.api.NodeContext;
import io.xuanji.core.api.NodeExecutionException;
import io.xuanji.core.api.NodeKind;
import io.xuanji.core.api.NodeMetadata;
import io.xuanji.core.api.NodeOutput;
import io.xuanji.core.api.Tool;
import io.xuanji.core.api.ToolMetadata;
import io.xuanji.core.api.ToolRequest;
import io.xuanji.core.api.WorkflowNode;

import java.util.Set;

/**
 * 工具 → 画布节点适配器（「工具即节点」机制核心，docs/detail/02 §2）。
 *
 * <p>把 {@link Tool}（平台自定义能力 SPI）包装成 {@link WorkflowNode}，从而复用
 * 既有整条节点链路——组件注册表登记 → /api/components 暴露 → 画布面板渲染 →
 * NodeDispatchComponent 调度执行。插件方只实现 Tool，无需写画布节点。</p>
 *
 * <p>refId = {@code tool.{id}}（{@link ToolMetadata#refId()}），与插件节点
 * （{@code {plugin}.{name}}）命名空间隔离，避免撞名。</p>
 */
public class ToolNodeAdapter implements WorkflowNode {

    private final Tool tool;
    private final NodeMetadata metadata;

    public ToolNodeAdapter(Tool tool) {
        this.tool = tool;
        ToolMetadata tm = tool.metadata();
        this.metadata = new NodeMetadata(
                tm.refId(),                    // tool.{id}
                tm.name(),                     // 显示名
                NodeKind.ATOMIC,               // 工具节点 = 原子操作
                "tool",                        // 面板分组（前端按 category 渲染成「工具」组）
                "tool",                        // 图标 key
                tm.description(),
                JsonSchema.anyObject(),        // inputSchema：上游数据宽松（工具参数经 paramSchema 配置）
                tm.outputSchema(),             // 输出声明（下游兼容性校验依据）
                tm.parameters(),               // paramSchema：画布属性抽屉动态表单 + Agent 函数签名共用
                Set.of()                       // 能力（P0 工具无决策门/重试声明）
        );
    }

    /** 暴露原始 Tool（Agent 侧适配器复用同一实例；调度层也可能直接调用） */
    public Tool tool() {
        return tool;
    }

    @Override
    public NodeMetadata metadata() {
        return metadata;
    }

    @Override
    public NodeOutput execute(NodeContext ctx) throws NodeExecutionException {
        // 画布场景：input = 上游 mapping 数据，params = 属性抽屉配置
        var result = tool.invoke(ToolRequest.of(ctx.input(), ctx.params()));
        return NodeOutput.of(result.output());
    }
}