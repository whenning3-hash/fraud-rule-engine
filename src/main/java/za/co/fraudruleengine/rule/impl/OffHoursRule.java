package za.co.fraudruleengine.rule.impl;

import org.springframework.stereotype.Component;
import za.co.fraudruleengine.model.RuleName;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.FraudRule;
import za.co.fraudruleengine.rule.RuleEvaluationUtils;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;

/**
 * Fraud rule that flags transactions occurring during a configurable off-hours window.
 *
 * <p>Fraudulent transactions — particularly those originating from stolen card details or
 * account takeovers — disproportionately occur outside normal business hours when the
 * legitimate account holder is less likely to notice an unauthorised charge and when
 * fraud-operations staffing is reduced. This rule adds a risk contribution for transactions
 * that fall within the suspicious time window.
 *
 * <p><strong>Configuration parameters</strong> (stored in {@code rule_configs.parameters}):
 * <ul>
 *   <li>{@code startHour} — the first hour (inclusive, 0–23) of the suspicious window
 *       (default: {@code 0}, i.e. midnight)</li>
 *   <li>{@code endHour} — the last hour (exclusive, 0–23) of the suspicious window
 *       (default: {@code 5}, i.e. up to but not including 05:00)</li>
 * </ul>
 *
 * <p>The comparison is half-open: {@code startHour <= transactionHour < endHour}.
 * Note that this rule operates on the {@link java.time.LocalDateTime} stored on the transaction,
 * which reflects the time zone of the submitting system. Ensure the client submits times in a
 * consistent time zone (preferably UTC) to avoid false positives or missed detections.
 */
@Component
public class OffHoursRule implements FraudRule {

    /**
     * Canonical rule name used as the join key with the {@code rule_configs} database row.
     * Sourced from {@link RuleName} to eliminate duplicated string literals.
     */
    public static final String RULE_NAME = RuleName.OFF_HOURS_RULE.name();

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    /**
     * Evaluates whether the transaction occurred within the configured off-hours window.
     *
     * @param transaction the transaction to evaluate; {@code transactionTime} must be non-null
     * @param parameters  rule configuration; reads {@code startHour} and {@code endHour}
     * @return a {@link RuleResult} with {@code matched = true} and the full risk weight when the
     *         transaction hour falls within {@code [startHour, endHour)}; otherwise {@code matched = false}
     *         with a score of {@code 0}
     */
    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        boolean matched = RuleEvaluationUtils.isOffHours(transaction, parameters);

        int transactionHour = transaction.transactionTime().getHour();
        int startHour       = parameters.getInt("startHour", 0);
        int endHour         = parameters.getInt("endHour",   5);

        String description = matched
                ? String.format("Transaction occurred at %02d:xx, within off-hours window (%02d:00–%02d:00)",
                        transactionHour, startHour, endHour)
                : String.format("Transaction occurred at %02d:xx, outside off-hours window", transactionHour);

        return new RuleResult(RULE_NAME, matched, matched ? parameters.riskWeight() : 0, description);
    }
}
