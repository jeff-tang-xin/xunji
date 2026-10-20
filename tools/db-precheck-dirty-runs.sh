#!/usr/bin/env bash
# 脏数据删除【预检】：只读，确认范围/外键/行数，不写任何东西。
# 用法：XJ_DB_PW=*** bash this.sh   （口令由调用方从 application.yml 提取，不落盘）
set -u
# 连接参数由包装脚本从 application.yml 现场提取后 export（不落盘、不打印）
DBH="${DBH:-localhost}"
DBU="${DBU:-xuanji}"
DBN="${DBN:-xuanji}"
: "${XJ_DB_PW:?missing XJ_DB_PW}"
export PGPASSWORD="$XJ_DB_PW"
DBPORT="${DBPORT:-5432}"
PSQL="psql -h $DBH -p $DBPORT -U $DBU -d $DBN -v ON_ERROR_STOP=1 -X -q"

RUNS="'r_a1a9a49f098447708a065107','r_5a4f09ab26e44ae49a9c224e','r_85b2ed7b5a95499688be11d0'"

echo "=== 0) 连接与库信息 ==="
$PSQL -c "SELECT current_database(), version();"

echo "=== 1) 待删的 3 条 run（必须恰好 3 行，且状态为 FAILED/FAILED/RUNNING）==="
$PSQL -c "SET TIME ZONE 'Asia/Shanghai';
SELECT id, status, coalesce(gate_node_id,'-') AS gate, trigger_type,
       left(coalesce(error,'(NULL)'),60) AS err, started_at, finished_at
FROM wf_run WHERE id IN ($RUNS) ORDER BY started_at;"
$PSQL -tAc "SELECT 'to_delete_runs='||count(*) FROM wf_run WHERE id IN ($RUNS);"

echo "=== 2) 全库 run 概览（确认不会误伤：应看到 r_c8359590 等 SUCCESS 不在删除集里）==="
$PSQL -c "SET TIME ZONE 'Asia/Shanghai';
SELECT id, status, trigger_type, started_at FROM wf_run ORDER BY started_at;"

echo "=== 3) 级联行数预估 ==="
$PSQL -c "SELECT 'wf_node_run' AS tbl, count(*) FROM wf_node_run WHERE run_id IN ($RUNS)
UNION ALL SELECT 'wf_decision', count(*) FROM wf_decision WHERE run_id IN ($RUNS);"

echo "=== 4) 谁引用 wf_run（外键清单，决定删除顺序 / 是否有未知依赖）==="
$PSQL -c "SELECT c.conrelid::regclass AS child_table, a.attname AS child_col,
       CASE c.confdeltype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT'
             WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' ELSE c.confdeltype::text END AS on_delete
FROM pg_constraint c
JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum = ANY(c.conkey)
WHERE c.confrelid='wf_run'::regclass ORDER BY 1;"

echo "=== 5) 这 3 条 run 是否被别人当父 run 引用（子工作流嵌套）==="
$PSQL -c "SELECT id, status FROM wf_run WHERE parent_run_id IN ($RUNS);"

echo "=== 6) 关联的规划行（删除后要退回 PENDING 的那些）==="
$PSQL -c "SET TIME ZONE 'Asia/Shanghai';
SELECT id, workspace_id, stage, iteration, status,
       coalesce(exec_definition_id::text,'-') AS def_id, coalesce(exec_run_id,'-') AS run_id, created_at
FROM project_stage_plan WHERE exec_run_id IN ($RUNS) ORDER BY id;"
$PSQL -tAc "SELECT 'plans_to_reset='||count(*) FROM project_stage_plan WHERE exec_run_id IN ($RUNS);"

echo "=== 7) 这 3 个项目的其它规划/总结（确认退回后能重新批准、不会撞唯一约束）==="
$PSQL -c "SET TIME ZONE 'Asia/Shanghai';
SELECT id, workspace_id, stage, iteration, status, coalesce(exec_run_id,'-') AS run_id
FROM project_stage_plan
WHERE workspace_id IN ('ws_149cad1e50594394b0eb','ws_93cef5004a5347bfbcc6','ws_d5a4bae98702456bbbb9')
ORDER BY workspace_id, id;"
$PSQL -c "SELECT id, workspace_id, stage, iteration, status FROM project_stage_summary
WHERE workspace_id IN ('ws_149cad1e50594394b0eb','ws_93cef5004a5347bfbcc6','ws_d5a4bae98702456bbbb9')
ORDER BY workspace_id, id;"

echo "=== 8) project_stage_plan 上是否有唯一约束（影响重批准）==="
$PSQL -c "SELECT conname, conrelid::regclass AS tbl,
  (SELECT string_agg(a.attname, ',' ORDER BY a.attnum) FROM pg_attribute a
    WHERE a.attrelid=conrelid AND a.attnum = ANY(conkey)) AS cols, contype
FROM pg_constraint WHERE conrelid IN ('project_stage_plan'::regclass,'workspace'::regclass)
  AND contype IN ('u','p') ORDER BY 2, contype, 1;"

echo "PRECHECK_DONE"
