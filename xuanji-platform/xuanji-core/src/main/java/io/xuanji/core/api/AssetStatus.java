package io.xuanji.core.api;

/**
 * 资产生命周期状态机（主文档 v0.3 统一资产模型）。
 *
 * <pre>
 * DRAFT ──publish──▶ PUBLISHED ──deprecate──▶ DEPRECATED ──offline──▶ OFFLINE
 *   ▲                    │
 *   └──────revert────────┘（PUBLISHED 不可变，修订走新草稿版本）
 * </pre>
 *
 * <p>发布即版本固化：PUBLISHED 记录不可修改；跨资产引用一律钉住已发布版本
 * （{@link AssetRef}）。状态迁移合法性由 {@link #canTransitionTo} 校验，
 * 非法迁移抛 IllegalStateException——把业务不变量编码进类型。</p>
 */
public enum AssetStatus {
    DRAFT, PUBLISHED, DEPRECATED, OFFLINE;

    /** 状态机合法迁移表 */
    public boolean canTransitionTo(AssetStatus target) {
        return switch (this) {
            case DRAFT -> target == PUBLISHED;
            case PUBLISHED -> target == DEPRECATED;
            case DEPRECATED -> target == OFFLINE;
            case OFFLINE -> false;
        };
    }
}
