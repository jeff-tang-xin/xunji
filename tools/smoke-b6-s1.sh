#!/usr/bin/env bash
# 批六冒烟 step1：登录 + 给 project_manager 配内置工具 fs.read
set -u
TOKEN=$(curl -s -X POST http://127.0.0.1:8321/api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Li5710246."}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
[ -z "$TOKEN" ] && { echo LOGIN_FAIL; exit 1; }
echo "token_len=${#TOKEN}"
echo "$TOKEN" > /tmp/.b6_token
PG_P=$(awk '/datasource:/{f=1} f && /password:/{print $2; exit}' /data/xuanji/application.yml | tr -d '\r')
export PGPASSWORD="$PG_P"
PSQL="psql -h 127.0.0.1 -U postgres -d xuanji -tAc"
# 给 project_manager 最新版本配 fs.read 内置工具（幂等：先清后设）
$PSQL "UPDATE agent_config SET builtin_tools_json='[\"fs.read\"]'::jsonb WHERE name='project_manager' AND version=(SELECT MAX(version) FROM agent_config WHERE name='project_manager');"
$PSQL "SELECT id || '@' || version || ' builtin=' || COALESCE(builtin_tools_json::text,'null') FROM agent_config WHERE name='project_manager' ORDER BY version DESC LIMIT 1;"
echo SMOKE_B6_S1_DONE
