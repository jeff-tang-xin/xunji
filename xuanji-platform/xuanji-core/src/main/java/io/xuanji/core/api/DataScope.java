package io.xuanji.core.api;

/**
 * 数据权限范围（决议 Q13）——角色级配置，决定该角色可见/可操作的数据行集。
 *
 * <p>过滤语义（按数据归属组织树与创建人裁剪）：</p>
 * <ul>
 *   <li>{@code ALL} —— 全部数据</li>
 *   <li>{@code UNDER_ORG} —— 本组织及全部下级组织的数据</li>
 *   <li>{@code ORG} —— 仅本组织的数据</li>
 *   <li>{@code ONLY_CREATE} —— 仅本人创建的数据（只能看自己建的）</li>
 * </ul>
 *
 * <p><b>平台管理员不受组织与数据权限控制</b>（bypass：全量可见可管，仅受菜单功能权限约束之外
 * 的审计记录约束）。作用面：管理台资产列表（工作流/Agent/连接器/发布中心）、run 列表、
 * 报表、工作区等一切按归属查询的数据。菜单可见性由角色-菜单绑定决定（RBAC），
 * 与数据范围正交——同一角色可配「看哪些菜单」与「看哪些数据」。</p>
 */
public enum DataScope {
    ALL, UNDER_ORG, ORG, ONLY_CREATE
}
