ALTER TABLE managed_accounts ADD COLUMN upstream_models TEXT;
ALTER TABLE managed_accounts ADD COLUMN models_synced_at TIMESTAMPTZ;
