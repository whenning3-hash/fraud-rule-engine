package za.co.fraudruleengine.domain.rule;

import za.co.fraudruleengine.domain.model.Transaction;

/**
 * Strategy interface that every fraud detection rule must implement.
 *
 * <p>Each implementation encapsulates a single, independently configurable detection heuristic
 * (e.g. amount threshold, transaction velocity, off-hours timing, duplicate detection). The
 * {@link RuleEngine} discovers all Spring-managed beans of this type at startup and evaluates
 * them against every incoming transaction.
 *
 * <p>Rules are designed to be stateless with respect to the transaction being evaluated; any
 * external state (e.g. Redis counters) is accessed through injected ports so that the rule
 * logic itself remains unit-testable without infrastructure dependencies.
 *
 * <p><strong>Adding a new rule:</strong> implement this interface, annotate the class with
 * {@code @Component}, and ensure a corresponding {@code rule_configs} row exists in the database
 * (Flyway migration V4 seeds the initial set). The engine will pick it up automatically.
 */
public interface FraudRule {

    /**
     * Returns the unique, stable identifier for this rule.
     *
     * <p>This name is used as the join key between the rule implementation and its database
     * configuration row in {@code rule_configs.rule_name}. It must exactly match the value
     * stored in the database (e.g. {@code "VELOCITY_RULE"}, {@code "AMOUNT_THRESHOLD_RULE"}).
     *
     * @return the canonical rule name; never {@code null} or empty
     */
    String getRuleName();

    /**
     * Evaluates the rule against the given transaction using the supplied runtime parameters.
     *
     * <p>Implementations must never throw unchecked exceptions for ordinary evaluation failures
     * (e.g. a missing optional parameter); they should fall back to sensible defaults via
     * {@link RuleParameters#getInt} or {@link RuleParameters#getBigDecimal}. Exceptions should
     * only propagate for genuinely unexpected infrastructure failures.
     *
     * @param transaction the transaction to evaluate; never {@code null}
     * @param parameters  database-backed configuration for this rule, including risk weight and
     *                    rule-specific key/value pairs; never {@code null}
     * @return a {@link RuleResult} indicating whether the rule matched, the risk score
     *         contribution (zero if unmatched), and a human-readable description; never {@code null}
     */
    RuleResult evaluate(Transaction transaction, RuleParameters parameters);
}
