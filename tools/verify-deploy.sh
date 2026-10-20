#!/bin/bash
echo "== ss listener =="
ss -tlnp | grep ':8321'
echo "== md5 =="
md5sum /data/xuanji/xuanji-server-0.1.0-SNAPSHOT.jar | awk '{print $1}'
echo "== bundle name =="
curl -s http://127.0.0.1:8321/ | grep -oP 'index-[A-Za-z0-9_-]+\.js' | head -1
echo "== tomcat started =="
grep -c 'Tomcat started' /data/xuanji/xuanji.log
echo "== error count (last 200 lines) =="
tail -200 /data/xuanji/xuanji.log | grep -c ERROR
echo "== flyway =="
PW=$(grep 'password:' /data/xuanji/application.yml | head -1 | sed 's/.*password: *//' | tr -d '\r')
export PGPASSWORD="$PW"
psql -h localhost -U postgres -d xuanji -t -A -c "SELECT version FROM flyway_schema_history ORDER BY length(version) DESC, version DESC LIMIT 1"
echo VERIFY_DONE
