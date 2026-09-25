CREATE TABLE deposits (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts (id),
    amount NUMERIC(19, 2) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_deposits_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_deposits_account_created ON deposits (account_id, created_at);
