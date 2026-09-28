ALTER TABLE managed_accounts ADD COLUMN fail_streak INTEGER NOT NULL DEFAULT 0;
ALTER TABLE managed_accounts ADD COLUMN guard_operation_id VARCHAR(36);
ALTER TABLE managed_accounts ADD COLUMN evaluation_fingerprint VARCHAR(64);
