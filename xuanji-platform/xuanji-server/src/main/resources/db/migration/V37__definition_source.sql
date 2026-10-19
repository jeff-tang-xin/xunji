-- V37: 项目流程与工作区流程分离（定义来源标记）
-- 项目阶段规划编译产物（StagePlanCompiler → DefinitionService.create）此前与用户自建工作流
-- 落同一个命名空间，工作流定义列表被「[阶段] 项目名 · 阶段 第N轮」灌满且已发布不可删。
-- 加 source 列区分：user = 用户自建（列表可见），stage_plan = 项目阶段执行产物（内部实现细节，列表过滤）。

ALTER TABLE wf_definition ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'user';

-- 存量打标：阶段规划编译产物的命名规则见 ProjectService（trimTo("[阶段] " + projectName + ...)）
UPDATE wf_definition SET source = 'stage_plan' WHERE name LIKE '[阶段] %';
