CREATE TABLE IF NOT EXISTS rule_configs (
    id          UUID            NOT NULL,
    rule_name   VARCHAR(100)    NOT NULL,
    enabled     BOOLEAN         NOT NULL DEFAULT TRUE,
    risk_weight INT             NOT NULL DEFAULT 25,
    parameters  JSONB           NOT NULL DEFAULT '{}',
    CONSTRAINT pk_rule_configs PRIMARY KEY (id),
    CONSTRAINT uq_rule_configs_rule_name UNIQUE (rule_name)
);
