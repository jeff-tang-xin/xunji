package io.xuanji.server.connector;

import io.xuanji.core.api.Credential;
import io.xuanji.server.asset.CryptoUtil;
import io.xuanji.server.auth.LoginUser;
import io.xuanji.server.repository.DataSourceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 数据源配置管理（connector_config，platform=jdbc）。
 * 凭证 AES-GCM 加密落库（Base64→decode(base64) 存 BYTEA），明文仅试连时临时使用、绝不回显；
 * 创建前先试连，连不通不入库（避免脏配置）。SQL 归 {@link DataSourceRepository}（C4 分层）。
 *
 * <p>数据源为平台级共享资源（画布节点/Agent 工具执行时无 LoginUser 上下文），
 * list/get 对所有登录用户可见；create 记录创建者，delete 校验创建者本人或平台管理员。</p>
 */
@Service
public class DataSourceService {

    private static final String PLATFORM = "jdbc";

    private final DataSourceRepository repo;
    private final CryptoUtil crypto;
    private final DataSourceConnector connector;

    public DataSourceService(DataSourceRepository repo, CryptoUtil crypto, DataSourceConnector connector) {
        this.repo = repo;
        this.crypto = crypto;
        this.connector = connector;
    }

    public List<Map<String, Object>> list() {
        return repo.listByPlatform(PLATFORM);
    }

    public Map<String, Object> get(long id) {
        Map<String, Object> row = repo.findFull(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据源不存在: " + id);
        }
        row.remove("credential_cipher"); // 脱敏：密文不出接口
        return row;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body, LoginUser user) {
        String name = str(body, "name");
        String url = str(body, "url");
        String dbUser = str(body, "user");
        String password = str(body, "password");
        String dbType = normalizeDbType(body.get("db_type"), url);
        if (name == null || name.isBlank() || url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 与 url 必填");
        }
        if (repo.existsByName(PLATFORM, name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据源名称已存在: " + name);
        }
        // 先试连，连不通直接 400，不入库
        validateOrThrow(new Credential("basic", Map.of(
                "url", url, "user", dbUser == null ? "" : dbUser, "password", password == null ? "" : password)));
        String cipher = password == null ? null : crypto.encrypt(password);
        Long id = repo.insert(PLATFORM, name, url, cipher, extraJson(dbUser, dbType), user.username());
        return Map.of("id", id, "name", name, "db_type", dbType);
    }

    /** 测试连接：用传入的临时凭证试连（不落库） */
    public Map<String, Object> test(Map<String, Object> body) {
        String url = nullToEmpty(str(body, "url"));
        normalizeDbType(body.get("db_type"), url); // 类型与 URL 前缀一致性前置校验
        validateOrThrow(new Credential("basic", Map.of(
                "url", url,
                "user", nullToEmpty(str(body, "user")),
                "password", nullToEmpty(str(body, "password")))));
        return Map.of("ok", true);
    }

    /** 支持的数据库类型 → JDBC URL 前缀。类型未传时从 URL 推断，推断不出按 postgresql 兜底。 */
    private static final Map<String, String> DB_URL_PREFIX = Map.of(
            "postgresql", "jdbc:postgresql://",
            "mysql", "jdbc:mysql://");

    /**
     * 归一化数据库类型：显式传入时校验合法 + 与 URL 前缀一致（防「选了 MySQL 填了 PG URL」的错配）；
     * 未传时从 URL 前缀推断（兼容旧客户端）。
     */
    private String normalizeDbType(Object raw, String url) {
        String requested = raw == null ? "" : raw.toString().trim().toLowerCase();
        String inferred = null;
        for (var e : DB_URL_PREFIX.entrySet()) {
            if (url != null && url.startsWith(e.getValue())) {
                inferred = e.getKey();
                break;
            }
        }
        if (requested.isEmpty()) {
            return inferred != null ? inferred : "postgresql";
        }
        if (!DB_URL_PREFIX.containsKey(requested)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的数据库类型: " + requested + "（当前支持 " + DB_URL_PREFIX.keySet() + "）");
        }
        if (inferred != null && !inferred.equals(requested)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "数据库类型与连接地址不匹配：选了 " + requested + " 但 URL 是 " + DB_URL_PREFIX.get(inferred) + " 前缀");
        }
        return requested;
    }

    /** 试连失败转 400：ConnectorException 是 RuntimeException，不拦会冒 500（异常分支须友好报错） */
    private void validateOrThrow(Credential credential) {
        try {
            connector.validateCredential(credential);
        } catch (io.xuanji.core.api.ConnectorException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "数据源连接校验失败: " + e.getMessage());
        }
    }

    @Transactional
    public void delete(long id, LoginUser user) {
        Map<String, Object> row = repo.findFull(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据源不存在: " + id);
        }
        if (!user.isPlatformAdmin()) {
            Object createdBy = row.get("created_by");
            if (createdBy == null || !createdBy.equals(user.username())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅数据源创建者或平台管理员可删除");
            }
        }
        repo.delete(id);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String extraJson(String user, String dbType) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("user", user == null ? "" : user, "db_type", dbType == null ? "postgresql" : dbType));
        } catch (Exception e) {
            return "{}";
        }
    }
}