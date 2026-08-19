package za.co.fraudruleengine.rule.impl;

import org.springframework.stereotype.Component;
import za.co.fraudruleengine.model.RuleName;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.FraudRule;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;

import java.math.BigDecimal;

/**
 * Fraud rule that flags transactions whose amount is a suspiciously round number above a
 * minimum threshold.
 *
 * <p><strong>Why round numbers are suspicious in banking fraud:</strong><br>
 * A common money-laundering and structuring technique involves breaking up large cash flows into
 * exact round amounts (e.g. R5 000, R10 000, R20 000) to stay just below regulatory reporting
 * thresholds or to mimic predictable, authorised payment patterns. Legitimate retail transactions
 * tend to have irregular amounts (e.g. R1 249.95) because they reflect real goods and services.
 * A high-value round amount — particularly from an account without a prior pattern of such
 * payments — is therefore a meaningful fraud signal.
 *
 * <p><strong>Configuration parameters</strong> (stored in {@code rule_configs.parameters}):
 * <ul>
 *   <li>{@code divisor}   — the "roundness" divisor; amounts divisible by this value are flagged
 *       (default: {@code "1000"}, i.e. multiples of R1 000)</li>
 *   <li>{@code minAmount} — the minimum amount below which the rule does not fire, to avoid
 *       false positives on common low-value round payments like bus fares (default: {@code "5000.00"})</li>
 * </ul>
 *
 * <p>The divisibility check uses {@link BigDecimal#remainder} to correctly handle decimal
 * amounts. An amount qualifies if {@code amount >= minAmount} AND
 * {@code amount.remainder(divisor).compareTo(ZERO) == 0}.
 */
@Component
public class RoundNumberAmountRule implements FraudRule {

    /** Canonical rule name used as the join key with the {@code rule_configs} database row. */
    public static final String RULE_NAME = RuleName.ROUND_NUMBER_AMOUNT_RULE.name();

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    /**
     * Evaluates whether the transaction amount is a suspiciously round number above the minimum
     * threshold.
     *
     * @param transaction the transaction to evaluate
     * @param parameters  rule configuration; reads {@code divisor} and {@code minAmount}
     * @return a matched {@link RuleResult} with the full risk weight when the amount is both
     *         above {@code minAmount} and exactly divisible by {@code divisor}; otherwise
     *         a non-matching result with score {@code 0}
     */
    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        BigDecimal divisor   = parameters.getBigDecimal("divisor",   new BigDecimal("1000"));
        BigDecimal minAmount = parameters.getBigDecimal("minAmount",  new BigDecimal("5000.00"));

        BigDecimal amount = transaction.amount();

        // Only evaluate amounts above the minimum — low-value round amounts (e.g. R100 bus fare) are normal
        boolean aboveMinimum = amount.compareTo(minAmount) >= 0;

        // Check whether the amount is exactly divisible by the configured divisor
        boolean isRound = aboveMinimum && amount.remainder(divisor).compareTo(BigDecimal.ZERO) == 0;

        String description = isRound
                ? String.format("Amount %s %s is a round multiple of %s (structuring indicator)",
                        amount, transaction.currency(), divisor)
                : String.format("Amount %s %s is not a suspicious round number", amount, transaction.currency());

        return new RuleResult(RULE_NAME, isRound, isRound ? parameters.riskWeight() : 0, description);
    }
}
