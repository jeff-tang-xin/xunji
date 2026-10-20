#!/bin/bash
PW=$(grep 'password:' /data/xuanji/application.yml | head -1 | sed 's/.*password: *//' | tr -d '\r')
export PGPASSWORD="$PW"
PSQL="psql -h localhost -U postgres -d xuanji -t -A"
echo "== seq=18 rows =="
$PSQL -c "SELECT workspace_id || ' | ' || stage || ' | iter ' || iteration || ' | seq ' || seq || ' | ' || status || ' | ' || left(name,30) FROM project_stage_step WHERE seq=18 ORDER BY id DESC LIMIT 5"
echo "== ws_5042 stage/iter distribution =="
$PSQL -c "SELECT stage || ' iter ' || iteration || ': ' || min(seq) || '-' || max(seq) || ' (' || count(*) || ' steps)' FROM project_stage_step WHERE workspace_id='ws_504237daf32c4310824f' GROUP BY stage, iteration ORDER BY stage, iteration"
echo "== ws current_stage =="
$PSQL -c "SELECT id || ' | ' || status || ' | ' || left(coalesce(stages_json,''),80) FROM workspace WHERE id='ws_504237daf32c4310824f'"
echo "== seq duplicates? =="
$PSQL -c "SELECT seq || ' x' || count(*) FROM project_stage_step WHERE workspace_id='ws_504237daf32c4310824f' GROUP BY seq HAVING count(*)>1 LIMIT 5"
echo "== FAILED steps recent =="
$PSQL -c "SELECT stage || ' iter ' || iteration || ' seq ' || seq || ' | ' || left(name,26) || ' | ' || left(coalesce(error,''),40) FROM project_stage_step WHERE workspace_id='ws_504237daf32c4310824f' AND status='FAILED' ORDER BY id DESC LIMIT 5"
echo SEQ_CHECK_DONE
