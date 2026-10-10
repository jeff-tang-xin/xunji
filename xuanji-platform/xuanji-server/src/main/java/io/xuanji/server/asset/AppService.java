package io.xuanji.server.asset;

import io.xuanji.server.auth.DataScopeFilter;
import io.xuanji.server.auth.LoginUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 应用（App）与 AppKey（主文档 4.9）。
 *
 * <p>App = 面向第三方的发布单元：创建时绑定已发布 workflow/agent 并钉住具体版本
 * （引用钉住，升级绑定版本是显式动作），发布（PUBLISHED）后经 /api/v1/apps/{appId}/**
 * 开放端点消费。AppKey 明文仅创建/轮换时返回一次，库中只存 SHA-256 哈希；
 * scope 控制可调用端点集（runs/chat/resume）。表结构沿用 V1 基线（id 为 app_ 前缀 VARCHAR）。</p>
 */
@Service
public class AppService {

    private final JdbcTemplate jdbc;

    public AppService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final List<String> APP_COLS = List.of(
            "name", "description", "bound_asset_type", "bound_asset_id", "callback_url");

    /* ---------- 管理端：CRUD（数据权限过滤） ---------- */

    public List<Map<String, Object>> list(LoginUser user) {
        var c = DataScopeFilter.of(user, "org_id", "created_by");
        return jdbc.queryForList("SELECT id, name, description, bound_asset_type, bound_asset_id, "
                + "bound_asset_version, callback_url, status, org_id, created_by, created_at FROM app WHERE " + c.sql()
                + " ORDER BY created_at", c.args().toArray());
    }

    public Map<String, Object> get(LoginUser user, String id) {
        Map<String, Object> app = jdbc.queryForList(
                "SELECT id, name, description, bound_asset_type, bound_asset_id, bound_asset_version, "
                        + "callback_url, status, org_id, created_by, created_at FROM app WHERE id = ?", id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "app not found"));
        requireOwner(user, app);
        return app;
    }

    /** 创建 App：绑定已发布资产并钉住当前版本（bound_asset_version NOT NULL，V1 基线） */
    @Transactional
    public Map<String, Object> create(LoginUser user, Map<String, Object> body) {
        String type = str(body, "bound_asset_type");
        if (!"WORKFLOW".equals(type) && !"AGENT".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bound_asset_type must be WORKFLOW or AGENT");
        }
        long assetId = requireLong(body, "bound_asset_id");
        Integer pinned = validateBinding(type, assetId);

        String id = "app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        List<Object> vals = new java.util.ArrayList<>();
        vals.add(id);
        vals.add(user.orgId()); // 平台管理员无组织 → NULL
        vals.add(user.username());
        StringBuilder cols = new StringBuilder("id, org_id, created_by");
        for (String col : APP_COLS) {
            if (body.get(col) != null) {
                cols.append(", ").append(col);
                vals.add(col.equals("bound_asset_id") ? assetId : body.get(col));
            }
        }
        cols.append(", bound_asset_version, status");
        vals.add(pinned);
        vals.add("DRAFT");
        StringBuilder marks = new StringBuilder("?, ?, ?");
        for (int i = 0; i < vals.size() - 3; i++) {
            marks.append(", ?");
        }
        jdbc.update("INSERT INTO app (" + cols + ") VALUES (" + marks + ")", vals.toArray());
        return Map.of("id", id, "bound_asset_version", pinned);
    }

    @Transactional
    public void update(LoginUser user, String id, Map<String, Object> body) {
        get(user, id); // 归属校验 + 存在性
        List<Object> vals = new java.util.ArrayList<>();
        StringBuilder sets = new StringBuilder();
        for (String col : APP_COLS) {
            if (body.containsKey(col)) {
                if (sets.length() > 0) {
                    sets.append(", ");
                }
                sets.append(col).append(" = ?");
                vals.add(col.equals("bound_asset_id") ? requireLong(body, col) : body.get(col));
            }
        }
        // 升级绑定版本 = 显式动作：显式传 bound_asset_version 时校验目标版本存在（基于 app 现有绑定资产）
        if (body.containsKey("bound_asset_version")) {
            var cur = jdbc.queryForMap("SELECT bound_asset_type, bound_asset_id FROM app WHERE id = ?", id);
            long assetId = ((Number) cur.get("bound_asset_id")).longValue();
            sets.append(sets.length() > 0 ? ", " : "").append("bound_asset_version = ?");
            vals.add(validateVersion((String) cur.get("bound_asset_type"), assetId,
                    ((Number) body.get("bound_asset_version")).intValue()));
        }
        if (sets.length() == 0) {
            return;
        }
        sets.append(", updated_by = ?, updated_at = ?");
        vals.add(user.username());
        vals.add(java.sql.Timestamp.from(java.time.Instant.now()));
        vals.add(id);
        jdbc.update("UPDATE app SET " + sets + " WHERE id = ?", vals.toArray());
    }

    @Transactional
    public void delete(LoginUser user, String id) {
        Map<String, Object> app = get(user, id);
        Integer creds = jdbc.queryForObject("SELECT count(*) FROM app_credential WHERE app_id = ?", Integer.class, id);
        if (creds != null && creds > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "先注销该 App 的全部 AppKey 再删除");
        }
        if ("PUBLISHED".equals(app.get("status"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已发布 App 不可删除（先禁用）");
        }
        jdbc.update("DELETE FROM app WHERE id = ?", id);
    }

    /** App 发布：校验绑定资产仍已发布 → status=PUBLISHED（对外可消费） */
    @Transactional
    public Map<String, Object> publish(LoginUser user, String id) {
        Map<String, Object> app = get(user, id);
        String type = (String) app.get("bound_asset_type");
        long assetId = ((Number) app.get("bound_asset_id")).longValue();
        validateBinding(type, assetId); // 发布时复核绑定资产仍处于已发布状态
        jdbc.update("UPDATE app SET status = 'PUBLISHED', updated_by = ?, updated_at = ? WHERE id = ?",
                user.username(), java.sql.Timestamp.from(java.time.Instant.now()), id);
        return Map.of("id", id, "status", "PUBLISHED");
    }

    /** 禁用 App：存量 AppKey 全部失效（开放端点校验 app_status） */
    @Transactional
    public void disable(LoginUser user, String id) {
        get(user, id);
        jdbc.update("UPDATE app SET status = 'DISABLED', updated_by = ?, updated_at = ? WHERE id = ?",
                user.username(), java.sql.Timestamp.from(java.time.Instant.now()), id);
    }

    /** 绑定校验：资产存在且已发布，返回 current_version 作为钉住版本 */
    private Integer validateBinding(String type, long assetId) {
        if ("WORKFLOW".equals(type)) {
            var rows = jdbc.queryForList(
                    "SELECT status, current_version FROM wf_definition WHERE id = ?", assetId);
            if (rows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定的 workflow 不存在: " + assetId);
            }
            String status = (String) rows.get(0).get("status");
            Integer ver = (Integer) rows.get(0).get("current_version");
            if (!"PUBLISHED".equals(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定的 workflow 未发布（status=" + status + "）");
            }
            return ver;
        }
        var rows = jdbc.queryForList("SELECT status, version FROM agent_config WHERE id = ?", assetId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定的 agent 不存在: " + assetId);
        }
        String status = (String) rows.get(0).get("status");
        Integer ver = (Integer) rows.get(0).get("version");
        if (!"PUBLISHED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定的 agent 未发布（status=" + status + "）");
        }
        return ver;
    }

    /** 校验指定版本存在（升级绑定版本用） */
    private Integer validateVersion(String type, long assetId, int version) {
        if ("WORKFLOW".equals(type)) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM wf_definition_version WHERE definition_id = ? AND version = ?",
                    Integer.class, assetId, version);
            if (n == null || n == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标版本不存在: v" + version);
            }
        }
        return version;
    }

    /* ---------- AppKey 生命周期 ---------- */

    /** 签发 AppKey：明文仅本次返回，库中只存 SHA-256 */
    @Transactional
    public Map<String, Object> createCredential(LoginUser user, String appId, Map<String, Object> body) {
        get(user, appId);
        String raw = "app-" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String scopes = body.get("scopes") != null ? toJson(body.get("scopes")) : "[\"runs\"]";
        Long keyId = jdbc.queryForObject(
                "INSERT INTO app_credential (app_id, key_hash, key_prefix, scopes_json, created_by) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?) RETURNING id",
                Long.class, appId, sha256(raw), raw.substring(0, 12), scopes, user.username());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyId", keyId);
        result.put("keyPrefix", raw.substring(0, 12));
        result.put("key", raw); // 明文仅此一次
        return result;
    }

    /** 轮换：签发新 key，旧 key 置 DISABLED */
    @Transactional
    public Map<String, Object> rotate(LoginUser user, String appId, long keyId) {
        get(user, appId);
        Map<String, Object> old = jdbc.queryForList(
                "SELECT id, scopes_json::text AS scopes FROM app_credential WHERE id = ? AND app_id = ?", keyId, appId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "credential not found"));
        jdbc.update("UPDATE app_credential SET status = 'DISABLED' WHERE id = ?", keyId);
        String raw = "app-" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Long newId = jdbc.queryForObject(
                "INSERT INTO app_credential (app_id, key_hash, key_prefix, scopes_json, created_by) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?) RETURNING id",
                Long.class, appId, sha256(raw), raw.substring(0, 12), old.get("scopes"), user.username());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyId", newId);
        result.put("rotatedFrom", keyId);
        result.put("key", raw); // 明文仅此一次
        return result;
    }

    @Transactional
    public void disableCredential(LoginUser user, String appId, long keyId) {
        get(user, appId);
        jdbc.update("UPDATE app_credential SET status = 'DISABLED' WHERE id = ? AND app_id = ?", keyId, appId);
    }

    public List<Map<String, Object>> listCredentials(LoginUser user, String appId) {
        get(user, appId);
        return jdbc.queryForList("SELECT id, key_prefix, scopes_json::text AS scopes, status, created_at "
                + "FROM app_credential WHERE app_id = ? ORDER BY id", appId);
    }

    /* ---------- 开放端点鉴权：AppKey → app（PUBLISHED）→ scope ---------- */

    /** 校验 Bearer key：返回 app 上下文（appId 必须匹配路径）；失败抛 401/403 */
    public Map<String, Object> authorize(String rawKey, String pathAppId, String requiredScope) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing app key");
        }
        var rows = jdbc.queryForList(
                "SELECT c.id AS key_id, c.scopes_json::text AS scopes, c.status AS key_status, "
                        + "a.id AS app_id, a.status AS app_status, a.bound_asset_type, a.bound_asset_id, a.bound_asset_version "
                        + "FROM app_credential c JOIN app a ON a.id = c.app_id WHERE c.key_hash = ?",
                sha256(rawKey));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid app key");
        }
        Map<String, Object> row = rows.get(0);
        if (!"ACTIVE".equals(row.get("key_status"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "app key disabled");
        }
        if (!"PUBLISHED".equals(row.get("app_status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "app not published");
        }
        if (!pathAppId.equals(row.get("app_id"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "app key does not match appId");
        }
        String scopes = (String) row.get("scopes");
        if (scopes == null || !scopes.contains("\"" + requiredScope + "\"")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "scope '" + requiredScope + "' not granted");
        }
        return row;
    }

    /* ---------- 工具 ---------- */

    private void requireOwner(LoginUser user, Map<String, Object> app) {
        if (user.isPlatformAdmin() || "ALL".equals(user.dataScope())) {
            return;
        }
        String scope = user.dataScope() == null ? "ONLY_CREATE" : user.dataScope();
        boolean allowed = switch (scope) {
            case "UNDER_ORG", "ORG" -> user.orgId() != null && user.orgId().equals(app.get("org_id"));
            default -> user.username().equals(app.get("created_by"));
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 App");
        }
    }

    private long requireLong(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a number");
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "[\"runs\"]";
        }
    }

    static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
