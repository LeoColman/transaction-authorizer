-- Contas informadas pelo sistema externo de abertura de contas (via SQS).
-- Saldo inicial ZERO por regra do desafio. CHECK de saldo não negativo é
-- defesa em profundidade: o UPDATE condicional já garante a invariante.
CREATE TABLE accounts (
    id            UUID PRIMARY KEY,
    owner_id      UUID           NOT NULL,
    status        VARCHAR(16)    NOT NULL,
    balance       NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency      CHAR(3)        NOT NULL DEFAULT 'BRL',
    created_at    TIMESTAMPTZ    NOT NULL,
    registered_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT accounts_balance_non_negative CHECK (balance >= 0)
);

-- Resultado imutável de cada autorização. A PK em id é a garantia de
-- idempotência por transactionId: inserção duplicada viola a PK e faz a
-- transação de banco inteira (incluindo alteração de saldo) sofrer rollback.
CREATE TABLE transactions (
    id            UUID PRIMARY KEY,
    account_id    UUID           NOT NULL REFERENCES accounts (id),
    type          VARCHAR(8)     NOT NULL,
    amount        NUMERIC(19, 2) NOT NULL,
    currency      CHAR(3)        NOT NULL,
    status        VARCHAR(12)    NOT NULL,
    balance_after NUMERIC(19, 2) NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL,
    CONSTRAINT transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT transactions_balance_after_non_negative CHECK (balance_after >= 0)
);

-- Suporta consultas de extrato por conta (evolução natural do domínio).
CREATE INDEX idx_transactions_account_created ON transactions (account_id, created_at DESC);
