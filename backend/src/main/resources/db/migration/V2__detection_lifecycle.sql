ALTER TABLE managed_accounts ADD COLUMN monitoring BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE managed_accounts ADD COLUMN source_present BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE managed_accounts ADD COLUMN disabled_by_guard BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE managed_accounts ADD COLUMN pass_streak INTEGER NOT NULL DEFAULT 0;
ALTER TABLE detection_runs ADD COLUMN error_accounts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE detection_results ADD COLUMN outcome VARCHAR(16) NOT NULL DEFAULT 'PASS';
ALTER TABLE detection_results ADD COLUMN question TEXT;
ALTER TABLE detection_results ADD COLUMN expected_answer TEXT;
UPDATE detection_results SET outcome = CASE WHEN passed THEN 'PASS' ELSE 'FAIL' END;
ALTER TABLE detection_results DROP CONSTRAINT detection_results_policy_id_fkey;
ALTER TABLE detection_results ADD CONSTRAINT detection_results_policy_id_fkey FOREIGN KEY (policy_id) REFERENCES check_policies(id) ON DELETE SET NULL;
-- Retain old demo history but never send synthetic IDs to production adapters.
UPDATE managed_accounts SET monitoring = FALSE, source_present = FALSE
WHERE id IN ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa1842', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa1771',
'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa1698', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0914',
'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0862', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0740');
UPDATE check_policies SET active = FALSE WHERE id IN ('33333333-3333-3333-3333-333333333301',
'33333333-3333-3333-3333-333333333302', '33333333-3333-3333-3333-333333333303');
