#!/usr/bin/env bash
# 批六冒烟 step2：发起项目对话（触发 AgentSpec 装配），看日志 builtin tools registered
set -u
TOKEN=$(cat /tmp/.b6_token)
WS=$1
# 找该项目当前阶段
STAGE=$(curl -s http://127.0.0.1:8321/api/projects/$WS -H "Authorization: Bearer $TOKEN" | grep -o '"current_stage":"[^"]*"' | cut -d'"' -f4)
echo "stage=$STAGE"
# 发对话（SSE，取前几行即可——装配日志在服务端）
timeout 90 curl -s -N -X POST http://127.0.0.1:8321/api/projects/$WS/stage/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"列出项目目录下有哪些文件"}' | head -c 600
echo ''
echo SMOKE_B6_S2_DONE
