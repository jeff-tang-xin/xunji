package io.xuanji.server.project.orchestrator;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import io.xuanji.server.asset.CryptoUtil;
import io.xuanji.server.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运维连接代理——AI 操作服务器/数据库的唯一通道（凭证托管 + 连接会话复用）。
 *
 * <p><b>安全边界（用户定调）</b>：地址与凭证都不暴露给 AI。AI 只见工具与引用 ID
 * （server-1/db-1，见 boardDigest 运维资源清单）；本类按引用 ID 找到项目绑定的
 * 连接条目（OPERATE capability，凭证 CryptoUtil 加密存储），解密后由平台侧
 * 发起连接——密码不进 AI 上下文、不进留档。</p>
 *
 * <p><b>连接生命周期（用户定调）</b>：AI 调工具即触发连接——未初始化就初始化
 * （用托管凭证建立），已初始化就复用（ssh Session / JDBC Connection 缓存，
 * 失效自动重连）。AI 决定「启动不启动」= 调不调工具，连接管理对 AI 透明。</p>
 *
 * <p>无状态纪律例外：本类就是连接状态的管理者（缓存即职责），按 wsId|ref 隔离，
 * 多项目并发安全。</p>
 */
@Service
public class OpsConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(OpsConnectionManager.class);
    private static final int SSH_TIMEOUT_MS = 15_000;
    private static final int STDOUT_LIMIT = 8_000;
    private static final int STDERR_LIMIT = 2_000;

    private final ProjectRepository projectRepo;
    private final CryptoUtil crypto;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 连接会话缓存：key = wsId|S|server-1 / wsId|D|db-1 */
    private final Map<String, Session> sshSessions = new ConcurrentHashMap<>();
    private final Map<String, Connection> dbConns = new ConcurrentHashMap<>();

    public OpsConnectionManager(ProjectRepository projectRepo, CryptoUtil crypto,
                                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.projectRepo = projectRepo;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    // ---------------- 绑定条目解析 ----------------

    /** 绑定条目：地址 + 解密后的凭证（只在平台侧内存存在，绝不进返回给 AI 的内容）。 */
    public record ServerEntry(String user, String host, int port, String password) { }
    public record DbEntry(String jdbcUrl, String username, String password) { }

    /** 引用 ID（server-2）→ 绑定条目。未配置/越界/缺凭证抛 IllegalArgumentException（AI 可读）。 */
    public ServerEntry resolveServer(String wsId, String ref) {
        List<Map<String, Object>> items = capabilityItems(wsId, "servers");
        int idx = refIndex(ref, "server");
        if (idx < 0 || idx >= items.size()) {
            throw new IllegalArgumentException("服务器引用 " + ref + " 不存在，可用清单见运维资源说明");
        }
        Map<String, Object> item = items.get(idx);
        String address = str(item.get("address"));
        String pwd = crypto.decrypt(str(item.get("password")));
        String[] up = parseUserHost(address);
        return new ServerEntry(up[0], up[1], Integer.parseInt(up[2]), pwd);
    }

    /** 引用 ID（db-2）→ 绑定条目。 */
    public DbEntry resolveDb(String wsId, String ref) {
        List<Map<String, Object>> items = capabilityItems(wsId, "databases");
        int idx = refIndex(ref, "db");
        if (idx < 0 || idx >= items.size()) {
            throw new IllegalArgumentException("数据库引用 " + ref + " 不存在，可用清单见运维资源说明");
        }
        Map<String, Object> item = items.get(idx);
        String address = str(item.get("address"));
        String pwd = crypto.decrypt(str(item.get("password")));
        return new DbEntry(toJdbcUrl(address), str(item.get("username")), pwd);
    }

    /** OPERATE capability 里某类绑定的对象列表（兼容存量字符串条目——视为无凭证，调用时报可读错误）。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capabilityItems(String wsId, String key) {
        List<Map<String, Object>> rows = projectRepo.findStageCapability(wsId, "OPERATE");
        if (rows.isEmpty() || rows.get(0).get("capability_json") == null) {
            return List.of();
        }
        Object raw = rows.get(0).get("capability_json");
        Map<String, Object> cap;
        try {
            cap = raw instanceof Map<?, ?> m
                    ? new java.util.LinkedHashMap<String, Object>((Map<String, Object>) m)
                    : objectMapper.readValue(String.valueOf(raw),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return List.of();
        }
        Object list = cap.get(key);
        if (!(list instanceof List<?> l)) {
            return List.of();
        }
        return l.stream()
                .filter(o -> o instanceof Map)
                .map(o -> (Map<String, Object>) o)
                .toList();
    }

    /** 引用 ID → 列表下标（server-1 → 0）。 */
    private static int refIndex(String ref, String prefix) {
        if (ref == null) return -1;
        String r = ref.trim().toLowerCase();
        if (!r.startsWith(prefix + "-")) return -1;
        try {
            return Integer.parseInt(r.substring(prefix.length() + 1)) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** root@host:port → [user, host, port]；缺省 user=root、port=22。 */
    private static String[] parseUserHost(String address) {
        String user = "root";
        String rest = address.trim();
        int at = rest.indexOf('@');
        if (at > 0) {
            user = rest.substring(0, at);
            rest = rest.substring(at + 1);
        }
        int colon = rest.lastIndexOf(':');
        int port = 22;
        if (colon > 0) {
            try {
                port = Integer.parseInt(rest.substring(colon + 1));
                rest = rest.substring(0, colon);
            } catch (NumberFormatException ignore) {
                // 无端口段
            }
        }
        return new String[]{user, rest, String.valueOf(port)};
    }

    /** host:port/dbname → jdbc:postgresql://host:port/dbname（容忍完整 jdbc: 前缀直传）。 */
    private static String toJdbcUrl(String address) {
        String a = address.trim();
        if (a.startsWith("jdbc:")) return a;
        return "jdbc:postgresql://" + a;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ---------------- SSH：未初始化就初始化，已初始化就复用 ----------------

    /** 在目标服务器执行命令，返回 {exitCode, stdout, stderr}。会话复用，失效自动重连一次。 */
    public Map<String, Object> execOnServer(String wsId, String ref, String command, int timeoutSeconds) {
        ServerEntry entry = resolveServer(wsId, ref);
        if (entry.password() == null || entry.password().isBlank()) {
            throw new IllegalArgumentException("服务器 " + ref + " 未配置凭证，请在项目编辑里补录密码");
        }
        String key = wsId + "|S|" + ref;
        try {
            return doExec(sshSession(key, entry), entry, command, timeoutSeconds);
        } catch (Exception first) {
            // 会话可能已被服务端断开：关旧建新重试一次
            Session stale = sshSessions.remove(key);
            if (stale != null) {
                try { stale.disconnect(); } catch (Exception ignore) { }
            }
            try {
                return doExec(sshSession(key, entry), entry, command, timeoutSeconds);
            } catch (Exception second) {
                throw new IllegalStateException("SSH 执行失败: " + rootMessage(second), second);
            }
        }
    }

    /** 连接会话：缓存命中且 connected 即复用，否则用托管凭证新建。 */
    private Session sshSession(String key, ServerEntry entry) throws Exception {
        Session s = sshSessions.get(key);
        if (s != null && s.isConnected()) {
            return s;
        }
        JSch jsch = new JSch();
        s = jsch.getSession(entry.user(), entry.host(), entry.port());
        s.setPassword(entry.password());
        // 内网部署机场景：跳过 host key 交互（首次连接无 known_hosts 会让 session 卡死）
        s.setConfig("StrictHostKeyChecking", "no");
        s.connect(SSH_TIMEOUT_MS);
        sshSessions.put(key, s);
        log.info("ssh session initialized: key={} {}@{}:{}", key, entry.user(), entry.host(), entry.port());
        return s;
    }

    private Map<String, Object> doExec(Session session, ServerEntry entry, String command, int timeoutSeconds)
            throws Exception {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            channel.setErrStream(null);
            InputStream out = channel.getInputStream();
            InputStream err = channel.getErrStream();
            channel.connect(timeoutSeconds * 1000);
            String stdout = drain(out, STDOUT_LIMIT);
            String stderr = drain(err, STDERR_LIMIT);
            while (channel.isConnected()) {
                Thread.sleep(100);
            }
            return Map.of(
                    "exitCode", channel.getExitStatus(),
                    "stdout", stdout,
                    "stderr", stderr);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /** 读流到 EOF，超限截断（防 cat 大文件撑爆上下文）。 */
    private static String drain(InputStream in, int limit) throws Exception {
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = in.read(buf)) > 0 && sb.length() < limit) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        if (sb.length() >= limit) {
            sb.append("\n…（输出过长已截断）");
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }

    // ---------------- JDBC：未初始化就初始化，已初始化就复用 ----------------

    /** 取库连接：缓存命中且 isValid 即复用，否则用托管凭证新建。 */
    private Connection dbConn(String key, DbEntry entry) throws Exception {
        Connection c = dbConns.get(key);
        try {
            if (c != null && c.isValid(3)) {
                return c;
            }
        } catch (Exception ignore) {
            // 失效连接走重建
        }
        if (c != null) {
            try { c.close(); } catch (Exception ignore) { }
        }
        c = DriverManager.getConnection(entry.jdbcUrl(), entry.username(), entry.password());
        dbConns.put(key, c);
        log.info("db connection initialized: key={} {}", key, entry.jdbcUrl());
        return c;
    }

    /** 只读查询，返回 {columns, rows, count}。 */
    public Map<String, Object> queryDb(String wsId, String ref, String sql) {
        String key = wsId + "|D|" + ref;
        DbEntry entry = resolveDb(wsId, ref);
        requireCredentials(entry.username(), entry.password(), "数据库 " + ref);
        requireSingleStatement(sql, true);
        try (java.sql.Statement st = dbConn(key, entry).createStatement()) {
            st.setQueryTimeout(30);
            try (java.sql.ResultSet rs = st.executeQuery(sql)) {
                List<String> columns = new java.util.ArrayList<>();
                int colCount = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(rs.getMetaData().getColumnLabel(i));
                }
                List<Map<String, Object>> rows = new java.util.ArrayList<>();
                while (rs.next() && rows.size() < 100) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(columns.get(i - 1), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return Map.of("columns", columns, "rows", rows, "count", rows.size());
            }
        } catch (Exception e) {
            throw new IllegalStateException("查询失败: " + rootMessage(e), e);
        }
    }

    /** 单条 DML/DDL 执行，返回 {ok, affected 或 message}。 */
    public Map<String, Object> execDb(String wsId, String ref, String sql) {
        String key = wsId + "|D|" + ref;
        DbEntry entry = resolveDb(wsId, ref);
        requireCredentials(entry.username(), entry.password(), "数据库 " + ref);
        requireSingleStatement(sql, false);
        try (java.sql.Statement st = dbConn(key, entry).createStatement()) {
            st.setQueryTimeout(60);
            boolean isResultSet = st.execute(sql.trim());
            if (isResultSet) {
                try (java.sql.ResultSet rs = st.getResultSet()) {
                    List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    int cols = rs.getMetaData().getColumnCount();
                    while (rs.next() && rows.size() < 50) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return Map.of("ok", true, "message", "执行成功（返回结果集 " + rows.size() + " 行）", "rows", rows);
                }
            }
            int affected = st.getUpdateCount();
            return Map.of("ok", true, "affected", affected);
        } catch (Exception e) {
            throw new IllegalStateException("执行失败: " + rootMessage(e), e);
        }
    }

    /** 凭证缺失 → 可读错误（AI 能理解要去让用户补录，而不是看到半截连接异常）。 */
    private static void requireCredentials(String username, String password, String label) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(label + " 未配置凭证，请在项目编辑里补录账号密码");
        }
    }

    /** 单语句校验：query 只允许 SELECT/WITH；两类都拒绝中间分号多语句。 */
    private static void requireSingleStatement(String sql, boolean readOnly) {
        String s = sql.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.contains(";")) {
            throw new IllegalArgumentException("一次只执行一条 SQL（不支持分号分隔的多语句）");
        }
        String head = s.toUpperCase();
        if (readOnly && !head.startsWith("SELECT") && !head.startsWith("WITH")) {
            throw new IllegalArgumentException("db_query 只接受只读查询（SELECT/WITH 开头）；写操作请用 db_dml");
        }
    }
}
