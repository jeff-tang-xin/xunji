package io.xuanji.core.api;

/**
 * 连接器平台适配错误。凭据校验失败、外部平台不可达等连接器层错误统一抛出，
 * 与 {@link NodeExecutionException}（节点执行期）分层——错误分层是排障与
 * 重试策略的基础。
 */
public class ConnectorException extends RuntimeException {

    private final String errorCode;

    public ConnectorException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode == null ? ErrorCode.INTERNAL : errorCode;
    }

    public ConnectorException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? ErrorCode.INTERNAL : errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
