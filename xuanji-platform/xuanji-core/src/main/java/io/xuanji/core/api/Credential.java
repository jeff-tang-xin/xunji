package io.xuanji.core.api;

import java.util.Map;

/**
 * 连接器凭据（运行时形态）。
 *
 * <p>存储态在平台侧 AES-GCM 加密落库（credential 表），运行时由平台解密后
 * 以本 record 传入 {@link Connector#validateCredential(Credential)} 与节点执行环境。
 * 实现方不得把凭据写入日志或异常消息。</p>
 *
 * @param type   凭据类型（如 basic / api-token / oauth2）
 * @param fields 凭据字段（按 ConnectorMetadata.credentialSchema 的实例）
 */
public record Credential(String type, Map<String, String> fields) {

    public Credential {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
