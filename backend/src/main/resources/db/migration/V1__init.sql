CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE managed_accounts (
    id UUID PRIMARY KEY,
    external_account_id VARCHAR(128) NOT NULL,
    email_masked VARCHAR(255) NOT NULL,
    project_code VARCHAR(64) NOT NULL REFERENCES projects(code),
    model VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    score INTEGER NOT NULL DEFAULT 0,
    latency_ms INTEGER,
    check_count INTEGER NOT NULL DEFAULT 0,
    last_check_at TIMESTAMPTZ,
    next_check_at TIMESTAMPTZ,
    last_failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_account_project_external UNIQUE (project_code, external_account_id)
);

CREATE INDEX idx_managed_accounts_status ON managed_accounts(status);
CREATE INDEX idx_managed_accounts_next_check ON managed_accounts(next_check_at);

CREATE TABLE check_policies (
    id UUID PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    expected_answer TEXT NOT NULL,
    capability_tag VARCHAR(128) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE detection_runs (
    id UUID PRIMARY KEY,
    trigger_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_accounts INTEGER NOT NULL DEFAULT 0,
    passed_accounts INTEGER NOT NULL DEFAULT 0,
    failed_accounts INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    error_message TEXT
);

CREATE TABLE detection_results (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES detection_runs(id),
    account_id UUID NOT NULL REFERENCES managed_accounts(id),
    policy_id UUID REFERENCES check_policies(id),
    passed BOOLEAN NOT NULL,
    score INTEGER NOT NULL,
    latency_ms INTEGER,
    answer_excerpt TEXT,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_detection_results_run ON detection_results(run_id);
CREATE INDEX idx_detection_results_account ON detection_results(account_id, created_at DESC);

CREATE TABLE app_settings (
    setting_key VARCHAR(128) PRIMARY KEY,
    setting_value VARCHAR(1000) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO projects (id, code, name, base_url) VALUES
    ('11111111-1111-1111-1111-111111111111', 'sub2api', 'sub2api', 'http://127.0.0.1:8081'),
    ('22222222-2222-2222-2222-222222222222', 'codex-proxy-rs', 'codex-proxy-rs', 'http://127.0.0.1:8080')
ON CONFLICT (code) DO NOTHING;

INSERT INTO managed_accounts (id, external_account_id, email_masked, project_code, model, status, score, latency_ms, check_count, last_check_at, next_check_at) VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa1842', 'sub-1842', 'lin•••@gmail.com', 'sub2api', 'gpt-5.2', 'HEALTHY', 98, 1800, 12, NOW() - INTERVAL '12 minutes', NOW()),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa1771', 'sub-1771', 'wen•••@outlook.com', 'sub2api', 'gpt-5.2', 'DEGRADED', 42, NULL, 10, NOW() - INTERVAL '15 minutes', NOW()),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa1698', 'sub-1698', 'zhao•••@gmail.com', 'sub2api', 'claude-4.1', 'HEALTHY', 96, 2100, 12, NOW() - INTERVAL '18 minutes', NOW()),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0914', 'cpr-0914', 'al•••@proton.me', 'codex-proxy-rs', 'gpt-5.2', 'HEALTHY', 100, 1400, 12, NOW() - INTERVAL '23 minutes', NOW()),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0862', 'cpr-0862', 'mo•••@icloud.com', 'codex-proxy-rs', 'gpt-5.2-codex', 'RECOVERING', 76, 2900, 8, NOW() - INTERVAL '30 minutes', NOW()),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0740', 'cpr-0740', 'qi•••@gmail.com', 'codex-proxy-rs', 'o3-pro', 'DISABLED', 18, NULL, 7, NOW() - INTERVAL '12 hours', NULL)
ON CONFLICT (project_code, external_account_id) DO NOTHING;

INSERT INTO check_policies (id, title, expected_answer, capability_tag, sort_order) VALUES
    ('33333333-3333-3333-3333-333333333301', '解释一道三阶微分方程的通解', '应出现特征根、通解结构与边界条件讨论', '高阶推理能力', 1),
    ('33333333-3333-3333-3333-333333333302', '比较两个并发方案的尾延迟', '应给出 P95/P99、锁竞争与吞吐的分析', '工程分析能力', 2),
    ('33333333-3333-3333-3333-333333333303', '根据约束生成一段安全 SQL', '应包含参数化、事务边界与索引建议', '代码生成能力', 3)
ON CONFLICT DO NOTHING;

INSERT INTO app_settings (setting_key, setting_value) VALUES
    ('schedule_value', '5'),
    ('schedule_unit', 'minutes'),
    ('restore_passes', '2'),
    ('next_run_at', NOW()::TEXT)
ON CONFLICT (setting_key) DO NOTHING;
