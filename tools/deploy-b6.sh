#!/usr/bin/env bash
# 批五+批六部署：kill 旧 PID（ss 实测）→ mv 新 jar → nohup 启动（日志落 xuanji.log）
set -u
cd /data/xuanji
PID=$(ss -tlnp | grep 8321 | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
echo "old pid: $PID"
[ -n "$PID" ] && kill "$PID" && sleep 3
mv -f xuanji-new.jar xuanji-server-0.1.0-SNAPSHOT.jar
md5sum xuanji-server-0.1.0-SNAPSHOT.jar | cut -c1-8
nohup java -Xmx512m -jar xuanji-server-0.1.0-SNAPSHOT.jar >> /data/xuanji/xuanji.log 2>&1 &
echo START_ISSUED
echo DEPLOY_B6_DONE
