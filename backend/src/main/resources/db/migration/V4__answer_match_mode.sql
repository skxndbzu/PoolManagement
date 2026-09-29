ALTER TABLE check_policies ADD COLUMN match_mode VARCHAR(16) NOT NULL DEFAULT 'FUZZY'
    CHECK (match_mode IN ('FUZZY', 'EXACT'));
-- 历史记录没有保存匹配方式，保留 NULL，避免按新策略解释旧结果。
ALTER TABLE detection_results ADD COLUMN match_mode VARCHAR(16)
    CHECK (match_mode IN ('FUZZY', 'EXACT'));
