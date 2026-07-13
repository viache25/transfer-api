CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    owner VARCHAR(255) NOT NULL,
    balance NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE transfers (
    id BIGSERIAL PRIMARY KEY,
    from_account_id BIGINT NOT NULL REFERENCES accounts (id),
    to_account_id BIGINT NOT NULL REFERENCES accounts (id),
    amount NUMERIC(19, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key)
);

-- composite indexes so "transfers of account X, newest first" is a top-N index scan per side of the OR
CREATE INDEX idx_transfers_from_account_created ON transfers (from_account_id, created_at);
CREATE INDEX idx_transfers_to_account_created ON transfers (to_account_id, created_at);
