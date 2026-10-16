#!/usr/bin/env bash
set -u
PG_P=$(awk '/datasource:/{f=1} f && /password:/{print $2; exit}' /data/xuanji/application.yml | tr -d '\r')
export PGPASSWORD="$PG_P"
PSQL="psql -h 127.0.0.1 -U postgres -d xuanji -tAc"
echo "=== flyway latest ==="
$PSQL "SELECT version || ' | ' || description || ' | ' || success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 2;"
echo "=== builtin col ==="
$PSQL "SELECT column_name FROM information_schema.columns WHERE table_name='agent_config' AND column_name='builtin_tools_json';"
echo "=== startup errors ==="
grep -a -E 'ERROR|APPLICATION FAILED' /data/xuanji/xuanji.log | tail -5
echo "=== started at ==="
grep -a 'Started XuanjiServerApplication' /data/xuanji/xuanji.log | tail -1
echo VERIFY_B6_DB_DONE
