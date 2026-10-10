package io.xuanji.server.connector;

import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.NodeExecutionException;
import io.xuanji.core.api.Tool;
import io.xuanji.core.api.ToolMetadata;
import io.xuanji.core.api.ToolRequest;
import io.xuanji.core.api.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数据源查询工具——Tool SPI 的 Agent 形态（「一次实现两处可用」）。
 *
 * <p>{@code @Component} 后由 RegistryConfiguration 自动扫描：画布出现
 * {@code tool.datasource_query} 节点；所有 Agent 经 AgentScopeRuntime 的
 * ToolAgentAdapter 全局获得本工具（P0 平台级能力，非声明式绑定）——LLM 在
 * ReAct 循环中以函数参数传 sql / datasource_id，即可查询外部业务库并基于
 * 结果继续推理（如「查一下运行状态然后给出分析」）。</p>
 *
 * <p>查询实现与画布 jdbc.query 节点共用 {@link DataSourceQueryExecutor}
 * （同一套 SELECT 校验 / 凭证解密 / 结果结构），行为一致。</p>
 */
@Component
public class DataSourceQueryTool implements Tool {

    public static final String ID = "datasource.query";

    private final DataSourceQueryExecutor executor;

    public DataSourceQueryTool(DataSourceQueryExecutor executor) {
        this.executor = executor;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ID, "数据源查询",
                "查询平台已配置的外部业务数据源（ERP/WMS/财务等 JDBC 库，如 PostgreSQL/MySQL）。"
                        + "参数 sql 必填（只读 SELECT 语句）；datasource_id 可选——缺省时若平台恰好只有一个"
                        + "启用的 JDBC 数据源则自动使用，否则报错并列出可选清单。"
                        + "返回 {columns, rows, count} 结构化结果，可基于 rows 内容继续分析。",
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{
                           "sql":{"type":"string","description":"只读 SELECT 查询语句"},
                           "datasource_id":{"type":"integer","description":"数据源ID（可选，缺省用唯一启用的数据源）"}},
                         "required":["sql"]}"""),
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{
                           "columns":{"type":"array","items":{"type":"string"}},
                           "rows":{"type":"array","items":{"type":"object"}},
                           "count":{"type":"integer"}},
                         "required":["rows","columns","count"]}"""));
    }

    @Override
    public ToolResult invoke(ToolRequest request) throws NodeExecutionException {
        Map<String, Object> in = request.input();
        Object sqlObj = in.get("sql");
        if (sqlObj == null) {
            throw NodeExecutionException.fatal("DATASOURCE_SQL_MISSING", "数据源查询需要 sql 参数");
        }
        Object dsId = in.get("datasource_id");
        long id = dsId instanceof Number n ? n.longValue() : executor.resolveDefault();
        return ToolResult.of(executor.query(id, sqlObj.toString()));
    }
}
