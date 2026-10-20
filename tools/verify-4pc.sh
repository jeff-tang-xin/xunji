#!/usr/bin/env bash
# 部署四件套验证：监听 PID / bundle 名 / jar md5 / login probe
echo '--- 1) listen ---'
ss -tlnp | grep 8321
echo '--- 2) bundle ---'
curl -s http://127.0.0.1:8321/ | grep -o 'index-[A-Za-z0-9_-]*\.js'
echo '--- 3) md5 ---'
md5sum /data/xuanji/xuanji-server-0.1.0-SNAPSHOT.jar
echo '--- 4) login probe ---'
curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8321/api/auth/login -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'
echo ''
echo VERIFY_SENTINEL_4PC
