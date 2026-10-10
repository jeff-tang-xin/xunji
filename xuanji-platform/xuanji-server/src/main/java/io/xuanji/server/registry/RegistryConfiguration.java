package io.xuanji.server.registry;

import io.xuanji.core.api.Connector;
import io.xuanji.core.api.Tool;
import io.xuanji.core.api.WebhookParser;
import io.xuanji.core.api.WorkflowNode;
import io.xuanji.core.registry.ComponentRegistry;
import io.xuanji.core.registry.RegisteredComponent;
import io.xuanji.core.registry.SimpleComponentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 组件注册表装配——「编译期插拔」的运行时落地（docs/detail/02 §6 扫描登记行为）。
 *
 * <p>启动时扫描 Spring 容器：</p>
 * <ul>
 *   <li>{@link WorkflowNode} bean —— 逐个登记（画布节点）</li>
 *   <li>{@link Connector} bean —— 聚合展开：其 nodes() 全部登记，
 *       webhookParsers() 计入启动摘要（解析器登记随触发器阶段落地）</li>
 * </ul>
 *
 * <p>插件方零注册代码：加 Maven 依赖 + @Component，启动即出现在画布面板。</p>
 */
@Configuration
public class RegistryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RegistryConfiguration.class);

    @Bean
    public ComponentRegistry componentRegistry() {
        return new SimpleComponentRegistry();
    }

    @Bean
    public ApplicationRunner registryBootstrap(ApplicationContext ctx, ComponentRegistry registry) {
        return (ApplicationArguments args) -> {
            // 1) 独立节点 bean
            Map<String, WorkflowNode> nodes = ctx.getBeansOfType(WorkflowNode.class);
            nodes.forEach((beanName, node) -> {
                NodeMetadataHolder holder = NodeMetadataHolder.of(node, sourceModuleOf(node.getClass()));
                registry.register(new RegisteredComponent(
                        holder.metadata().id(), holder.metadata(), holder.pluginModule(), true));
            });

            // 2) 连接器聚合：nodes() 展开 + webhookParsers() 计数
            Map<String, Connector> connectors = ctx.getBeansOfType(Connector.class);
            int parserCount = 0;
            for (var entry : connectors.entrySet()) {
                Connector connector = entry.getValue();
                String module = sourceModuleOf(connector.getClass());
                for (WorkflowNode node : connector.nodes()) {
                    registry.register(new RegisteredComponent(
                            node.metadata().id(), node.metadata(), module, true));
                }
                parserCount += connector.webhookParsers().size();
                log.info("connector registered: id={} nodes={} parsers={}",
                        connector.metadata().id(), connector.nodes().size(), connector.webhookParsers().size());
            }

            // 3) 工具 bean（Tool SPI）：包成 ToolNodeAdapter 登记为画布工具节点（「工具即节点」）
            Map<String, Tool> tools = ctx.getBeansOfType(Tool.class);
            tools.forEach((beanName, tool) -> {
                ToolNodeAdapter adapter = new ToolNodeAdapter(tool);
                registry.register(new RegisteredComponent(
                        adapter.metadata().id(), adapter.metadata(), sourceModuleOf(tool.getClass()), true));
            });

            // 4) 启动摘要（面板分组视图）
            var byCategory = ((SimpleComponentRegistry) registry).groupByCategory();
            log.info("component registry ready: {} nodes, {} connectors, {} webhook parsers",
                    registry.listEnabled().size(), connectors.size(), parserCount);
            byCategory.forEach((category, list) -> log.info("  panel [{}]: {}",
                    category, list.stream().map(c -> c.metadata().id()).toList()));
        };
    }

    /**
     * P0 简化：来源模块按实现类包名推断（io.xuanji.plugin.* → 对应插件）。
     * 后续可从 classloader code source（jar 名）精确取 artifactId。
     */
    private static String sourceModuleOf(Class<?> type) {
        String pkg = type.getPackageName();
        if (pkg.startsWith("io.xuanji.plugin.")) {
            return pkg.split("\\.")[3];
        }
        return "core-builtin";
    }

    /** 内部载体：避免在 lambda 里重复求值 metadata（record 不可变，仅组织代码） */
    private record NodeMetadataHolder(io.xuanji.core.api.NodeMetadata metadata, String pluginModule) {
        static NodeMetadataHolder of(WorkflowNode node, String pluginModule) {
            return new NodeMetadataHolder(node.metadata(), pluginModule);
        }
    }
}
