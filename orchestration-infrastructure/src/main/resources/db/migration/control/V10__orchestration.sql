-- Control plane: run headers, the append-only transition journal, and human decisions.

CREATE TABLE workflow_instances (
    run_id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    definition_name    VARCHAR(64)  NOT NULL,
    definition_version BIGINT       NOT NULL,
    policy_version     VARCHAR(32)  NOT NULL,
    state              VARCHAR(32)  NOT NULL,
    terminal_outcome   VARCHAR(512) NULL,
    created_at         TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    terminal_at        TIMESTAMP(9) WITH TIME ZONE NULL,
    input_json         CLOB         NULL
);

-- The journal is the source of truth. (run_id, seq) is unique, which is what stops a restarted
-- scheduler from appending a second copy of an event it already wrote.
CREATE TABLE workflow_journal (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id             VARCHAR(64)  NOT NULL,
    seq                BIGINT       NOT NULL,
    node_id            VARCHAR(64)  NULL,
    from_state         VARCHAR(32)  NULL,
    to_state           VARCHAR(32)  NULL,
    action             VARCHAR(64)  NOT NULL,
    actor_type         VARCHAR(16)  NOT NULL,
    actor_id           VARCHAR(128) NULL,
    result             VARCHAR(512) NULL,
    reason             VARCHAR(2000) NULL,
    definition_version BIGINT       NOT NULL,
    policy_version     VARCHAR(32)  NULL,
    occurred_at        TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    payload_json       CLOB         NULL,
    CONSTRAINT uq_journal_run_seq UNIQUE (run_id, seq)
);

CREATE INDEX idx_journal_run ON workflow_journal (run_id, seq);

CREATE TABLE approval_decisions (
    decision_id        VARCHAR(128) NOT NULL PRIMARY KEY,
    run_id             VARCHAR(64)  NOT NULL,
    gate_id            VARCHAR(64)  NOT NULL,
    decision_type      VARCHAR(32)  NOT NULL,
    actor              VARCHAR(128) NOT NULL,
    decision           VARCHAR(64)  NOT NULL,
    rationale          VARCHAR(2000) NOT NULL,
    conditions         VARCHAR(2000) NULL,
    affected_artifacts VARCHAR(2000) NULL,
    decided_at         TIMESTAMP(9) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_approvals_run ON approval_decisions (run_id, gate_id);
