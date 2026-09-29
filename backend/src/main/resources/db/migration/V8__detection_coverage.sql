-- 历史批次无法还原当时的模型清单，因此不回填推测的覆盖数量。
ALTER TABLE detection_runs ADD COLUMN planned_checks BIGINT;
ALTER TABLE detection_runs ADD COLUMN completed_checks BIGINT;
