package io.xuanji.server.auth;

import java.util.Set;

/**
 * 登录会话主体（管理台 RBAC，主文档 v0.9 Q13）。
 *
 * <p>menuCodes 为登录时快照——角色/菜单分配变更后重新登录生效（管理台常规语义）。
 * dataScope 为数据权限四档（ALL/UNDER_ORG/ORG/ONLY_CREATE），平台管理员 bypass：
 * 查询过滤层（org_id + created_by）在资产 CRUD 接入时统一实装。</p>
 */
public record LoginUser(
        Long userId,
        String username,
        String displayName,
        Long orgId,
        boolean isPlatformAdmin,
        boolean mustChangePassword,
        String dataScope,
        Set<String> menuCodes) {

    /** request attribute 名（AuthInterceptor 注入，Controller @RequestAttribute 取） */
    public static final String ATTR = AuthInterceptor.ATTR_LOGIN_USER;
}
