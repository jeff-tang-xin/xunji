#!/usr/bin/env bash
set -u
WS=${1:?ws required}
TOKEN=$(curl -s -X POST http://127.0.0.1:8321/api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Li5710246."}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s -N --max-time 240 -X POST "http://127.0.0.1:8321/api/projects/${WS}/stage/chat" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"message":"请用文件读取工具查看项目目录下有哪些文件，把文件清单告诉我"}' > /tmp/s3-stream.txt 2>&1 || true
echo "=== stream events ==="
grep -a -E '^event:' /tmp/s3-stream.txt | sort | uniq -c
echo "=== tool frames ==="
grep -a -E 'tool\.(call|result)|view_text_file|list_directory' /tmp/s3-stream.txt | head -10
echo "=== text tail ==="
grep -a '^data:' /tmp/s3-stream.txt | tail -5
echo SMOKE_B6_S3_DONE
