package za.co.fraudruleengine.domain.rule;

/**
 * Immutable record capturing the outcome of a single {@link FraudRule} evaluation.
 *
 * <p>Each rule returns exactly one {@code RuleResult} per transaction evaluation. The
 * {@link RuleEngine} collects these into an {@link EvaluationResult} and sums the
 * {@link #riskScore()} values of all matching rules to derive the aggregate fraud score.
 *
 * <p>By convention, rules set {@code riskScore} to the configured risk weight when
 * {@code matched} is {@code true}, and to {@code 0} when {@code false}, ensuring that
 * non-firing rules contribute nothing to the total score.
 *
 * @param ruleName    the canonical name of the rule that produced this result (matches
 *                    {@link FraudRule#getRuleName()})
 * @param matched     {@code true} if the rule's condition was satisfied by the transaction
 * @param riskScore   the score contribution; equals the rule's configured risk weight on a
 *                    match, or {@code 0} on a non-match
 * @param description human-readable explanation of why the rule matched or did not match;
 *                    useful for analyst investigation of fraud alerts
 */
public record RuleResult(
        String ruleName,
        boolean matched,
        int riskScore,
        String description
) {}
