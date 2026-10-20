#!/usr/bin/env bash
# 服务器侧包装：从 /data/xuanji/application.yml 现场提取 DB 连接参数并 export。
# 纪律：口令绝不 echo、绝不写文件、绝不进日志；只打印长度与脱敏摘要。
set -u
CFG=/data/xuanji/application.yml
[ -f "$CFG" ] || { echo NO_CONFIG; exit 1; }

PW=$(grep -a -m1 '^[[:space:]]*password:' "$CFG" | sed 's/^[[:space:]]*password:[[:space:]]*//' | tr -d '\r' | tr -d '"')
DBU=$(grep -a -m1 '^[[:space:]]*username:' "$CFG" | sed 's/^[[:space:]]*username:[[:space:]]*//' | tr -d '\r' | tr -d '"')
URL=$(grep -a -m1 '^[[:space:]]*url:' "$CFG" | sed 's/^[[:space:]]*url:[[:space:]]*//' | tr -d '\r')
[ -z "$PW" ] && { echo NO_PASSWORD_FOUND; exit 1; }

DBH=$(printf '%s' "$URL" | sed -n 's|jdbc:postgresql://\([^:/]*\).*|\1|p')
DBP=$(printf '%s' "$URL" | sed -n 's|jdbc:postgresql://[^:/]*:\([0-9]*\).*|\1|p')
DBN=$(printf '%s' "$URL" | sed -n 's|jdbc:postgresql://[^/]*/\([^?;]*\).*|\1|p')
[ -z "$DBP" ] && DBP=5432
[ -z "$DBH" ] && { echo CANNOT_PARSE_URL; exit 1; }

echo "resolved: host=$DBH port=$DBP db=$DBN user=$DBU pw_len=${#PW}"

export DBH DBU DBN
DBPORT="$DBP" XJ_DB_PW="$PW" bash "${1:-/tmp/pre.sh}"
