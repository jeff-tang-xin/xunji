package io.xuanji.core.api;

/**
 * 资产发布 SPI——五类资产各一个实现（core 内置注册，server 编排发布流水线）。
 *
 * <p>发布流水线 7 步（定义冻结 → 引用钉住校验 → schema 校验 → 凭据检查 →
 * 版本固化 → 注册表刷新 → 审计）由 server 统一编排；本 SPI 只封装
 * 「某类资产如何从草稿固化为一个不可变发布版本」的类型特定逻辑——
 * 多态分派，发布流水线对资产类型无感知（开闭原则）。</p>
 */
public interface AssetPublisher {

    /** 本实现负责的资产类型 */
    AssetType assetType();

    /**
     * 发布前校验（引用钉住完整性、定义合法性、能力覆盖等）。
     *
     * @param draftId 草稿资产 id
     * @return 校验发现的问题清单；空 = 通过
     */
    java.util.List<String> validate(String draftId);

    /**
     * 执行版本固化：草稿 → 新的不可变发布版本。
     *
     * @param draftId 草稿资产 id
     * @return 钉住引用（含新版本号）
     */
    AssetRef publish(String draftId);
}
