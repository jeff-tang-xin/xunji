#!/bin/bash
PW=$(grep 'password:' /data/xuanji/application.yml | head -1 | sed 's/.*password: *//' | tr -d '\r')
export PGPASSWORD="$PW"
PSQL="psql -h localhost -U postgres -d xuanji -t -A"
echo "== DECISION steps (recent) =="
$PSQL -c "SELECT seq || ' | ' || status || ' | ' || left(name,40) || ' | ' || created_at FROM project_stage_step WHERE name LIKE '%验收%' OR name LIKE '%决策%' ORDER BY seq DESC LIMIT 8"
echo "== agent_state recent assistant turns (ws_504237daf32c4310824f) =="
$PSQL -c "SELECT id || ' | ' || left(payload::text, 300) FROM agent_state WHERE session_id LIKE 'proj-504237daf32c4310824f%' AND payload::text LIKE '%现已停在用户验收卡%' ORDER BY id DESC LIMIT 4"
echo "== waiting decisions =="
$PSQL -c "SELECT seq || ' | ' || status || ' | ' || left(name,50) FROM project_stage_step WHERE status='PLANNED' AND type='DECISION' ORDER BY seq DESC LIMIT 5"
echo DUP_CHECK_DONE
