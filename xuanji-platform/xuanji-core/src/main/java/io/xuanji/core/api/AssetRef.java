package io.xuanji.core.api;

/**
 * 资产引用（版本钉住）。
 *
 * <p>跨资产引用（工作流引用 Agent、报表引用工作流、App 绑定资产）一律指向
 * 「类型 + id + 已发布版本号」——被引用资产发布新版本不影响既有引用，
 * 升级引用是显式动作。这是「发布即可被消费」的隔离基础。</p>
 *
 * @param type    资产类型
 * @param id      资产 id
 * @param version 钉住的已发布版本号（如 3）
 */
public record AssetRef(AssetType type, String id, int version) {

    public AssetRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("asset id must not be blank");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("asset version must be positive: " + version);
        }
    }

    @Override
    public String toString() {
        return type.name().toLowerCase() + ":" + id + "@v" + version;
    }
}
