package io.xuanji.server.connector;

import io.xuanji.core.api.NodeExecutionException;
import io.xuanji.server.asset.CryptoUtil;
import io.xuanji.server.repository.DataSourceRepository;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源查询执行器——{@link JdbcQueryNode}（画布节点）与 {@link DataSourceQueryTool}
 * （Agent 工具）共用的「取配置 → 解密 → JDBC 查询」公共实现，避免两份副本漂移。
 *
 * <p>安全约束：仅允许 SELECT（P0 简单前缀校验，生产建议叠加只读账号 + 语句白名单）；
 * 凭证不写日志与异常消息。查询失败抛 {@link NodeExecutionException}（retryable）——
 * 画布侧由引擎按 retry 策略处理，Agent 侧由 ToolAgentAdapter 转为错误 ToolResultBlock。</p>
 */
@Component
public class DataSourceQueryExecutor {

    private final DataSourceRepository repo;
    private final CryptoUtil crypto;

    public DataSourceQueryExecutor(DataSourceRepository repo, CryptoUtil crypto) {
        this.repo = repo;
        this.crypto = crypto;
    }

    /** 按指定数据源执行只读查询，输出 {columns, rows, count}（结果值统一转字符串防序列化漂移） */
    public Map<String, Object> query(long datasourceId, String sql) {
        String s = sql == null ? "" : sql.trim();
        if (s.isEmpty()) {
            throw NodeExecutionException.fatal("DATASOURCE_SQL_MISSING", "数据源查询需要 sql 参数");
        }
        if (!s.toLowerCase().startsWith("select")) {
            throw NodeExecutionException.fatal("DATASOURCE_SQL_FORBIDDEN", "数据源查询仅允许 SELECT 语句");
        }
        Map<String, Object> cfg = repo.findFull(datasourceId);
        if (cfg == null) {
            throw NodeExecutionException.fatal("DATASOURCE_NOT_FOUND", "数据源不存在: " + datasourceId);
        }
        if (!"ENABLED".equals(cfg.get("status"))) {
            throw NodeExecutionException.fatal("DATASOURCE_DISABLED", "数据源已停用: " + datasourceId);
        }
        String endpoint = (String) cfg.get("endpoint");
        Map<String, Object> extra = parseExtra((String) cfg.get("extra_json"));
        String user = extra.get("user") == null ? "" : extra.get("user").toString();
        String password = decryptPassword(cfg.get("credential_cipher"));

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(endpoint, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(s)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            for (int i = 1; i <= n; i++) {
                columns.add(md.getColumnLabel(i));
            }
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    Object v = rs.getObject(i);
                    row.put(md.getColumnLabel(i), v == null ? null : v.toString());
                }
                rows.add(row);
            }
        } catch (Exception e) {
            throw new NodeExecutionException("DATASOURCE_QUERY_FAILED", true,
                    "数据源查询失败: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("count", rows.size());
        return out;
    }

    /**
     * 缺省数据源解析（Agent 工具场景 LLM 可能不指定 datasource_id）：
     * 恰好一个 ENABLED 的 jdbc 数据源 → 直接用；0 个或多个 → 报错并列出清单供 LLM 选择。
     */
    public long resolveDefault() {
        List<Map<String, Object>> enabled = repo.listByPlatform("jdbc").stream()
                .filter(c -> "ENABLED".equals(c.get("status")))
                .toList();
        if (enabled.size() == 1) {
            return ((Number) enabled.get(0).get("id")).longValue();
        }
        String catalog = repo.listByPlatform("jdbc").stream()
                .map(c -> c.get("id") + "=" + c.get("name") + "(" + c.get("status") + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("无");
        throw NodeExecutionException.fatal("DATASOURCE_AMBIGUOUS",
                "平台有 " + enabled.size() + " 个启用的 JDBC 数据源，请在参数 datasource_id 中指定。可选: " + catalog);
    }

    private String decryptPassword(Object cipher) {
        if (cipher == null) {
            return "";
        }
        String base64 = cipher instanceof byte[] b ? Base64.getEncoder().encodeToString(b) : cipher.toString();
        String plain = crypto.decrypt(base64);
        return plain == null ? "" : plain;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseExtra(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
