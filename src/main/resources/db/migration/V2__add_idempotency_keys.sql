CREATE TABLE idempotency_keys (
    id                  UUID PRIMARY KEY,
    idempotency_key     VARCHAR(255) NOT NULL,
    user_id             UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    operation           VARCHAR(16)  NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    response_status     INT,
    response_body       TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    completed_at        TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idempotency_key)
);

-- Supports a future retention sweep:
--   DELETE FROM idempotency_keys WHERE created_at < now() - interval '30 days';
CREATE INDEX idx_idempotency_created ON idempotency_keys (created_at);
