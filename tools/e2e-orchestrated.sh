#!/usr/bin/env bash
# 新内核（AI 编排）E2E 冒烟：新项目 → 第一句话 → 主控 plan_steps/execute_step → steps 台账出现
set -u
BASE=http://localhost:8321
: "${XJ_PW:?missing XJ_PW}"

curl -s -o /tmp/eo_login -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$XJ_PW\"}" > /dev/null
T=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' /tmp/eo_login)
[ -z "$T" ] && { echo LOGIN_FAILED; exit 1; }
echo "token_ok"

TS=$(date +%H%M%S)
echo "== 1) 创建新项目 =="
curl -s -o /tmp/eo_create -w 'http=%{http_code}\n' -X POST "$BASE/api/projects" \
  -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d "{\"name\":\"E2E-ORCH-$TS\",\"project_mode\":\"NEW\"}"
WS=$(python3 -c "import json;print(json.load(open('/tmp/eo_create')).get('id',''))")
[ -z "$WS" ] && { echo CREATE_FAILED; head -c 300 /tmp/eo_create; echo; exit 1; }
echo "ws=$WS"

echo "== 2) 初始 stage-execution（预期 NO_PLAN / 无 steps）=="
curl -s -o /tmp/eo_exec0 -w 'http=%{http_code}\n' "$BASE/api/projects/$WS/stage-execution" -H "Authorization: Bearer $T"
python3 -c "
import json
d = json.load(open('/tmp/eo_exec0'))
print('  state=%s steps=%s' % (d.get('state'), len(d.get('steps') or [])))"

echo "== 3) 第一句话（新内核 orchestrator.chat，SSE 最多 300s）=="
curl -sS -N --max-time 300 -X POST "$BASE/api/projects/$WS/stage/chat" \
  -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"message":"我要做一个图书管理小系统：支持录入图书、按书名搜索、借阅登记。请规划本阶段步骤并开始执行第一步。"}' \
  > /tmp/eo_sse.txt 2>/tmp/eo_sse.err
echo "curl_exit=$? bytes=$(wc -c < /tmp/eo_sse.txt)"
grep -a '^event:' /tmp/eo_sse.txt | sort | uniq -c | head -8

echo "== 4) 轮询 stage-execution（6 次 x 20s，看 steps 台账）=="
for i in 1 2 3 4 5 6; do
  sleep 20
  curl -s -o /tmp/eo_exec1 "$BASE/api/projects/$WS/stage-execution" -H "Authorization: Bearer $T"
  python3 -c "
import json
d = json.load(open('/tmp/eo_exec1'))
steps = d.get('steps') or []
print('  poll: state=%s steps=%s' % (d.get('state'), len(steps)))
for s in steps:
    print('    #%s [%s] %s status=%s agent=%s out=%s' % (
        s.get('seq'), s.get('stepType'), (s.get('name') or '')[:24],
        s.get('status'), s.get('agent'), ((s.get('outputSummary') or '')[:40])))
" 
  HAS_ACTIVE=$(python3 -c "
import json
d = json.load(open('/tmp/eo_exec1'))
steps = d.get('steps') or []
print('yes' if any(s.get('status') in ('EXECUTING','WAITING') for s in steps) else 'no')")
  [ "$HAS_ACTIVE" = "no" ] && [ "$(python3 -c "import json;print(len(json.load(open('/tmp/eo_exec1')).get('steps') or []))")" != "0" ] && break
done

echo "== 5) 会话历史（agent_state 回放通道）=="
curl -s -o /tmp/eo_hist -w 'http=%{http_code}\n' "$BASE/api/projects/$WS/stage/chat/history?stage=REQUIREMENT&iteration=1" -H "Authorization: Bearer $T"
python3 -c "
import json
d = json.load(open('/tmp/eo_hist'))
msgs = d.get('messages') or []
print('  history messages=%s' % len(msgs))
for m in msgs[-3:]:
    print('    [%s] %s' % (m.get('role'), ((m.get('text') or '')[:60]).replace(chr(10),' ')))"

echo "WS=$WS"
echo E2E_ORCH_DONE
