CREATE TABLE operations (
    id UUID PRIMARY KEY,
    jira_id VARCHAR(255) NOT NULL,
    revision INTEGER NOT NULL CHECK (revision >= 1),
    status VARCHAR(64) NOT NULL,
    validation_run_id UUID,
    plan_id UUID,
    approved_plan_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE official_accounts (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES operations(id) ON DELETE CASCADE,
    oa_order INTEGER NOT NULL CHECK (oa_order >= 1),
    platform VARCHAR(32) NOT NULL CHECK (platform = 'ANDROID'),
    oa_name VARCHAR(255) NOT NULL,
    thumbnail_url TEXT NOT NULL,
    content TEXT NOT NULL,
    button_text TEXT NOT NULL,
    redirect_url TEXT NOT NULL,
    CONSTRAINT official_accounts_operation_order_unique UNIQUE (operation_id, oa_order)
);

CREATE TABLE validation_runs (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES operations(id) ON DELETE CASCADE,
    source_revision INTEGER NOT NULL CHECK (source_revision >= 1),
    policy_version VARCHAR(128) NOT NULL,
    model VARCHAR(255),
    status VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE field_findings (
    id UUID PRIMARY KEY,
    validation_run_id UUID NOT NULL REFERENCES validation_runs(id) ON DELETE CASCADE,
    field_name VARCHAR(128) NOT NULL,
    validator_type VARCHAR(128) NOT NULL,
    status VARCHAR(64) NOT NULL,
    issue TEXT,
    location TEXT,
    suggestion TEXT,
    severity VARCHAR(64),
    confidence NUMERIC(4, 3)
);

CREATE TABLE test_plans (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES operations(id) ON DELETE CASCADE,
    source_revision INTEGER NOT NULL CHECK (source_revision >= 1),
    template_catalog_version VARCHAR(128) NOT NULL,
    status VARCHAR(64) NOT NULL,
    approval_status VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE operations
    ADD CONSTRAINT operations_validation_run_id_fk FOREIGN KEY (validation_run_id) REFERENCES validation_runs(id),
    ADD CONSTRAINT operations_plan_id_fk FOREIGN KEY (plan_id) REFERENCES test_plans(id),
    ADD CONSTRAINT operations_approved_plan_id_fk FOREIGN KEY (approved_plan_id) REFERENCES test_plans(id);

CREATE TABLE test_cases (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES test_plans(id) ON DELETE CASCADE,
    oa_order INTEGER NOT NULL CHECK (oa_order >= 1),
    case_order INTEGER NOT NULL CHECK (case_order >= 1),
    template_id VARCHAR(255) NOT NULL,
    template_version INTEGER NOT NULL CHECK (template_version >= 1),
    parameters JSONB NOT NULL,
    status VARCHAR(64) NOT NULL,
    CONSTRAINT test_cases_plan_order_unique UNIQUE (plan_id, oa_order, case_order)
);

CREATE TABLE hubs (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    connection_status VARCHAR(64) NOT NULL,
    transport VARCHAR(64) NOT NULL,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE,
    device_ready BOOLEAN NOT NULL DEFAULT FALSE,
    runner_ready BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE job_leases (
    id UUID PRIMARY KEY,
    hub_id UUID NOT NULL REFERENCES hubs(id),
    execution_id UUID NOT NULL,
    lease_token UUID NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE executions (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES operations(id),
    plan_id UUID NOT NULL REFERENCES test_plans(id),
    hub_id UUID REFERENCES hubs(id),
    source_revision INTEGER NOT NULL CHECK (source_revision >= 1),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(64) NOT NULL,
    queued_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE
);

ALTER TABLE job_leases
    ADD CONSTRAINT job_leases_execution_id_fk FOREIGN KEY (execution_id) REFERENCES executions(id) ON DELETE CASCADE;

CREATE TABLE test_results (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
    test_case_id UUID NOT NULL REFERENCES test_cases(id),
    attempt INTEGER NOT NULL CHECK (attempt >= 1),
    status VARCHAR(64) NOT NULL,
    duration_ms INTEGER NOT NULL CHECK (duration_ms >= 0),
    error_category VARCHAR(64),
    expected_result TEXT,
    actual_result TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT test_results_execution_case_attempt_unique UNIQUE (execution_id, test_case_id, attempt)
);

CREATE TABLE evidence (
    id UUID PRIMARY KEY,
    test_result_id UUID NOT NULL REFERENCES test_results(id) ON DELETE CASCADE,
    evidence_type VARCHAR(64) NOT NULL,
    relative_path TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    checksum VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX validation_runs_operation_revision_idx ON validation_runs(operation_id, source_revision);
CREATE INDEX test_plans_operation_revision_idx ON test_plans(operation_id, source_revision);
CREATE INDEX executions_operation_revision_idx ON executions(operation_id, source_revision);
CREATE INDEX job_leases_hub_expires_idx ON job_leases(hub_id, expires_at);
