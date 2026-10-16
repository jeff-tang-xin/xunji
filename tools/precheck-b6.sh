#!/usr/bin/env bash
# 批五+批六部署前预检：有无真在跑的 run（RUNNING/SUSPENDED）
set -u
PG_P=$(awk '/datasource:/{f=1} f && /password:/{print $2; exit}' /data/xuanji/application.yml | tr -d '\r')
export PGPASSWORD="$PG_P"
PSQL="psql -h 127.0.0.1 -U postgres -d xuanji -tAc"
echo "=== ACTIVE runs (RUNNING/SUSPENDED) ==="
$PSQL "SELECT id || ' | ' || status || ' | started ' || started_at::timestamp(0) FROM wf_run WHERE status IN ('RUNNING','SUSPENDED');"
echo "=== current jar md5 ==="
md5sum /data/xuanji/xuanji-server-0.1.0-SNAPSHOT.jar | cut -c1-8
echo "=== listening pid ==="
ss -tlnp | grep 8321 | grep -o 'pid=[0-9]*' | head -1
echo PRECHECK_B6_DONE
