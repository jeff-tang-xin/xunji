#!/bin/bash
PW=$(grep 'password:' /data/xuanji/application.yml | head -1 | sed 's/.*password: *//' | tr -d '\r')
export PGPASSWORD="$PW"
PSQL="psql -h localhost -U postgres -d xuanji -t -A"
echo "== DEVELOP steps: name | req_codes | status =="
$PSQL -c "SELECT seq || ' | ' || left(name,24) || ' | codes=' || coalesce(requirement_codes::text,'null') || ' | ' || status FROM project_stage_step WHERE workspace_id='ws_504237daf32c4310824f' AND stage='DEVELOP' AND iteration=1 ORDER BY seq"
echo "== counts =="
$PSQL -c "SELECT stage || ': ' || count(*) || ' steps (' || count(*) FILTER (WHERE requirement_codes IS NULL OR requirement_codes::text IN ('[]','null')) || ' unlinked)' FROM project_stage_step WHERE workspace_id='ws_504237daf32c4310824f' GROUP BY stage"
echo REQ_CHECK_DONE
