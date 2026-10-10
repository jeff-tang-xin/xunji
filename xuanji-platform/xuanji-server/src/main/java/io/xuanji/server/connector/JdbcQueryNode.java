package io.xuanji.server.connector;

import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.NodeCapability;
import io.xuanji.core.api.NodeContext;
import io.xuanji.core.api.NodeExecutionException;
import io.xuanji.core.api.NodeKind;
import io.xuanji.core.api.NodeMetadata;
import io.xuanji.core.api.NodeOutput;
import io.xuanji.core.api.WorkflowNode;

import java.util.Set;

/**
 * JDBC 数据源查询节点——连接器 SPI 的「查询形态」（主文档 5.1，报表场景数据来源）。
 *
 * <p>按 params.datasource_id 取 connector_config（解密凭证）连外部业务库，执行 params.sql，
 * 输出结构化结果集 {columns, rows, count} 供下游 mapping 与 Agent 消费。
 * 查询实现委托 {@link DataSourceQueryExecutor}——与 Agent 工具 datasource.query
 * （{@link DataSourceQueryTool}）共用同一套 SELECT 校验 / 解密 / 查询逻辑。</p>
 *
 * <p>本节点非 Spring bean，由 {@link DataSourceConnector} 持有实例，
 * 经 RegistryConfiguration 的连接器聚合登记一次。</p>
 */
public class JdbcQueryNode implements WorkflowNode {

    public static final String ID = "jdbc.query";

    private final DataSourceQueryExecutor executor;

    public JdbcQueryNode(DataSourceQueryExecutor executor) {
        this.executor = executor;
    }

    @Override
    public NodeMetadata metadata() {
        return new NodeMetadata(
                ID, "JDBC 数据源查询", NodeKind.ATOMIC, "connector.jdbc", "database",
                "连接外部业务数据库执行 SQL 查询，输出结构化结果集（报表真实数据来源）",
                JsonSchema.anyObject(),
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{
                           "columns":{"type":"array","items":{"type":"string"}},
                           "rows":{"type":"array","items":{"type":"object"}},
                           "count":{"type":"integer"}},
                         "required":["rows","columns","count"]}"""),
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{
                           "datasource_id":{"type":"integer","description":"connector_config.id（JDBC 数据源）"},
                           "sql":{"type":"string","description":"只读 SELECT 查询"}},
                         "required":["datasource_id","sql"]}"""),
                Set.of(NodeCapability.IDEMPOTENT));
    }

    @Override
    public NodeOutput execute(NodeContext ctx) {
        Object dsId = ctx.params().get("datasource_id");
        Object sqlObj = ctx.params().get("sql");
        if (dsId == null || sqlObj == null) {
            throw NodeExecutionException.fatal("DATASOURCE_PARAM_MISSING",
                    "jdbc.query 需 params.datasource_id 与 params.sql");
        }
        return NodeOutput.of(executor.query(((Number) dsId).longValue(), sqlObj.toString()));
    }
}