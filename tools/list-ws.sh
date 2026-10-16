#!/usr/bin/env bash
set -u
PG_P=$(awk '/datasource:/{f=1} f && /password:/{print $2; exit}' /data/xuanji/application.yml | tr -d '\r')
export PGPASSWORD="$PG_P"
psql -h 127.0.0.1 -U postgres -d xuanji -tAc "SELECT column_name FROM information_schema.columns WHERE table_name='workspace' ORDER BY ordinal_position;" | tr '\n' ' '
echo ''
psql -h 127.0.0.1 -U postgres -d xuanji -tAc "SELECT id || ' | ' || name FROM workspace ORDER BY created_at DESC LIMIT 5;"
echo LIST_WS2_DONE
