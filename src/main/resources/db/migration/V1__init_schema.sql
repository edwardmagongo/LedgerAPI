CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE accounts (
    id         UUID PRIMARY KEY,
    owner_id   UUID           NOT NULL REFERENCES users (id),
    currency   VARCHAR(3)     NOT NULL,
    balance    NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    status     VARCHAR(16)    NOT NULL,
    version    BIGINT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_accounts_owner ON accounts (owner_id);

CREATE TABLE transactions (
    id            UUID PRIMARY KEY,
    account_id    UUID           NOT NULL REFERENCES accounts (id),
    type          VARCHAR(16)    NOT NULL,
    amount        NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    balance_after NUMERIC(19, 4) NOT NULL,
    transfer_id   UUID,
    created_at    TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_transactions_account_created ON transactions (account_id, created_at DESC);
CREATE INDEX idx_transactions_transfer ON transactions (transfer_id);
