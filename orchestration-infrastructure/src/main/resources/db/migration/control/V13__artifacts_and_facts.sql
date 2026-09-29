-- Artifacts and run facts, so a completed run can still be inspected after a restart.
-- Without these the journal survives but the artifacts, lineage and gate context do not.

-- The artifact id is only unique inside a run: every run produces an "ingest:RawRequirement:v1".
-- The key is therefore the run and the artifact together.
CREATE TABLE workflow_artifacts (
    row_id             VARCHAR(340) NOT NULL PRIMARY KEY,
    artifact_id        VARCHAR(255) NOT NULL,
    run_id             VARCHAR(64)  NOT NULL,
    node_id            VARCHAR(64)  NOT NULL,
    artifact_type      VARCHAR(64)  NOT NULL,
    version            INT          NOT NULL,
    sha256             VARCHAR(64)  NOT NULL,
    content_json       CLOB         NULL,
    input_artifact_ids VARCHAR(2000) NULL,
    decision_ids       VARCHAR(2000) NULL,
    produced_at        TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    degraded           BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_artifacts_run_artifact ON workflow_artifacts (run_id, artifact_id);
CREATE INDEX idx_artifacts_run ON workflow_artifacts (run_id, node_id);

-- Facts drive branch conditions and the policy evaluation, so a rebuilt run needs them too.
ALTER TABLE workflow_instances ADD COLUMN facts_json CLOB NULL;
