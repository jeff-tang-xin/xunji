-- V14: wf_run 加决策门超时字段 —— onTimeout 自动处置（决策门最后一个维度）
-- 挂起时按 gate 的 timeout（"30s"/"24h"…）算出 gate_deadline 落库；
-- GateTimeoutScheduler 周期扫 status='SUSPENDED' 且 gate_deadline 到期的 run，
-- 按该 gate 的 onTimeout 策略（APPROVE/REJECT/ESCALATE）自动处置。
-- gate_node_id 冗余存储，供调度器直接定位 gate（免于每次解析快照）。
ALTER TABLE wf_run ADD COLUMN gate_deadline TIMESTAMPTZ;
ALTER TABLE wf_run ADD COLUMN gate_node_id  VARCHAR(128);