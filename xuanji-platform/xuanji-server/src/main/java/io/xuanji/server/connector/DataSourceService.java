package io.xuanji.server.connector;

import io.xuanji.core.api.Credential;
import io.xuanji.server.asset.CryptoUtil;
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
    public Map<String, Object> create(Map<String, Object> body) {
        String name = str(body, "name");
        String url = str(body, "url");
        String user = str(body, "user");
        String password = str(body, "password");
        if (name == null || name.isBlank() || url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 与 url 必填");
        }
        if (repo.existsByName(PLATFORM, name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据源名称已存在: " + name);
        }
        // 先试连，连不通直接 400，不入库
        validateOrThrow(new Credential("basic", Map.of(
                "url", url, "user", user == null ? "" : user, "password", password == null ? "" : password)));
        String cipher = password == null ? null : crypto.encrypt(password);
        Long id = repo.insert(PLATFORM, name, url, cipher, extraJson(user));
        return Map.of("id", id, "name", name);
    }

    /** 测试连接：用传入的临时凭证试连（不落库） */
    public Map<String, Object> test(Map<String, Object> body) {
        validateOrThrow(new Credential("basic", Map.of(
                "url", nullToEmpty(str(body, "url")),
                "user", nullToEmpty(str(body, "user")),
                "password", nullToEmpty(str(body, "password")))));
        return Map.of("ok", true);
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
    public void delete(long id) {
        if (repo.delete(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据源不存在: " + id);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String extraJson(String user) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("user", user == null ? "" : user));
        } catch (Exception e) {
            return "{}";
        }
    }
}