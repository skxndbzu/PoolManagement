-- 检测通过一轮即恢复系统隔离的账号；以后仍可在系统设置调整观察次数。
INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('restore_passes', '1', NOW())
ON CONFLICT (setting_key) DO UPDATE SET setting_value = '1', updated_at = NOW();
