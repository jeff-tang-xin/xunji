package io.xuanji.server.auth;

import java.util.List;

/**
 * 数据权限四档 → SQL 过滤片段（主文档 v0.9 Q13：过滤统一查询层 org_id + created_by）。
 *
 * <p>平台管理员 bypass 全量；档位语义：
 * ALL=全部 / UNDER_ORG=本组织及下级（sys_org.path 物化路径前缀匹配）/ ORG=仅本组织 /
 * ONLY_CREATE=仅本人创建。用户无组织时 UNDER_ORG/ORG 自然为空集（SQL 语义保证）。</p>
 *
 * <p>用法：{@code var c = DataScopeFilter.of(user, "org_id", "created_by");}
 * 然后 {@code "... WHERE " + c.sql()} 绑定 {@code c.args()}。</p>
 */
public final class DataScopeFilter {

    /** 过滤片段 + 绑定参数（顺序对应 sql 中的 ?） */
    public record Clause(String sql, List<Object> args) {
        public static final Clause TRUE = new Clause("TRUE", List.of());
    }

    private DataScopeFilter() {
    }

    public static Clause of(LoginUser user, String orgCol, String createdByCol) {
        if (user.isPlatformAdmin() || "ALL".equals(user.dataScope())) {
            return Clause.TRUE;
        }
        return switch (user.dataScope() == null ? "ONLY_CREATE" : user.dataScope()) {
            case "UNDER_ORG" -> new Clause(
                    orgCol + " IN (SELECT id FROM sys_org WHERE path LIKE "
                            + "(SELECT path || '%' FROM sys_org WHERE id = ?))",
                    List.of(user.orgId()));
            case "ORG" -> new Clause(orgCol + " = ?", List.of(user.orgId()));
            default -> new Clause(createdByCol + " = ?", List.of(user.username()));
        };
    }
}
