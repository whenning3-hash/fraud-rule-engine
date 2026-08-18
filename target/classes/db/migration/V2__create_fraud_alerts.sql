CREATE TABLE IF NOT EXISTS fraud_alerts (
    id              UUID            NOT NULL,
    transaction_id  UUID            NOT NULL,
    account_id      VARCHAR(100)    NOT NULL,
    total_risk_score INT            NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    rule_details    TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_fraud_alerts PRIMARY KEY (id),
    CONSTRAINT fk_fraud_alerts_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id)
);

CREATE TABLE IF NOT EXISTS fraud_alert_matched_rules (
    alert_id    UUID            NOT NULL,
    rule_name   VARCHAR(100)    NOT NULL,
    CONSTRAINT fk_matched_rules_alert FOREIGN KEY (alert_id) REFERENCES fraud_alerts(id)
);

CREATE INDEX idx_fraud_alerts_account_id ON fraud_alerts (account_id);
CREATE INDEX idx_fraud_alerts_status ON fraud_alerts (status);
CREATE INDEX idx_fraud_alerts_created_at ON fraud_alerts (created_at);
