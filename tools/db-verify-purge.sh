#!/usr/bin/env bash
# 删除后校验（只读）：确认无孤儿、规划已退回、备份文件完整
set -u
DBH="${DBH:-localhost}"; DBU="${DBU:-xuanji}"; DBN="${DBN:-xuanji}"; DBPORT="${DBPORT:-5432}"
: "${XJ_DB_PW:?missing XJ_DB_PW}"
export PGPASSWORD="$XJ_DB_PW"
PSQL="psql -h $DBH -p $DBPORT -U $DBU -d $DBN -v ON_ERROR_STOP=1 -X -q"
RUNS="'r_a1a9a49f098447708a065107','r_5a4f09ab26e44ae49a9c224e','r_85b2ed7b5a95499688be11d0'"

echo "=== 1) run 表现状（应只剩 3 条，且全 SUCCESS）==="
$PSQL -c "SET TIME ZONE 'Asia/Shanghai';
SELECT id, status, trigger_type, started_at FROM wf_run ORDER BY started_at;"

echo "=== 2) 孤儿检查（必须全 0）==="
$PSQL -c "SELECT 'target_runs_left' AS k, count(*) AS v FROM wf_run WHERE id IN ($RUNS)
UNION ALL SELECT 'orphan_node_runs', count(*) FROM wf_node_run WHERE run_id IN ($RUNS)
UNION ALL SELECT 'orphan_decisions', count(*) FROM wf_decision WHERE run_id IN ($RUNS)
UNION ALL SELECT 'node_run_total', count(*) FROM wf_node_run
UNION ALL SELECT 'node_run_orphans_all', count(*) FROM wf_node_run n
   WHERE NOT EXISTS (SELECT 1 FROM wf_run r WHERE r.id = n.run_id)
UNION ALL SELECT 'decision_orphans_all', count(*) FROM wf_decision d
   WHERE NOT EXISTS (SELECT 1 FROM wf_run r WHERE r.id = d.run_id);"

echo "=== 3) 三个项目的规划（应 status=PENDING、run_id/def_id 为空）==="
$PSQL -c "SELECT id, workspace_id, stage, iteration, status,
          coalesce(exec_run_id,'(NULL)') AS run_id,
          coalesce(exec_definition_id::text,'(NULL)') AS def_id
   FROM project_stage_plan WHERE id IN (1,2,3) ORDER BY id;"

echo "=== 4) 备份文件完整性 ==="
BAK=$(ls -d /tmp/dirty-runs-backup-* 2>/dev/null | tail -1)
echo "backup dir: $BAK"
for f in wf_run wf_node_run wf_decision project_stage_plan; do
  if [ -f "$BAK/$f.csv" ]; then
    echo "  $f.csv  lines=$(wc -l < "$BAK/$f.csv")  bytes=$(wc -c < "$BAK/$f.csv")"
  else
    echo "  $f.csv  MISSING !!!"
  fi
done

echo "=== 5) 定义表（旧阶段定义应仍在，已发布不可删）==="
$PSQL -c "SELECT id, left(name,40) AS name, status FROM wf_definition WHERE id IN (4,5,6) ORDER BY id;"
echo VERIFY_PURGE_DONE
