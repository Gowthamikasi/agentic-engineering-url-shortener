-- Approved deviations from a mandatory rule. An expiry is mandatory in spirit: an exception with
-- no expiry and no review condition is how a temporary waiver becomes permanent.
CREATE TABLE policy_exceptions (
    exception_id         VARCHAR(128) NOT NULL PRIMARY KEY,
    run_id               VARCHAR(64)  NULL,
    policy_id            VARCHAR(64)  NOT NULL,
    reason               VARCHAR(2000) NOT NULL,
    scope                VARCHAR(512) NOT NULL,
    approver             VARCHAR(128) NULL,
    compensating_control VARCHAR(2000) NULL,
    approved_at          TIMESTAMP(9) WITH TIME ZONE NULL,
    expires_at           TIMESTAMP(9) WITH TIME ZONE NULL,
    review_condition     VARCHAR(512) NULL
);

CREATE INDEX idx_policy_exceptions_policy ON policy_exceptions (policy_id);
