package za.co.fraudruleengine.rule.impl;

import org.springframework.stereotype.Component;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.FraudRule;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;

import java.math.BigDecimal;

/**
 * Fraud rule that flags transactions whose amount exceeds a configurable monetary threshold.
 *
 * <p>Large single transactions are a strong fraud signal in both card-present and card-not-present
 * environments, particularly when the transaction amount is anomalously high relative to an
 * account's typical spending behaviour. This rule provides a coarse but computationally cheap
 * first-pass filter.
 *
 * <p><strong>Configuration parameters</strong> (stored in {@code rule_configs.parameters}):
 * <ul>
 *   <li>{@code maxAmount} — the maximum permitted transaction amount as a decimal string
 *       (default: {@code "10000.00"})</li>
 * </ul>
 *
 * <p>The comparison uses {@link BigDecimal#compareTo} rather than {@code equals} to handle
 * scale differences (e.g. {@code 10000} vs {@code 10000.00}) correctly.
 */
@Component
public class AmountThresholdRule implements FraudRule {

    /** Canonical rule name used as the join key with the {@code rule_configs} database row. */
    public static final String RULE_NAME = "AMOUNT_THRESHOLD_RULE";

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    /**
     * Evaluates whether the transaction amount exceeds the configured threshold.
     *
     * @param transaction the transaction to evaluate
     * @param parameters  rule configuration; reads the {@code maxAmount} parameter
     * @return a {@link RuleResult} with {@code matched = true} and the full risk weight score
     *         when the amount is strictly greater than {@code maxAmount}; otherwise
     *         {@code matched = false} with a score of {@code 0}
     */
    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        BigDecimal maxAmount = parameters.getBigDecimal("maxAmount", new BigDecimal("10000.00"));

        // compareTo is used instead of > to ensure BigDecimal scale differences don't affect the result
        boolean matched = transaction.amount().compareTo(maxAmount) > 0;

        String description = matched
                ? String.format("Transaction amount %s %s exceeds threshold of %s",
                        transaction.amount(), transaction.currency(), maxAmount)
                : String.format("Transaction amount %s %s within threshold of %s",
                        transaction.amount(), transaction.currency(), maxAmount);

        return new RuleResult(RULE_NAME, matched, matched ? parameters.riskWeight() : 0, description);
    }
}
