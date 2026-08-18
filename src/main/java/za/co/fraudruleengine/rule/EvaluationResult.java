package za.co.fraudruleengine.rule;

import java.util.List;

/**
 * Aggregated outcome of running all enabled fraud rules against a single transaction.
 *
 * <p>Produced by {@link RuleEngine#evaluate(za.co.fraudruleengine.model.Transaction)}
 * and consumed by {@link za.co.fraudruleengine.service.FraudEvaluationService}, which uses
 * {@link #isFraudulent(int)} to decide whether a fraud alert should be raised and
 * {@link #matchedRuleNames()} to populate the alert's matched-rule list.
 *
 * @param ruleResults the individual evaluation outcome for every rule that was executed;
 *                    includes both matching and non-matching rules for full audit visibility
 * @param totalScore  the sum of {@link RuleResult#riskScore()} across all matched rules;
 *                    compared against the configurable threshold to determine fraud classification
 */
public record EvaluationResult(List<RuleResult> ruleResults, int totalScore) {

    /**
     * Returns the names of every rule that fired (i.e. {@link RuleResult#matched()} is {@code true}).
     *
     * <p>This list is stored on the resulting {@link za.co.fraudruleengine.entity.FraudAlertEntity}
     * to give analysts a quick summary of which signals contributed to the fraud determination
     * without needing to parse the full JSON rule-detail payload.
     *
     * @return an unmodifiable list of matched rule names; empty if no rules matched
     */
    public List<String> matchedRuleNames() {
        return ruleResults.stream()
                .filter(RuleResult::matched)
                .map(RuleResult::ruleName)
                .toList();
    }

    /**
     * Determines whether the aggregate risk score meets or exceeds the fraud classification
     * threshold configured via {@code fraud.score.threshold} (default {@code 60}).
     *
     * <p>Using a threshold rather than a fixed score allows the risk sensitivity of the
     * engine to be tuned operationally without code changes — for example, lowering the
     * threshold during a known fraud campaign or raising it to reduce false-positive volume.
     *
     * @param threshold the minimum total score required to classify the transaction as fraudulent
     * @return {@code true} if {@link #totalScore()} is greater than or equal to {@code threshold}
     */
    public boolean isFraudulent(int threshold) {
        return totalScore >= threshold;
    }
}
