-- Application plane: short links.
CREATE TABLE links (
    code             VARCHAR(16)   NOT NULL PRIMARY KEY,
    target           VARCHAR(2048) NOT NULL,
    created_at       TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    expires_at       TIMESTAMP(9) WITH TIME ZONE NULL,
    idempotency_key  VARCHAR(255)  NULL,
    created_by       VARCHAR(64)   NULL
);

CREATE INDEX idx_links_expires_at ON links (expires_at);
