ALTER TABLE managed_accounts ADD COLUMN detection_models TEXT;
ALTER TABLE managed_accounts ADD COLUMN remote_enabled BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE managed_accounts SET remote_enabled = FALSE WHERE disabled_by_guard = TRUE OR status = 'DISABLED';
ALTER TABLE detection_results ADD COLUMN model VARCHAR(200);
