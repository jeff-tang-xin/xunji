#!/bin/bash
PW=$(grep 'password:' /data/xuanji/application.yml | head -1 | sed 's/.*password: *//' | tr -d '\r')
export PGPASSWORD="$PW"
PSQL="psql -h localhost -U postgres -d xuanji -t -A"
echo "== RUNNING runs =="
$PSQL -c "SELECT id || ' | ' || status || ' | ' || coalesce(error,'') FROM wf_run WHERE status IN ('RUNNING','SUSPENDED') ORDER BY started_at DESC LIMIT 10"
echo "== EXECUTING steps =="
$PSQL -c "SELECT seq || ' | ' || name || ' | ' || status FROM project_stage_step WHERE status='EXECUTING' LIMIT 10"
echo CHECK_DONE
