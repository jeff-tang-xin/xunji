# 项目四阶段生命周期契约（V26）

> ⚠️ **本文件已被 `stage-lifecycle-contract-v27.md` 取代**（阶段收敛为 3 个、去掉模板驱动、流程改由画布 JSON 在 LiteFlow 执行）。
> 保留原文仅作审计轨迹，**不要按本文件实现或联调**。

> 本文件是后端与前端**唯一对齐依据**。任何一侧要改语义，先改本文件并在黑板登记，不要各自猜测。

## 0. 模型要点（用户已确认）

1. 大阶段由**平台固定**为 4 个，模板不得自定义阶段集合：

| stage | 中文 | 内容 |
|---|---|---|
| `INITIATIVE` | 立项 | 项目说明、文档与留档结构建立 |
| `REQUIREMENT` | 需求 | 需求分析与撰写、原型设计 |
| `DEVELOP` | 开发 | 架构、详设、用例、开发、冒烟、提测、测试 |
| `OPERATE` | 运维 | 发布部署、DB、后期 Bug 与新需求 |

2. **单向推进、不可回退**：`INITIATIVE → REQUIREMENT → DEVELOP → OPERATE`。不提供任何回退接口。
3. **`OPERATE` 是常驻阶段**（没有"完成"），运维阶段内的新需求/Bug 走**迭代**（`iteration` 号递增），
   大阶段不回退、迭代可重复 —— 这是"不可回退"与"运维承接新需求"的调和方式。
4. 模板只定义「每个大阶段里有哪些节点 + 该阶段通用规范」，节点必须归属某个 stage。
5. 创建项目时选择 **新项目 / 老项目维护**；老项目直接落 `OPERATE`。

## 1. 数据库（V26 已建列，勿重复建）

- `workspace.current_stage VARCHAR(16) NOT NULL DEFAULT 'INITIATIVE'`
- `project_work_node.stage VARCHAR(16)`、`project_work_node.iteration INT NOT NULL DEFAULT 1`
- `project_template_node.stage VARCHAR(16)`

## 2. API

### 2.1 `POST /api/projects`（创建）

请求体新增 `project_mode`：`"NEW"`（默认）| `"LEGACY"`。字段 `template` 沿用（模板 code）。

- `NEW`：`current_stage = INITIATIVE`；按模板生成**全部**节点，每个节点写入其 `stage`、`iteration = 1`。
- `LEGACY`：`current_stage = OPERATE`；**只生成模板中 `stage='OPERATE'` 的节点**。
  若模板没有 OPERATE 节点，生成一个兜底节点：
  `type='OPS'`, `stage='OPERATE'`, `owner_role='运维'`, `agent_name='devops'`, `seq=1`, `status='NOT_STARTED'`。

响应新增：`current_stage`。

### 2.2 `GET /api/projects/{id}`（详情）

响应新增：

```json
{
  "current_stage": "DEVELOP",
  "stages": [
    {"stage": "INITIATIVE", "label": "立项", "status": "DONE",  "resident": false, "nodes": [...]},
    {"stage": "REQUIREMENT","label": "需求", "status": "DONE",  "resident": false, "nodes": [...]},
    {"stage": "DEVELOP",    "label": "开发", "status": "RUNNING","resident": false, "nodes": [...]},
    {"stage": "OPERATE",    "label": "运维", "status": "PENDING","resident": true, "iterations": [1], "nodes": [...]}
  ]
}
```

- `nodes[]` 每项新增 `stage`、`iteration`。
- `stage.status` 计算（只看该 stage 下 `iteration=1` 的首轮节点）：
  - 无节点 → `PENDING`
  - 全部 `DONE` → `DONE`
  - 存在 `RUNNING` 或 `WAITING_APPROVAL` → `RUNNING`
  - 存在 `FAILED` 或 `REJECTED` → `BLOCKED`
  - 其余 → `PENDING`
- `resident`：仅 `OPERATE` 为 `true`（常驻，不随节点全 DONE 而"结束"）。
- `iterations`：该 stage 下出现过的迭代号列表（升序）。

### 2.3 `POST /api/projects/{id}/stage/advance`（推进大阶段）

- 前置校验：
  1. `current_stage != OPERATE`，否则 **400** `运维阶段为常驻阶段，不可继续推进，请在运维阶段内发起迭代`
  2. 当前 stage 下 `iteration=1` 的节点**全部 `DONE`**，否则 **400** 带中文原因，例：
     `开发阶段尚有 2 个节点未完成：TEST_CASE、DEVELOP`
- 成功：**200** `{"current_stage": "OPERATE", "advanced": true}`
- **无回退接口**。

### 2.4 `POST /api/projects/{id}/iterations`（运维阶段发起迭代）

请求体可选 `{"name": "新需求：xxx"}`。

- 前置校验：`current_stage == OPERATE`，否则 **400** `仅运维阶段可发起迭代，当前处于「开发」阶段`
- `iteration = 该 workspace 现有 max(iteration) + 1`（首轮=1，故首次迭代=2）
- 生成节点：取模板中 `stage IN ('REQUIREMENT','DEVELOP')` 的节点定义，
  写入 `stage='OPERATE'`、`iteration=新号`，`depends_on` 在**本迭代内部**按模板顺序重链（不跨迭代依赖）。
- 成功：**200** `{"iteration": 2, "nodes": [...]}`

### 2.5 模板 CRUD 新增 `stage`

- `GET /api/projects/templates/{code}` 的 `nodes[]` 每项返回 `stage`。
- `POST/PUT /api/projects/templates` 的 `nodes[]` 每项接受 `stage`；
  缺省时后端按 `type` 自动映射（映射表同 V26 的 CASE，未知类型保守落 `DEVELOP`）。

## 3. 前端要求

1. **创建项目弹窗**：加 `Radio`「新项目 / 老项目维护」，映射 `project_mode`。
   选"老项目维护"时给出说明：直接落在运维阶段，只生成运维阶段节点，后续通过迭代承接新需求。
2. **项目详情**：按 4 大阶段**分组展示**（阶段状态条 + 分组卡片），每个阶段显示
   `label / status / 节点数 / 完成数`；`OPERATE` 标"常驻"，并按 `iteration` 分组显示迭代轮次。
3. **推进按钮**：当前阶段全 DONE 时，非 OPERATE 阶段显示「推进到下一阶段」；
   OPERATE 阶段显示「发起迭代」。按钮禁用条件与后端校验一致，禁用时给出原因。
4. **模板编辑器**：每个节点加「所属大阶段」`Select`（4 个固定选项）。
   **必须用 `Select`，不要用 `AutoComplete`**（后者需先输入才出候选，用户反馈"看不到内容"就是它）。
5. 阶段/状态一律显示中文，禁止把 `INITIATIVE`/`NOT_STARTED` 直接暴露给用户。

## 4. 验收标准

- 后端：`mvn -pl xuanji-server -am compile` BUILD SUCCESS；四个端点行为符合上文（含 400 文案）。
- 前端：`npm run build`（含 `tsc --noEmit`）通过，0 error TS。
- 全量测试由主 Agent 收尾统一兜底，**子 Agent 不要跑全量测试**。
