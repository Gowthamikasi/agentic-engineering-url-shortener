-- Append-only audit trail with a per-run hash chain.
-- There is no UPDATE or DELETE path to this table anywhere in the code: the AuditSink port
-- exposes only append and read, so "append-only" is a property of the type as well as of intent.
CREATE TABLE audit_events (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id             VARCHAR(64)  NOT NULL,
    seq                BIGINT       NOT NULL,
    occurred_at        TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    actor_type         VARCHAR(16)  NOT NULL,
    actor_id           VARCHAR(128) NULL,
    action             VARCHAR(64)  NOT NULL,
    target_type        VARCHAR(32)  NULL,
    target_id          VARCHAR(128) NULL,
    result             VARCHAR(512) NULL,
    reason             VARCHAR(2000) NULL,
    policy_version     VARCHAR(32)  NULL,
    definition_version BIGINT       NOT NULL,
    prev_hash          VARCHAR(64)  NOT NULL,
    hash               VARCHAR(64)  NOT NULL,
    CONSTRAINT uq_audit_run_seq UNIQUE (run_id, seq)
);

CREATE INDEX idx_audit_run ON audit_events (run_id, seq);
