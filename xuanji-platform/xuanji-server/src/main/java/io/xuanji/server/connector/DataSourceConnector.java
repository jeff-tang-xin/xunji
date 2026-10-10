package io.xuanji.server.connector;

import io.xuanji.core.api.Connector;
import io.xuanji.core.api.ConnectorException;
import io.xuanji.core.api.ConnectorMetadata;
import io.xuanji.core.api.Credential;
import io.xuanji.core.api.JsonSchema;
import io.xuanji.core.api.WebhookParser;
import io.xuanji.core.api.WorkflowNode;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/**
 * JDBC 数据源连接器——连接器 SPI 的「查询形态」（主文档 5.1 / 报表场景数据来源）。
 *
 * <p>与「操作型」连接器（jira 写外部系统）不同，本连接器通过 {@link #nodes()} 提供一个
 * 查询节点 {@link JdbcQueryNode}，把外部业务库的真实数据以结构化结果集喂给下游。</p>
 *
 * <p>作为内置连接器落在 server（组装点）：节点执行需读平台 connector_config 与解密凭证；
 * 外部平台专用连接器仍应独立 Maven 模块 + Spring 自动装配接入。</p>
 */
@Component
public class DataSourceConnector implements Connector {

    public static final String ID = "jdbc";

    private final JdbcQueryNode queryNode;

    public DataSourceConnector(DataSourceQueryExecutor executor) {
        this.queryNode = new JdbcQueryNode(executor);
    }

    @Override
    public ConnectorMetadata metadata() {
        return new ConnectorMetadata(
                ID, "JDBC 数据源", "database",
                "连接外部业务数据库（JDBC），执行查询输出结构化结果集，作为报表/工作流的真实数据来源",
                JsonSchema.parse("""
                        {"type":"object",
                         "properties":{
                           "url":{"type":"string","description":"JDBC URL，如 jdbc:postgresql://host:5432/db"},
                           "user":{"type":"string"},
                           "password":{"type":"string","format":"password"}},
                         "required":["url","user","password"]}"""));
    }

    @Override
    public List<WorkflowNode> nodes() {
        return List.of(queryNode);
    }

    @Override
    public List<WebhookParser> webhookParsers() {
        return List.of();
    }

    @Override
    public void validateCredential(Credential credential) throws ConnectorException {
        String url = credential.fields().get("url");
        String user = credential.fields().get("user");
        String password = credential.fields().get("password");
        if (url == null || url.isBlank()) {
            throw new ConnectorException("CONN_INVALID", "JDBC URL 不能为空");
        }
        try (Connection c = DriverManager.getConnection(url, user == null ? "" : user,
                password == null ? "" : password)) {
            if (!c.isValid(5)) {
                throw new ConnectorException("CONN_TIMEOUT", "数据源连接无效");
            }
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("CONN_FAILED", "数据源连接失败: " + e.getMessage(), e);
        }
    }
}