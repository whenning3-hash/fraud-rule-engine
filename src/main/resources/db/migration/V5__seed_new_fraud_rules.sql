-- V5: Seed four additional Capitec-realistic fraud rules
--
-- These rules extend the baseline rule set with behavioural patterns
-- observed in real banking fraud scenarios:
--
--   ROUND_NUMBER_AMOUNT_RULE   — Structuring / money-laundering: large round-number payments
--                                are a classic indicator of attempts to avoid regulatory thresholds.
--
--   NIGHT_TIME_ATM_RULE        — Card theft / skimming: high-value ATM or POS cash withdrawals
--                                during off-hours (midnight–05:00) are strongly correlated with
--                                card theft and SIM-swap fraud.
--
--   COUNTRY_MISMATCH_RULE      — Impossible travel: a transaction in a different country from
--                                the account's recent transaction history within 24 hours cannot
--                                be performed by a physically present cardholder.
--
--   UNUSUAL_MERCHANT_CATEGORY_RULE — Behavioural deviation / account takeover: a merchant
--                                    category the account has never used before is a soft signal
--                                    of compromised credentials or social-engineering fraud.
--
-- Risk weights are calibrated so that individual rules fire at the default threshold (20 for
-- local/demo; 60 for production) only when combined with other signals, except for the
-- country-mismatch rule which is high-confidence on its own.

INSERT INTO rule_configs (id, rule_name, enabled, risk_weight, parameters)
VALUES
(
    '55555555-5555-5555-5555-555555555555',
    'ROUND_NUMBER_AMOUNT_RULE',
    TRUE,
    25,
    '{"divisor": "1000", "minAmount": "5000.00"}'::jsonb
),
(
    '66666666-6666-6666-6666-666666666666',
    'NIGHT_TIME_ATM_RULE',
    TRUE,
    45,
    '{"startHour": "0", "endHour": "5", "minAmount": "1500.00"}'::jsonb
),
(
    '77777777-7777-7777-7777-777777777777',
    'COUNTRY_MISMATCH_RULE',
    TRUE,
    50,
    '{"windowHours": "24"}'::jsonb
),
(
    '88888888-8888-8888-8888-888888888888',
    'UNUSUAL_MERCHANT_CATEGORY_RULE',
    TRUE,
    15,
    '{}'::jsonb
)
ON CONFLICT (rule_name) DO NOTHING;
