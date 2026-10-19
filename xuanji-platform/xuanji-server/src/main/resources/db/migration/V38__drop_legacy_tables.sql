-- V38: 清理废弃的项目域表（破坏性迁移，部署前需用户确认）
-- 依据（2026-10-19 数据结构审计）：
-- ① project_work_node：V27 起规划不再落台账（新项目 0 节点），执行真相单一在 wf_run/wf_node_run，
--   台账是第二个真相源且无回写通道；节点级端点（run/complete/bind-workflow/artifacts/chat）已删。
-- ② project_template_node：V30 模板数据化（stages_json）后节点链不参与执行，仅历史兼容。
-- ③ project_pm_session：写不读（create 时 upsert 一条，无任何读取方），会话真相在 agent_state。
-- ④ project_stage_session：lockStageSession 写 locked_at 无读取方，对话锁语义已由
--   inputLocked=pendingDecisions（决策答复链路）取代。
-- 四表的级联 DELETE 行保留在 WorkspaceRepository.deleteWorkspaceCascade 中，
-- 本迁移执行后这些 DELETE 语句会因表不存在而失败——需同步删除（随本迁移同 jar 发布）。

DROP TABLE IF EXISTS project_work_node;
DROP TABLE IF EXISTS project_template_node;
DROP TABLE IF EXISTS project_pm_session;
DROP TABLE IF EXISTS project_stage_session;
