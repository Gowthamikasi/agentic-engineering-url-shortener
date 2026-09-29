-- Application plane: Idempotency-Key ledger (ASM-002).
CREATE TABLE idempotency (
    idem_key     VARCHAR(255) NOT NULL PRIMARY KEY,
    request_hash VARCHAR(64)  NOT NULL,
    code         VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMP(9) WITH TIME ZONE NOT NULL
);
