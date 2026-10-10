package io.xuanji.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 数据源/连接器配置数据访问（connector_config，V1 基线）。
 * credential_cipher 为 BYTEA：写入用 decode(?, 'base64')，读出为 byte[]（Service 侧转 Base64 再解密），
 * 与 AssetService 对 model_provider 凭证的处理保持对称。extra_json 走 ::text 避免 PGobject。
 */
@Repository
public class DataSourceRepository {

    private final JdbcTemplate jdbc;

    public DataSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(String platform, String name, String endpoint, String credentialBase64, String extraJson) {
        return jdbc.queryForObject(
                "INSERT INTO connector_config (platform, name, endpoint, credential_cipher, extra_json) "
                        + "VALUES (?, ?, ?, decode(?, 'base64'), ?::jsonb) RETURNING id",
                Long.class, platform, name, endpoint,
                credentialBase64 == null ? "" : credentialBase64,
                extraJson == null ? "{}" : extraJson);
    }

    /** 含密文（供解密连库）；credential_cipher 返回 byte[] */
    public Map<String, Object> findFull(long id) {
        return jdbc.queryForList(
                "SELECT id, platform, name, endpoint, credential_cipher, extra_json::text AS extra_json, status "
                        + "FROM connector_config WHERE id = ?", id)
                .stream().findFirst().orElse(null);
    }

    /** 脱敏列表（不含密文） */
    public List<Map<String, Object>> listByPlatform(String platform) {
        return jdbc.queryForList(
                "SELECT id, platform, name, endpoint, extra_json::text AS extra_json, status "
                        + "FROM connector_config WHERE platform = ? ORDER BY id", platform);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM connector_config WHERE id = ?", id);
    }

    public boolean existsByName(String platform, String name) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM connector_config WHERE platform = ? AND name = ?",
                Integer.class, platform, name);
        return n != null && n > 0;
    }
}