-- V35: agent 内置工具白名单（AgentScope 2.0.4 自带工具的启用与授权配置）
-- 值形态：JSON 字符串数组，如 ["fs.read","fs.write","shell"]；
-- "shell" = 启用但每次调用 ASK 用户确认（单次授权）；"shell:always" = 永久放行；
-- "fs.read"/"fs.write" = 启用即放行（baseDir 硬限项目目录，无法越界）。
ALTER TABLE agent_config ADD COLUMN IF NOT EXISTS builtin_tools_json JSONB;
