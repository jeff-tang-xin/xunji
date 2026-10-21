package io.xuanji.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 种子数据初始化（幂等）：内置 2 个 LLM provider（OpenAI/Anthropic 示例，appkey 空）
 * + 7 个智能体（项目经理/产品需求/架构设计/开发/测试/运维/DB）。
 *
 * <p>provider 按 name 查重、agent 按 name 查重，已存在则跳过（不重复插入）。
 * 所有 provider 的 credential_cipher 为 NULL（appkey 空，不加密）；7 个 agent 均绑定
 * OpenAI provider（AgentScopeRuntime 当前仅支持 OPENAI_PROTOCOL，Anthropic provider
 * 作为示例配置存在，待 model-anthropic 扩展接入后可切换）。</p>
 */
@Component
public class SeedInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedInitializer.class);

    private static final String PROTOCOL_OPENAI = "OPENAI_PROTOCOL";
    private static final String PROTOCOL_ANTHROPIC = "ANTHROPIC_PROTOCOL";

    private final JdbcTemplate jdbc;

    /** 只用于 skills_json 的读-改-写：追加阶段规范引用时不能丢掉用户自行绑定的其他 skill。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    public SeedInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        seedProviders();
        seedAgents();
        seedStageSkills();
        seedCoreSkills();
    }

    // ---------- 内置 provider ----------

    private void seedProviders() {
        insertProvider("OpenAI", PROTOCOL_OPENAI,
                "https://api.openai.com/v1", "gpt-4o");
        insertProvider("Anthropic", PROTOCOL_ANTHROPIC,
                "https://api.anthropic.com", "claude-sonnet-4-20250514");
    }

    private void insertProvider(String name, String protocol, String baseUrl, String model) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_provider_config WHERE name = ?", Integer.class, name);
        if (exists != null && exists > 0) {
            log.info("seed provider skipped (already exists): name={}", name);
            return;
        }
        jdbc.update("INSERT INTO model_provider_config "
                        + "(name, protocol, base_url, model, credential_cipher, params_json, status, "
                        + " org_id, created_by, version, updated_by, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, NULL, 'ENABLED', NULL, 'system', 1, 'system', now())",
                name, protocol, baseUrl, model);
        log.info("seed provider created: name={}, protocol={}, model={}", name, protocol, model);
    }

    // ---------- 内置 agent ----------

    private void seedAgents() {
        Long openaiProviderId = jdbc.queryForObject(
                "SELECT id FROM model_provider_config WHERE name = 'OpenAI'", Long.class);
        if (openaiProviderId == null) {
            log.warn("seed agent skipped: OpenAI provider not found");
            return;
        }
        insertAgent("project_manager", "项目经理", openaiProviderId, PROMPT_PROJECT_MANAGER, "fs.read", "fs.write");
        insertAgent("product_analyst", "产品需求", openaiProviderId, PROMPT_PRODUCT_ANALYST, "fs.read");
        insertAgent("architect", "架构设计", openaiProviderId, PROMPT_ARCHITECT, "fs.read");
        insertAgent("developer", "开发", openaiProviderId, PROMPT_DEVELOPER, "fs.read", "fs.write");
        insertAgent("tester", "测试", openaiProviderId, PROMPT_TESTER, "fs.read");
        insertAgent("devops", "运维", openaiProviderId, PROMPT_DEVOPS, "fs.read");
        insertAgent("dba", "DB", openaiProviderId, PROMPT_DBA, "fs.read");
        publishBuiltinAgents();
    }

    /** 内置 agent 名单：写入顺序、发布作用域都以此为准。 */
    private static final List<String> BUILTIN_AGENTS = List.of(
            "project_manager", "product_analyst", "architect", "developer", "tester", "devops", "dba");

    /**
     * 把内置 agent 的最新版置为 PUBLISHED。
     *
     * <p>不是图省事：画布 AGENT 节点的发布期校验（{@code AgentNodeExecutor.validateRef}）要求被引用的
     * {@code agent_config} 版本 status='PUBLISHED'，DRAFT 一律 400。项目流程改由 LiteFlow 执行后，
     * PM 规划出来的画布全是 AGENT 节点 —— 内置 agent 若还停在 DRAFT，一条都发不出去。
     * 只发布每个 name 的最新版本，历史版本保持原状，免得旧版本被当成可用引用钉进流程。</p>
     */
    private void publishBuiltinAgents() {
        for (String name : BUILTIN_AGENTS) {
            int n = jdbc.update("UPDATE agent_config SET status = 'PUBLISHED', updated_by = 'system', "
                    + "updated_at = now() WHERE name = ? AND status <> 'PUBLISHED' AND version = "
                    + "(SELECT MAX(version) FROM agent_config a2 WHERE a2.name = agent_config.name)", name);
            if (n > 0) {
                log.info("seed agent published: name={} rows={}", name, n);
            }
        }
    }

    private void insertAgent(String name, String description, Long providerId, String systemPrompt,
                             String... builtinTools) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_config WHERE name = ?", Integer.class, name);
        String builtinJson = builtinTools.length == 0 ? null
                : toJsonArray(java.util.Arrays.asList(builtinTools));
        if (exists != null && exists > 0) {
            // 存量升级（幂等）：builtin_tools 只补 NULL——用户已配置的不动
            if (builtinJson != null) {
                jdbc.update("UPDATE agent_config SET builtin_tools_json = CAST(? AS jsonb) "
                        + "WHERE name = ? AND builtin_tools_json IS NULL", builtinJson, name);
            }
            // system_prompt 按种子刷新（2026-10-21 用户定调：内置 agent 的职责提示词是平台资产，
            // 每次新部署初始化到最新——agent 管理里看到的职责与实际行为必须一致，不随版本漂移）
            jdbc.update("UPDATE agent_config SET system_prompt = ?, updated_by = 'system', updated_at = now() "
                    + "WHERE name = ?", systemPrompt, name);
            log.info("seed agent prompt refreshed: name={}", name);
            return;
        }
        jdbc.update("INSERT INTO agent_config "
                        + "(name, description, model_provider_id, system_prompt, tools_json, "
                        + " permission_json, params_json, skills_json, builtin_tools_json, version, status, "
                        + " org_id, created_by, updated_by, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, NULL, NULL, NULL, CAST(? AS jsonb), 1, 'PUBLISHED', "
                        + " NULL, 'system', 'system', now())",
                name, description, providerId, systemPrompt, builtinJson);
        log.info("seed agent created: name={}, description={}, builtin={}", name, description, builtinJson);
    }

    /** 字符串列表 → JSON 数组文本（builtin_tools_json 是 JSONB 字符串数组）。 */
    private String toJsonArray(java.util.List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(items.get(i)).append("\"");
        }
        return sb.append("]").toString();
    }

    // ---------- 阶段规范 skill（模板去掉后，"每阶段必须满足什么"迁到这里） ----------

    /**
     * 把 3 份阶段规范以 skill 形态写入 {@code skill_repo} 并绑定相关 agent。
     *
     * <p>背景：项目流程改为由 PM 现场规划后，{@code project_template} 不再参与节点生成。
     * 但模板里真正起作用的是那套阶段通用知识（验收标准怎么写、什么算冒烟通过、发布前查什么）——
     * 实测注入与否差别很大：注入后 AI 会主动产出"范围外清单""合规与隐私检查条目"这类章节，
     * 不注入就退回泛泛而谈。所以知识不能跟着模板一起丢，换成 skill 绑到 agent 上，
     * 由既有 {@code AgentNodeExecutor.resolveSkills} 在装配系统提示词时展开。</p>
     */
    private void seedStageSkills() {
        // name / description 一律从 skill 文件的 frontmatter 读取，这里只声明"绑给谁"
        bindSkill("skills/stage-requirement-spec.md",
                List.of("product_analyst", "project_manager"));
        bindSkill("skills/stage-develop-spec.md",
                List.of("architect", "developer", "tester", "dba"));
        bindSkill("skills/stage-operate-spec.md",
                List.of("devops", "project_manager"));
    }

    /**
     * 通用工程 skill（2026-10-21：easy-claw 平台技能库全部内置进 xunji）。
     * 与阶段规范同机制：classpath 资源为唯一来源（frontmatter 提供 name/description），
     * 幂等写入 skill_repo（已存在只刷新正文与摘要、不抬版本），按职责绑定到内置 agent。
     * 绑定原则：谁在哪个职责上消费这些标准就绑给谁——skill 注入系统提示词，是角色的专业底座。
     */
    private void seedCoreSkills() {
        bindSkill("skills/backend-architecture.md", List.of("architect", "dba"));
        bindSkill("skills/clean-code.md", List.of("developer", "tester"));
        bindSkill("skills/code-refactor.md", List.of("developer"));
        bindSkill("skills/karpathy-guidelines.md", List.of("architect", "developer"));
        bindSkill("skills/vercel-react-best-practices.md", List.of("developer"));
        bindSkill("skills/frontend-quality.md", List.of("developer", "tester"));
        bindSkill("skills/devops-cicd.md", List.of("devops"));
        bindSkill("skills/cursor-rules.md", List.of("project_manager"));
        bindSkill("skills/antdesign-philosophy.md", List.of("product_analyst"));
        bindSkill("skills/elementui-philosophy.md", List.of("product_analyst"));
    }

    /**
     * 幂等写入单份阶段规范并绑定到 agent 列表。
     *
     * <p>已存在时只刷新正文与摘要、<b>不抬版本号</b>：agent 绑定的是 {@code skill:{id}@{ver}}，
     * 而 resolveSkills 要求 version 精确匹配，抬版本会让旧引用静默解析不到 —— 规范反而丢了。
     * 需要正式改版时走资产界面发布新版本，再由绑定关系迁移过来。</p>
     */
    private void bindSkill(String resource, List<String> agentNames) {
        String raw;
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("阶段规范资源缺失，skipped: {}", resource);
                return;
            }
            raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            log.warn("读取阶段规范失败: {} ({})", resource, e.toString());
            return;
        }
        SkillMeta meta = parseSkillFrontmatter(raw, resource);
        if (meta == null) {
            return;
        }
        String name = meta.name();
        String description = meta.description();
        String md = meta.body();
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM skill_repo WHERE name = ?", Integer.class, name);
        if (exists != null && exists > 0) {
            jdbc.update("UPDATE skill_repo SET content_md = ?, description = ?, status = 'PUBLISHED', "
                    + "updated_by = 'system', updated_at = now() WHERE name = ?", md, description, name);
        } else {
            jdbc.update("INSERT INTO skill_repo (name, description, content_uri, version, status, "
                            + "content_md, updated_by, updated_at) VALUES (?, ?, ?, 1, 'PUBLISHED', ?, 'system', now())",
                    name, description, "classpath:" + resource, md);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, version FROM skill_repo WHERE name = ?", name);
        if (rows.isEmpty()) {
            log.warn("阶段规范写入后仍查不到，skipped: {}", name);
            return;
        }
        Map<String, Object> row = rows.get(0);
        String ref = "skill:" + row.get("id") + "@" + row.get("version");
        for (String agent : agentNames) {
            appendSkillRef(agent, ref);
        }
        log.info("seed stage skill: name={} ref={} agents={}", name, ref, agentNames);
    }

    /** 把 skill 引用追加进 {@code agent_config.skills_json}；已含则不动（保留用户自行绑定的其他 skill）。 */
    private void appendSkillRef(String agentName, String ref) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT skills_json::text AS skills_json FROM agent_config WHERE name = ?", agentName);
        if (rows.isEmpty()) {
            log.warn("agent 不存在，阶段规范绑定跳过: {}", agentName);
            return;
        }
        Object raw = rows.get(0).get("skills_json");
        String text = raw == null ? "" : raw.toString().trim();
        List<String> refs = new ArrayList<>();
        if (!text.isEmpty() && !"null".equals(text)) {
            try {
                Object parsed = JSON.readValue(text, Object.class);
                if (parsed instanceof List<?> list) {
                    for (Object o : list) {
                        refs.add(o == null ? null : String.valueOf(o));
                    }
                }
            } catch (Exception e) {
                // 脏数据不能覆盖成空数组了事：那会把用户已有的绑定抹掉，宁可不改并留日志
                log.warn("agent {} 的 skills_json 解析失败，跳过绑定以免覆盖: {}", agentName, e.toString());
                return;
            }
        }
        if (refs.contains(ref)) {
            return;
        }
        refs.add(ref);
        try {
            jdbc.update("UPDATE agent_config SET skills_json = ?::jsonb, updated_at = now() WHERE name = ?",
                    JSON.writeValueAsString(refs), agentName);
        } catch (Exception e) {
            log.warn("agent {} 绑定阶段规范失败: {}", agentName, e.toString());
        }
    }

    /** skill 文件解析结果：frontmatter 的 name / description + 去掉 frontmatter 的正文。 */
    private record SkillMeta(String name, String description, String body) {
    }

    /**
     * 解析 skill 文件的 YAML frontmatter（Anthropic Agent Skills 形态：{@code ---} 包裹的
     * {@code name} 与 {@code description}）。
     *
     * <p>为什么以文件为唯一来源：description 决定"这个 skill 什么时候该被启用"，
     * 如果 Java 里再写一份，两处迟早漂移，而漂移后排查成本极高（表现为"规范明明改了却没生效"）。</p>
     *
     * <p>为什么不引 YAML 解析器：frontmatter 只有两个已知字段，手解足够；为一个解析器加依赖不值。
     * 正文单独存 content_md —— 注入系统提示词时不带 frontmatter，免得模型把元数据当成指令。</p>
     *
     * <p>缺 name 直接跳过（无标识的 skill 写进库就是脏数据）；缺 description 仍写入但告警，
     * 因为正文本身有价值，不该因为一行元数据没填就把规范丢掉。</p>
     */
    private SkillMeta parseSkillFrontmatter(String raw, String resource) {
        String text = raw.replace("\r\n", "\n").strip();
        if (!text.startsWith("---\n")) {
            log.warn("skill 缺少 YAML frontmatter（不符合 Anthropic 规范），skipped: {}", resource);
            return null;
        }
        int end = text.indexOf("\n---", 4);
        if (end < 0) {
            log.warn("skill frontmatter 未闭合，skipped: {}", resource);
            return null;
        }
        String head = text.substring(4, end);
        String after = text.substring(end);
        int bodyStart = after.indexOf('\n', 1);
        String body = bodyStart < 0 ? "" : after.substring(bodyStart + 1).strip();

        String name = null;
        String description = null;
        for (String line : head.split("\n")) {
            int i = line.indexOf(':');
            if (i <= 0) {
                continue;
            }
            String key = line.substring(0, i).trim();
            String val = line.substring(i + 1).trim();
            if (val.length() >= 2 && ((val.startsWith("\"") && val.endsWith("\""))
                    || (val.startsWith("'") && val.endsWith("'")))) {
                val = val.substring(1, val.length() - 1);
            }
            if ("name".equals(key)) {
                name = val;
            } else if ("description".equals(key)) {
                description = val;
            }
        }
        if (name == null || name.isBlank()) {
            log.warn("skill frontmatter 缺 name，skipped: {}", resource);
            return null;
        }
        if (!name.matches("[a-z0-9-]{1,64}")) {
            // 规范约束：仅小写字母/数字/连字符，≤64，且应与标识一致。不阻断，但必须看得见
            log.warn("skill name 不符合 Anthropic 命名规范（小写字母/数字/连字符，≤64）: {}", name);
        }
        if (description == null || description.isBlank()) {
            log.warn("skill {} 缺 description（决定何时启用该 skill），请补齐 frontmatter", name);
            description = name;
        }
        return new SkillMeta(name, description, body);
    }

    // ---------- 7 个 agent 的 system_prompt ----------

    private static final String PROMPT_PROJECT_MANAGER = """
            你是「项目经理」，项目的主控者——不是转述用户的话的传声筒。

            【角色职责】
            - 主控编排：把项目目标拆解为阶段与步骤，决定做什么、什么顺序、派给谁。
            - 项目把控：对照台账掌握真实进度（哪些做了、哪些没做、哪些卡住），不凭感觉汇报。
            - 进度失控的制止人：发现重复讨论、无效步骤、原地打转，立即制止并收敛——同议题讨论不超过 2 轮，收敛不出结论就升级用户决策或跳过。
            - 成本把控：每一步都有成本（时间/token/人力），发起任何步骤前先过「做事前四问」。
            - 流程梳理：每轮开工先对照台账梳理流程状态（哪里卡了/哪里重复了/最优下一步是什么），发现重复步/无效步主动跳过收敛。

            【做事前四问（方法论，防止阶段爆炸）】
            发起任何步骤（规划/讨论/派任务/发决策）之前先自答：
            ①为什么做这件事——它解决什么具体问题（「应该做」「通常要做」不是答案）；
            ②做它能带来什么——产出什么、下游谁消费、成本是什么；
            ③依据是什么——凭什么判断会有这个收益（已有产出/数据/POC/明确的需求条目）；
            ④有没有替代方案——至少考虑过一条备选并说清为什么选它（只想到一条路=还没想清楚）。
            四问答不清楚就不要发起——它大概率是空转（重复讨论、仪式性文档、无人消费的产出）。

            【工作方式】
            - 先理解项目背景与约束（目标、范围、时间、团队、技术栈），再输出计划。
            - 用平台的数据源查询能力核实现状（如已有代码、文档、配置），避免凭空假设。
            - 派任务时把四问的答案写进任务卡（为什么做/产出什么/验收依据），执行者不是接单机器。
            - 遇到风险主动上报并给出缓解方案，不隐瞒、不拖延。

            【产出物】
            - 项目计划（阶段划分、里程碑、依赖关系、前置条件）。
            - 进度报告（当前状态、已完成、进行中、阻塞项、下一步）——必须与台账一致。
            - 风险清单与应对策略。
            - 阶段留档登记：每个阶段完成后把最终结论登记为交付留档，供下一阶段读取。
            """;

    private static final String PROMPT_PRODUCT_ANALYST = """
            你是「产品需求」，负责需求调研、需求分析、PRD 编写与需求评审——不是建单记录员。

            【角色职责】
            - 调研用户与业务诉求，澄清模糊需求，识别真实问题与价值。
            - 需求合理性论证：为什么需要这个需求？不做的代价是什么？优先级凭什么？
            - 边界条件：什么算需求范围内、什么明确算外、例外场景怎么处理——边界不清的需求开发必然跑偏。
            - 交付口径：每个需求做完长什么样、怎么验收（可判定的验收标准，禁止「体验良好」）。
            - 必要时产出可点击的原型（HTML 文件落盘），让用户看到东西而不是想象东西。
            - 将诉求转化为结构化、可验收的需求描述（功能/非功能/边界）。

            【工作方式】
            - 先调研再分析：用平台的数据源查询能力核实业务现状与数据，不凭空编造需求。
            - 需求描述遵循「用户故事 + 验收标准」格式，保证可测试、可追溯。
            - 需求有歧义时主动提问澄清，不替用户做未确认的假设——笼统的需求必须拆到可验收的粒度。
            - 需求变更走留档登记，保证版本可追溯。

            【退化禁令】只有一句话标题、没有用户故事/验收标准/边界的需求是废需求，不许交付。

            【产出物】
            - 需求调研纪要（用户、场景、痛点、期望）。
            - PRD（需求清单、用户故事、验收标准、优先级、边界与例外、范围外清单）。
            - 原型（可选但推荐：关键流程的 HTML 原型）。
            - 需求评审记录（评审结论、待决项、变更记录）。
            - 阶段留档登记：需求分析完成后把最终 PRD 登记为交付留档，供架构设计读取。
            """;

    private static final String PROMPT_ARCHITECT = """
            你是「架构设计」，负责系统架构设计、技术选型、接口设计与技术方案——架构的本质是架构，不是写一份架构文档。

            【角色职责（产出四件套，缺一不可）】
            - 技术选型定稿：结构化清单（类别/选型/版本/理由/被否决的替代方案）。每个选型过「做事前四问」：①为什么选它（解决什么具体问题）②带来什么（成本收益）③依据是什么（benchmark/POC/官方文档，最硬的形式是在骨架上真跑 POC 对比）④替代方案是什么（只想到一条路=还没想清楚）。选型必须过环境关：与运维确认当前环境支持性，环境不支持的技术方案是废纸。
            - 项目骨架真建出来：用文件工具在工作区创建目录树、构建配置（pom/package.json）、分层占位文件、测试目录——空骨架必须能构建通过（mvn compile / npm install）。骨架是活的，不是纸面目录树。
            - 目录规范：分层范式（DDD/MVC/TDD 按项目适配）落到可执行规则——什么进什么目录、命名规则、测试放哪、依赖方向。
            - 设计说明文档：整体架构（模块划分、数据流）、接口契约（API/数据模型/错误语义）、逐需求技术方案（每个需求点怎么做、难点与对策、任务拆分依据）。文档只是说明之一，骨架和选型才是架构本身。

            【工作方式】
            - 先读需求与既有代码：用平台的数据源查询能力核实现状，复用已有能力而非重复造轮子。
            - 架构方案遵循「简单优先、可演进」原则，避免过度设计。
            - 接口设计明确输入/输出/错误语义，与开发、测试对齐验收口径。
            - 关键决策记录理由与备选方案，便于评审与追溯。

            【退化禁令】只写一份 markdown 文档、没有骨架、没有结构化选型清单 = 架构工作没做。

            【产出物】
            - 技术选型清单（docs/DEVELOP/tech-stack.md，含四问论证与环境确认）。
            - 项目骨架（工作区真实目录树+构建配置，构建通过）。
            - 目录规范（可执行规则）。
            - 架构设计文档（总体架构、接口契约、逐需求技术方案）。
            - 阶段留档登记：架构设计完成后把最终技术方案登记为交付留档，供详设/开发读取。
            """;

    private static final String PROMPT_DEVELOPER = """
            你是「开发」，负责代码实现、单元测试与代码评审——不是做一个 demo 功能就申报完成。

            【角色职责】
            - 按技术方案与接口契约实现功能代码，保证正确、可读、可维护。
            - 在项目骨架的既有模块里开工，遵循目录规范——骨架是既成事实，不自己发明目录结构。
            - 编写单元测试覆盖核心逻辑与边界情况，测试与实现同步交付——禁止「先声明完成再补测试」。
            - 参与代码评审，保证代码质量与一致性。

            【工作方式】
            - 先读方案与既有代码风格，遵循项目既有命名、分层与错误处理约定。
            - 实现遵循「改动最小化」，不顺手扩大范围、不引入未要求的依赖。
            - 边界情况主动覆盖（空值、空集合、越界、异常路径、资源释放）。
            - 错误处理不吞异常：要么处理并说明，要么带上下文上抛。
            - 提交前自测：构建通过、测试真跑过、关键路径验证——「能跑」是机器跑出来的，不是自己说的。

            【退化禁令】示例片段、伪实现、「应该能跑」的代码不算交付；没有测试的实现不算完成。

            【产出物】
            - 功能代码（含单元测试，构建+测试真跑通过）。
            - 代码评审记录（评审意见、修改结论）。
            - 阶段留档登记：开发完成后把交付代码说明与变更记录登记为交付留档，供测试读取。
            """;

    private static final String PROMPT_TESTER = """
            你是「测试」，负责测试策略、测试执行、缺陷管理与质量报告——不是写个测试用例文档。

            【角色职责】
            - 基于需求与接口契约设计测试用例，覆盖正常、边界与异常路径。
            - 真跑执行：用 shell 断言在项目根目录真跑测试命令（构建/测试/健康检查），结果真实回写——「应该能过」不算测过。
            - 缺陷管理：发现缺陷上报（report_bug 挂需求与用例），跟踪修复直至关闭。
            - 输出测试报告（通过率、遗留缺陷、发布建议），为发布决策提供依据。

            【工作方式】
            - 先读需求、接口与实现说明，用平台的数据源查询能力核实测试数据与预期。
            - 测试用例遵循「可复现、可追溯」原则，每条用例明确前置条件、步骤、预期结果。
            - 缺陷描述清晰（复现步骤、实际/预期、环境、严重度），不吞问题。
            - 测试用例可在开发阶段并行设计（依赖详设而非开发完成），提测前先跑冒烟用例。

            【退化禁令】用例文档不算交付——真跑过的执行记录与结果回写才算；结果与预期不符时如实报 FAILED，不许美化。

            【产出物】
            - 测试用例集（功能/边界/异常/回归，declare_test_cases 落档）。
            - 测试执行记录与缺陷清单（settle_test_cases 真实回写、report_bug）。
            - 测试报告（通过率、遗留缺陷、发布建议）。
            - 阶段留档登记：测试完成后把测试报告与缺陷结论登记为交付留档，供发布决策读取。
            """;

    private static final String PROMPT_DEVOPS = """
            你是「运维」，负责环境评估、部署、发布、监控与日常运维——不只是处理服务器。

            【角色职责】
            - 环境评估：当前环境支持什么技术栈（OS/运行时/数据库/端口/资源水位）——技术选型必须过环境关，架构选型时你是环境适配性的判定人。
            - 负责构建、部署与发布流程，保证环境一致与可回滚。
            - 配置监控与告警，保障服务可用性与稳定性。
            - 处理线上问题，做根因分析与容量规划。

            【工作方式】
            - 先读部署文档与既有环境，用平台的数据源查询能力核实配置与状态。
            - 发布遵循「小步、可回滚、可观测」原则，发布前确认依赖与前置条件。
            - 监控指标明确（可用性、延迟、错误率、资源水位），异常及时告警。
            - 变更与操作留痕，便于追溯与复盘。

            【退化禁令】「已完成」三个字不算交付——执行留档（命令+输出+结论）才算。

            【产出物】
            - 环境评估报告（支持的技术栈/版本/资源约束——供技术选型引用）。
            - 部署/发布方案（步骤、回滚预案、验证清单）。
            - 监控与告警配置说明。
            - 运维记录（发布记录、故障复盘、容量建议）。
            - 阶段留档登记：发布完成后把发布记录与运行状态登记为交付留档。
            """;

    private static final String PROMPT_DBA = """
            你是「DB」，负责数据库设计、SQL 编写、数据迁移与性能优化。

            【角色职责】
            - 设计数据库模型（表结构、索引、约束、外键），保证数据一致性与可扩展性。
            - 编写与评审 SQL，保证正确、高效、安全。
            - 负责数据迁移与数据质量，定位并优化慢查询。

            【工作方式】
            - 先读需求与既有数据模型，用平台的数据源查询能力核实表结构与数据分布。
            - 表设计遵循「最小必要、可演进」原则，索引按查询模式建立，避免过度索引。
            - SQL 遵循参数化查询，杜绝 SQL 注入；迁移脚本幂等、可回滚。
            - 性能问题先定位（执行计划、慢日志）再优化，不盲目加索引或改结构。

            【退化禁令】没在真实库上验证过的 SQL 不算交付——执行计划与验证结果随交付留档。

            【产出物】
            - 数据库设计文档（表结构、索引、约束、ER 关系）。
            - SQL 脚本（建表/迁移/初始化，幂等可回滚，附验证结果）。
            - 性能优化报告（慢查询分析、优化方案与效果）。
            - 阶段留档登记：数据库相关设计完成后把最终模型与脚本登记为交付留档。
            """;
}