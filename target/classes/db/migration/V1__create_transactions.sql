CREATE TABLE IF NOT EXISTS transactions (
    id              UUID            NOT NULL,
    account_id      VARCHAR(100)    NOT NULL,
    amount          NUMERIC(19, 4)  NOT NULL,
    currency        CHAR(3)         NOT NULL,
    merchant_name   VARCHAR(255)    NOT NULL,
    merchant_category VARCHAR(100),
    channel         VARCHAR(20)     NOT NULL,
    country_code    CHAR(3),
    transaction_time TIMESTAMP      NOT NULL,
    risk_score      INT             NOT NULL DEFAULT 0,
    is_fraudulent   BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_transactions PRIMARY KEY (id)
);

CREATE INDEX idx_transactions_account_id ON transactions (account_id);
CREATE INDEX idx_transactions_created_at ON transactions (created_at);
CREATE INDEX idx_transactions_is_fraudulent ON transactions (is_fraudulent);
