package io.xuanji.core.api;

/**
 * 资产类型——「一切皆资产皆可发布」的五类统一模型。
 *
 * <p>五类资产共享同一生命周期（草稿→发布→弃用→下线）与发布语义（版本固化、
 * 引用钉住），因此以同一 SPI（{@link AssetPublisher}）多态处理，
 * 发布中心视图与发布流水线对资产类型无感知。</p>
 */
public enum AssetType {
    WORKFLOW, AGENT, SKILL, MCP, TOOL
}
