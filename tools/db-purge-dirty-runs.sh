#!/usr/bin/env bash
# 脏数据删除【执行】：删除修复前遗留的 3 条阶段 run，并把关联规划退回 PENDING，
# 让 ws_149cad / ws_93cef / ws_d5a4bae 三个项目能重新「批准并起跑」。
#
# 为什么不能只删 run：推进门要求 plan.exec_run_id 指向 SUCCESS 的 run。只删 run 会让
# exec_run_id 悬空——项目照样卡死，还多了一处「指向不存在对象」的脏引用。
# 为什么必须手删子表：预检确认 wf_run 上没有任何外键引用，删除不会级联，
# 漏删就留下 11 行 wf_node_run + 4 行 wf_decision 的孤儿。
#
# 纪律：先备份再动手；全程单事务；每步核对行数，不符立即回滚。
set -u
DBH="${DBH:-localhost}"; DBU="${DBU:-xuanji}"; DBN="${DBN:-xuanji}"; DBPORT="${DBPORT:-5432}"
: "${XJ_DB_PW:?missing XJ_DB_PW}"
export PGPASSWORD="$XJ_DB_PW"
PSQL="psql -h $DBH -p $DBPORT -U $DBU -d $DBN -v ON_ERROR_STOP=1 -X -q"
RUNS="'r_a1a9a49f098447708a065107','r_5a4f09ab26e44ae49a9c224e','r_85b2ed7b5a95499688be11d0'"
STAMP=$(date +%Y%m%d-%H%M%S)
BAK=/tmp/dirty-runs-backup-$STAMP
mkdir -p "$BAK"

echo "=== 0) 备份到 $BAK（删除前唯一回滚依据，勿删）==="
$PSQL -c "\copy (SELECT * FROM wf_run WHERE id IN ($RUNS)) TO '$BAK/wf_run.csv' CSV HEADER"
$PSQL -c "\copy (SELECT * FROM wf_node_run WHERE run_id IN ($RUNS)) TO '$BAK/wf_node_run.csv' CSV HEADER"
$PSQL -c "\copy (SELECT * FROM wf_decision WHERE run_id IN ($RUNS)) TO '$BAK/wf_decision.csv' CSV HEADER"
$PSQL -c "\copy (SELECT * FROM project_stage_plan WHERE exec_run_id IN ($RUNS)) TO '$BAK/project_stage_plan.csv' CSV HEADER"
ls -l "$BAK"
echo "backup rows: run=$($PSQL -tAc "SELECT count(*) FROM wf_run WHERE id IN ($RUNS)") node=$($PSQL -tAc "SELECT count(*) FROM wf_node_run WHERE run_id IN ($RUNS)") dec=$($PSQL -tAc "SELECT count(*) FROM wf_decision WHERE run_id IN ($RUNS)") plan=$($PSQL -tAc "SELECT count(*) FROM project_stage_plan WHERE exec_run_id IN ($RUNS)")"

echo "=== 1) 前置断言：必须 3 run / 11 node_run / 4 decision / 3 plan，否则不动数据 ==="
ACT=$($PSQL -tAc "SELECT count(*) FROM wf_run WHERE id IN ($RUNS)")
[ "$ACT" = "3" ] || { echo "ABORT: runs=$ACT expected 3"; exit 1; }
ACT=$($PSQL -tAc "SELECT count(*) FROM wf_node_run WHERE run_id IN ($RUNS)")
[ "$ACT" = "11" ] || { echo "ABORT: node_runs=$ACT expected 11"; exit 1; }
ACT=$($PSQL -tAc "SELECT count(*) FROM project_stage_plan WHERE exec_run_id IN ($RUNS) AND status='APPROVED'")
[ "$ACT" = "3" ] || { echo "ABORT: approved plans=$ACT expected 3"; exit 1; }
echo "assertions ok"

echo "=== 2) 单事务执行 ==="
$PSQL <<SQL
BEGIN;

-- ① 先把规划退回可批准态（必须趁 exec_run_id 还在时定位）
UPDATE project_stage_plan
   SET status='PENDING', exec_definition_id=NULL, exec_run_id=NULL,
       decided_by=NULL, decided_at=NULL, reject_reason=NULL
 WHERE exec_run_id IN ($RUNS);

-- ② 删子表（无外键级联，必须显式删）
DELETE FROM wf_node_run WHERE run_id IN ($RUNS);
DELETE FROM wf_decision WHERE run_id IN ($RUNS);
-- ③ 删父表
DELETE FROM wf_run WHERE id IN ($RUNS);

COMMIT;
SQL
echo "transaction committed"

echo "=== 3) 事后校验 ==="
$PSQL -tAc "SELECT 'wf_run_left='||count(*) FROM wf_run;"
$PSQL -tAc "SELECT 'target_runs_left='||count(*) FROM wf_run WHERE id IN ($RUNS);"
$PSQL -tAc "SELECT 'orphan_node_runs='||count(*) FROM wf_node_run WHERE run_id IN ($RUNS);"
$PSQL -tAc "SELECT 'orphan_decisions='||count(*) FROM wf_decision WHERE run_id IN ($RUNS);"
$PSQL -tAc "SELECT 'node_run_total='||count(*) FROM wf_node_run;"
$PSQL -c "SET TIME ZONE 'Asia/Shanghai';
SELECT id, status, coalesce(exec_run_id,'-') AS run_id, coalesce(exec_definition_id::text,'-') AS def_id
FROM project_stage_plan WHERE id IN (1,2,3) ORDER BY id;"
$PSQL -c "SELECT id, status, trigger_type FROM wf_run ORDER BY started_at;"
echo "backup kept at: $BAK"
echo PURGE_DONE
