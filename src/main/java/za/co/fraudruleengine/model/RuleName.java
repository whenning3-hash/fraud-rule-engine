package za.co.fraudruleengine.model;

/**
 * Enumeration of every registered fraud detection rule.
 *
 * <p>Using an enum as the single source of truth for rule identifiers eliminates the risk of
 * silent typos in the per-class {@code RULE_NAME} string constants.  The value returned by
 * {@link Enum#name()} is used as the join key between each {@link za.co.fraudruleengine.rule.FraudRule}
 * implementation and its configuration row in the {@code rule_configs} database table.
 *
 * <p><strong>Adding a new rule:</strong>
 * <ol>
 *   <li>Add a new constant here.</li>
 *   <li>Reference it from the rule implementation: {@code RuleName.YOUR_RULE.name()}.</li>
 *   <li>Add the corresponding {@code rule_configs} row in the appropriate Flyway migration.</li>
 * </ol>
 *
 * <p>The enum constants must match the values stored in the database exactly (case-sensitive).
 */
public enum RuleName {

    /** Flags accounts that submit an unusually high number of transactions within a short window. */
    VELOCITY_RULE,

    /** Flags transactions whose monetary amount exceeds a configurable threshold. */
    AMOUNT_THRESHOLD_RULE,

    /** Flags transactions occurring outside normal business hours (default: midnight–05:00). */
    OFF_HOURS_RULE,

    /** Flags transactions that appear to be exact duplicates of a recent prior transaction. */
    DUPLICATE_TRANSACTION_RULE,

    /** Flags transactions whose amounts are suspiciously round numbers (e.g. R1000.00 exactly). */
    ROUND_NUMBER_AMOUNT_RULE,

    /**
     * Flags high-value ATM or POS cash withdrawals made during off-hours — a strong composite
     * signal for card theft or skimming fraud.
     */
    NIGHT_TIME_ATM_RULE,

    /** Flags transactions from a country different from those seen in the account's recent history. */
    COUNTRY_MISMATCH_RULE,

    /**
     * Flags transactions in merchant categories that the account has never used before,
     * such as foreign-exchange bureaux or gambling establishments.
     */
    UNUSUAL_MERCHANT_CATEGORY_RULE
}
