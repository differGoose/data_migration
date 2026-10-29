-- Apply manually to the TARGET database. Engine never executes this DDL.
CREATE TABLE migration_audit (
    job_id VARCHAR(120) NOT NULL,
    task_name VARCHAR(200) NOT NULL,
    batch_no BIGINT NOT NULL,
    target_table VARCHAR(300) NOT NULL,
    state VARCHAR(32) NOT NULL,
    lower_key TEXT,
    upper_key TEXT,
    expected_rows BIGINT NOT NULL DEFAULT 0,
    committed_rows BIGINT NOT NULL DEFAULT 0,
    skipped_rows BIGINT NOT NULL DEFAULT 0,
    processed_rows BIGINT NOT NULL DEFAULT 0,
    retries INTEGER NOT NULL DEFAULT 0,
    total_rows BIGINT NOT NULL DEFAULT 0,
    prepared BOOLEAN NOT NULL DEFAULT FALSE,
    planning_complete BOOLEAN NOT NULL DEFAULT FALSE,
    data_complete BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(250),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (job_id, task_name, batch_no),
    CHECK (committed_rows >= 0 AND skipped_rows >= 0 AND retries >= 0)
);
CREATE INDEX migration_audit_target_idx ON migration_audit(target_table);
