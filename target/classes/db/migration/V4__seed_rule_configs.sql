INSERT INTO rule_configs (id, rule_name, enabled, risk_weight, parameters) VALUES
(
    '11111111-1111-1111-1111-111111111111',
    'VELOCITY_RULE',
    TRUE,
    30,
    '{"maxTransactions": "5", "windowMinutes": "10"}'::jsonb
),
(
    '22222222-2222-2222-2222-222222222222',
    'AMOUNT_THRESHOLD_RULE',
    TRUE,
    40,
    '{"maxAmount": "10000.00"}'::jsonb
),
(
    '33333333-3333-3333-3333-333333333333',
    'OFF_HOURS_RULE',
    TRUE,
    20,
    '{"startHour": "0", "endHour": "5"}'::jsonb
),
(
    '44444444-4444-4444-4444-444444444444',
    'DUPLICATE_TRANSACTION_RULE',
    TRUE,
    35,
    '{"windowSeconds": "60"}'::jsonb
)
ON CONFLICT (rule_name) DO NOTHING;
