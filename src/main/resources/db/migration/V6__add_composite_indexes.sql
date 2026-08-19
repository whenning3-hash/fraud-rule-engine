-- V6: Add composite indexes to support fraud rule query patterns at scale
--
-- Without these indexes, rule queries degrade to sequential scans as the transaction
-- table grows.  Each index is targeted at a specific query pattern used by a rule:
--
--   idx_transactions_account_created
--     CountryMismatchRule: SELECT DISTINCT country_code ... WHERE account_id = ? AND created_at >= ?
--     A single-column index on account_id alone forces PostgreSQL to scan all rows for
--     the account and then filter by date.  The composite (account_id, created_at) allows
--     the planner to satisfy both predicates from the index, reducing I/O dramatically.
--
--   idx_transactions_account_category
--     UnusualMerchantCategoryRule: ... WHERE account_id = ? AND merchant_category = ? AND id != ?
--     Without this index every "first-time merchant category" check scans the full account
--     history.  The composite cuts the working set to rows matching both account and category.
--
--   idx_matched_rules_alert_id
--     FraudAlertEntity loads matchedRules via @ElementCollection(fetch = EAGER), which issues:
--       SELECT * FROM fraud_alert_matched_rules WHERE alert_id = ?
--     PostgreSQL FK constraints do NOT create indexes automatically (unlike some databases).
--     Without this index every alert load triggers a full table scan on fraud_alert_matched_rules.

CREATE INDEX IF NOT EXISTS idx_transactions_account_created
    ON transactions (account_id, created_at);

CREATE INDEX IF NOT EXISTS idx_transactions_account_category
    ON transactions (account_id, merchant_category);

CREATE INDEX IF NOT EXISTS idx_matched_rules_alert_id
    ON fraud_alert_matched_rules (alert_id);
