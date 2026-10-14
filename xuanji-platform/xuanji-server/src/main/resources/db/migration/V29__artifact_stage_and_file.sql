-- V29: 交付物阶段归属 + 项目目录落盘
--   project_artifact 加 stage 列：阶段对话自动登记的交付物记录产出阶段，
--   前端按阶段分组展示；历史数据（V26 台账节点登记）stage 为空，前端归入「未标注」。
--   落盘文件（docs/{stage}/{name}.md）路径存 file_path，git 可直接跟踪。
-- ============================================================

ALTER TABLE project_artifact ADD COLUMN IF NOT EXISTS stage VARCHAR(16);
CREATE INDEX IF NOT EXISTS idx_pa_stage ON project_artifact(workspace_id, stage);
