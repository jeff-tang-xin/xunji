package io.xuanji.core.api;

import java.util.List;

/**
 * 连接器 SPI——「外部平台接入」的聚合抽象：一个连接器 = 节点 + 事件解析器 + 凭据。
 *
 * <p>可插拔机制（编译期插拔）：连接器是一个 Maven 模块（或模块内一组 bean），
 * 只依赖 xuanji-core；server 作为组装点引入其依赖 + Spring 自动装配即完成接入。
 * 新增外部平台 = 新增一个 Connector 实现，核心零改动（开闭原则的物理落点）。</p>
 *
 * <p>与通用 MCP 自定义接入双轨并行（主文档 Q6）：标准平台走专用连接器（体验深），
 * 长尾平台走 MCP 自定义接入（零开发）。</p>
 */
public interface Connector {

    /** 连接器元数据（id/name/凭据字段 schema——前端动态渲染凭据表单） */
    ConnectorMetadata metadata();

    /** 平台操作 → 注册为画布节点（如 jira.create-issue / jira.transition-issue） */
    List<WorkflowNode> nodes();

    /** 平台事件 → 注册为触发解析器（如 jira issue.updated） */
    List<WebhookParser> webhookParsers();

    /**
     * 凭据保存前的连通性校验（如调 /rest/api/3/myself 验证 token）。
     *
     * @throws ConnectorException AUTH_FAILED / CONN_TIMEOUT 等
     */
    void validateCredential(Credential credential) throws ConnectorException;
}
