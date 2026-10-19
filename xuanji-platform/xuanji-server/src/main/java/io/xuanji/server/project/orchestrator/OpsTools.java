package io.xuanji.server.project.orchestrator;

import io.xuanji.core.api.Tool;
import io.xuanji.core.api.ToolMetadata;
import io.xuanji.core.api.ToolRequest;
import io.xuanji.core.api.ToolResult;
import io.xuanji.core.runtime.ToolDef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维工具三件套（AI 操作服务器/数据库的唯一入口，会话级 BEAN 形态）。
 *
 * <p><b>安全边界（用户定调）</b>：地址与凭证都不暴露给 AI——工具参数只收引用 ID
 * （server-1/db-1，清单随 boardDigest 注入），连接由 {@link OpsConnectionManager}
 * 平台侧代理（凭证解密、未初始化就初始化、已初始化就复用），AI 只拿执行结果。</p>
 *
 * <p>挂载：TaskRunner 执行任务时经 {@code defsFor(workspaceId, ops)} 注入执行 agent
 * （部署/排障任务由 devops 跑，工具就位；用不用由 AI 按任务决定）。</p>
 */
public final class OpsTools {

    private OpsTools() { }

    /** 三个运维工具（闭包携带 wsId——引用 ID 只在项目内解析）。 */
    public static List<ToolDef> defsFor(String workspaceId, OpsConnectionManager ops) {
        return List.of(remoteShell(workspaceId, ops), dbQuery(workspaceId, ops), dbDml(workspaceId, ops));
    }

    private static ToolDef remoteShell(String wsId, OpsConnectionManager ops) {
        Tool tool = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return new ToolMetadata("ops_remote_shell", "ops_remote_shell", "ops_remote_shell",
                        null, null);
            }

            @Override
            public ToolResult invoke(ToolRequest request) {
                try {
                    String server = str(request.input().get("server"));
                    String command = str(request.input().get("command"));
                    int timeout = timeoutOf(request.input().get("timeout_seconds"));
                    return ToolResult.of(ops.execOnServer(wsId, server, command, timeout));
                } catch (Exception e) {
                    return ToolResult.of(Map.of("ok", false, "message", rootMessage(e)));
                }
            }
        };
        return new ToolDef("ops_remote_shell",
                "在项目绑定的目标服务器上执行 shell 命令（部署、启停、排障、看日志）。"
                        + "server 取项目运维资源清单里的服务器引用（如 server-1）；连接由平台代理（凭证托管），"
                        + "你只拿命令结果。返回 {exitCode, stdout, stderr}——exitCode 0 为成功。"
                        + "命令要有确定性（避免交互式命令），输出过长会被截断。",
                schemaOf(new String[][]{
                        {"server", "服务器引用 ID，如 server-1（清单见运维资源说明）"},
                        {"command", "要执行的 shell 命令，避免交互式命令"},
                        {"timeout_seconds", "超时秒数，缺省 60，上限 300"}},
                        List.of("server", "command")),
                "BEAN", null, tool);
    }

    private static ToolDef dbQuery(String wsId, OpsConnectionManager ops) {
        Tool tool = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return new ToolMetadata("db_query", "db_query", "db_query",
                        null, null);
            }

            @Override
            public ToolResult invoke(ToolRequest request) {
                try {
                    return ToolResult.of(ops.queryDb(wsId,
                            str(request.input().get("db")), str(request.input().get("sql"))));
                } catch (Exception e) {
                    return ToolResult.of(Map.of("ok", false, "message", rootMessage(e)));
                }
            }
        };
        return new ToolDef("db_query",
                "查询项目绑定的数据库（只读 SELECT/WITH），返回 {columns, rows, count}。"
                        + "db 取项目运维资源清单里的数据库引用（如 db-1）；连接由平台代理（凭证托管）。"
                        + "用于部署后验证数据、核对表结构；写操作用 db_dml。",
                schemaOf(new String[][]{
                        {"db", "数据库引用 ID，如 db-1（清单见运维资源说明）"},
                        {"sql", "只读查询语句（SELECT/WITH 开头，单条）"}},
                        List.of("db", "sql")),
                "BEAN", null, tool);
    }

    private static ToolDef dbDml(String wsId, OpsConnectionManager ops) {
        Tool tool = new Tool() {
            @Override
            public ToolMetadata metadata() {
                return new ToolMetadata("db_dml", "db_dml", "db_dml",
                        null, null);
            }

            @Override
            public ToolResult invoke(ToolRequest request) {
                try {
                    return ToolResult.of(ops.execDb(wsId,
                            str(request.input().get("db")), str(request.input().get("sql"))));
                } catch (Exception e) {
                    return ToolResult.of(Map.of("ok", false, "message", rootMessage(e)));
                }
            }
        };
        return new ToolDef("db_dml",
                "在项目绑定的数据库上执行单条写操作（CREATE TABLE / INSERT / UPDATE / DELETE 等部署初始化 DDL/DML）。"
                        + "db 取项目运维资源清单里的数据库引用（如 db-1）；连接由平台代理（凭证托管）。"
                        + "一次一条 SQL；建表用 IF NOT EXISTS，写数据注意幂等，执行后用 db_query 验证。",
                schemaOf(new String[][]{
                        {"db", "数据库引用 ID，如 db-1（清单见运维资源说明）"},
                        {"sql", "单条 DDL/DML 语句"}},
                        List.of("db", "sql")),
                "BEAN", null, tool);
    }

    /** JSON Schema 构造（properties 值必须是 {type,description} 对象——裸字符串=非法 schema，网关 400）。 */
    private static Map<String, Object> schemaOf(String[][] props, List<String> required) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String[] p : props) {
            Map<String, Object> pv = new LinkedHashMap<>();
            pv.put("type", "string");
            pv.put("description", p[1]);
            properties.put(p[0], pv);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required);
        return m;
    }

    private static int timeoutOf(Object o) {
        if (o instanceof Number n && n.intValue() > 0) {
            return Math.min(n.intValue(), 300);
        }
        return 60;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }
}
