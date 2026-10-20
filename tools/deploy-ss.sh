#!/bin/bash
# 部署脚本 v2：ss 判活唯一真相（pgrep -f 匹配 java 进程不可靠，弃用）
set -e
JAR=/data/xuanji/xuanji-server-0.1.0-SNAPSHOT.jar
PID=$(ss -tlnp | grep ':8321' | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -n "$PID" ]; then
  echo "KILLING_LISTENER_$PID"
  kill "$PID"
  for i in $(seq 1 30); do
    ss -tlnp | grep -q ':8321' || break
    sleep 1
  done
  if ss -tlnp | grep -q ':8321'; then
    echo "PORT_STILL_BUSY"
    exit 1
  fi
  echo "PORT_RELEASED"
else
  echo "NO_LISTENER"
fi
mv /tmp/new.jar "$JAR"
echo "JAR_REPLACED"
cd /data/xuanji && ./start.sh start
echo "START_ISSUED"
