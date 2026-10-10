package io.xuanji.core.api;

/**
 * 平台级错误码常量。插件可扩展自有前缀的错误码（如 jira.rate-limit），
 * 但以下平台保留码具有引擎语义，引擎按其驱动状态机。
 */
public final class ErrorCode {

    /** 取消/超时（引擎置位取消令牌后节点应返回此码） */
    public static final String CANCELLED = "CANCELLED";
    /** 连接外部系统超时（通常可重试） */
    public static final String CONN_TIMEOUT = "CONN_TIMEOUT";
    /** 认证/授权失败（通常不可重试，需人工修凭据） */
    public static final String AUTH_FAILED = "AUTH_FAILED";
    /** 输入/输出/配置不符合 schema */
    public static final String SCHEMA_VIOLATION = "SCHEMA_VIOLATION";
    /** 外部系统返回业务性失败（如 Jira 400） */
    public static final String REMOTE_REJECTED = "REMOTE_REJECTED";
    /** 未分类内部错误 */
    public static final String INTERNAL = "INTERNAL";

    private ErrorCode() {
    }
}
