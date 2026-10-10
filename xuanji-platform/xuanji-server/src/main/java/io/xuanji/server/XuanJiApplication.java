package io.xuanji.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 玄机万象平台启动入口。
 *
 * <p>组装点语义：组件扫描覆盖 io.xuanji 全部包——core 的抽象、plugins 的节点实现、
 * agent-scope 的适配实现在此汇合。插件只需在类上标 {@code @Component} 且被本工程
 * 依赖，即被扫描登记（默认扫描仅覆盖主类所在包，会漏掉 io.xuanji.plugin.*）。</p>
 */
@SpringBootApplication(scanBasePackages = "io.xuanji")
public class XuanJiApplication {

    public static void main(String[] args) {
        SpringApplication.run(XuanJiApplication.class, args);
    }
}
