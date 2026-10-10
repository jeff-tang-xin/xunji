# 01 · 工程结构与实施基线

> 隶属《DevOps 全链路编排平台设计方案》v0.6 · 详细设计 01/06
> 状态：与主文档同评审

---

## 1. 仓库与 Maven 模块布局

单体模块化起步（主文档待决问题 #4 的建议默认值），接口边界按微服务标准设计，后续可拆。

```
xuanji-platform/                         # 根 POM（聚合 + dependencyManagement）
├─ xuanji-bom/                           # 三方依赖版本统一（LiteFlow/AgentScope/LogicFlow 版本唯一出处）
├─ xuanji-core/                          # 核心层：只含接口与抽象，不依赖任何具体实现
│  ├─ io.xuanji.core.api                 #   三层 SPI 接口 + NodeContext/NodeOutput 等 record 类型
│  ├─ io.xuanji.core.registry            #   ComponentRegistry（扫描/登记/查询/校验）
│  ├─ io.xuanji.core.engine              #   引擎封装：画布JSON编译器、规则热刷、分段执行器、run 状态机
│  ├─ io.xuanji.core.agent               #   AgentRuntime 接口 + SSE 事件网关接口 + 事件模型
│  └─ io.xuanji.core.asset               #   资产生命周期接口（Publisher SPI）+ 引用钉住校验
├─ xuanji-agent-scope/                   # AgentRuntime 的 AgentScope 实现（唯一允许 import io.agentscope 的模块）
├─ xuanji-server/                        # Spring Boot 应用：REST/SSE/Webhook/发布流水线/审批中心/调度器
├─ xuanji-admin-web/                     # 前端 React 工程（Vite + AntD + LogicFlow）
└─ plugins/                              # 插件模块（每个只依赖 xuanji-core）
   ├─ node-basic/                         #   内置业务节点示例（校验/通知等，P0 用）
   ├─ connector-jira/
   ├─ connector-zentao/
   └─ connector-yunxiao/
```

**模块依赖规则（编译期强制插拔的基础）**

| 模块 | 允许依赖 | 禁止依赖 |
|---|---|---|
| core | 仅三方基础库（Spring context、Jackson、SLF4J） | 任何实现模块、LiteFlow/AgentScope 具体类 |
| agent-scope | core + AgentScope 扩展模块 | server、plugins |
| plugins/* | 仅 core | 其他插件、server、agent-scope |
| server | core + agent-scope + 全部 plugins | ——（组装点） |

> 关键约束：`io.agentscope` 的 import 只允许出现在 `xuanji-agent-scope`（主文档风险 #1 的落地手段）。

## 2. 版本基线（BOM 统一管理）

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | 21（已确认 Q1） | 虚拟线程用于 SSE 网关 |
| Spring Boot | 3.5.x（已确认） | 生态成熟 |
| LiteFlow | 2.16+（最新稳定版） | spring-boot-starter |
| AgentScope Java | 2.0.3（锁定） | agentscope-harness + model-openai / model-anthropic 扩展 |
| LogicFlow | 2.x（@logicflow/core + extension） | 只用画布，不用其执行引擎 |
| React / TypeScript / Vite / AntD | 18 / 5.x / 5.x / 5.x（已确认 Q3 + TS） | 前端栈定版：React + TS + AntD |
| PostgreSQL | 16 + Flyway（已确认 Q2） | Flyway 管理 schema 版本演进 |
| Redis | 7.x | AgentScope 分布式 session + 调度分布式锁 |

## 3. 配置管理

- `application.yml` 分层：`application.yml`（默认）→ `application-{profile}.yml`（dev/test/prod）→ 环境变量（密钥类只走环境变量）。
- 环境变量清单（P0 最小集）：

| 变量 | 用途 |
|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 数据库 |
| `REDIS_URL` | Redis |
| `CREDENTIAL_ENCRYPT_KEY` | 凭据列加密密钥（AES-GCM），**禁止入库入仓** |
| `DASHSCOPE_API_KEY` 或 `DEEPSEEK_API_KEY` 等 | 模型供应商（按 P0 选型） |

- 业务配置（模型供应商、连接器凭据、AppKey）一律走数据库（见 05 文档 DDL），**不写配置文件**。
- 数据库 schema 演进由 **Flyway** 管理（Q2 决议）：`xuanji-server/src/main/resources/db/migration/V{n}__{desc}.sql`，启动自动迁移；**禁止手工改库**，schema 变更必须新增迁移脚本。

## 4. 编码规约

- 包名根：`io.xuanji`；groupId：`io.xuanji`；根工程：`xuanji-platform`。
- 遵循项目现有约定；无历史包袱时默认：构造器注入（禁字段注入）、`record` 用于不可变数据、异常分层（`NodeExecutionException` 可重试标记 / `ConnectorException` 平台适配错误）、日志占位符 `{}` 不拼接。
- 一切对外 JSON 字段命名 `camelCase`；DB 列名 `snake_case`。

## 5. 构建与本地运行

```bash
mvn -T 1C clean install -DskipITs          # 全量构建（并行）
mvn -pl xuanji-server spring-boot:run      # 本地起后端（8080）
cd xuanji-admin-web && npm run dev         # 前端（Vite dev server，代理 /api 到 8080）
```

- 本地依赖：`docker-compose.dev.yml` 提供 PostgreSQL + Redis（P0 提交）。
- 质量门：`mvn verify` = 编译 + 单测 + 转换器覆盖率 ≥ 80%（转换器是回归重灾区）。

## 6. 部署形态（P5 前单体）

- 产物：`xuanji-server` 可执行 jar + 前端静态资源（Spring 静态托管或 Nginx 分离，二选一，P1 定）。
- 容器化：单 Dockerfile（分层 jar），compose 编排 app + postgres + redis。
- 水平扩展预留：SUSPENDED run 快照在 DB、调度器用 Redis 分布式锁、SSE 网关无状态（事件经 Redis pub/sub 广播）——**P0 就按此设计，P5 才启用多副本**。
