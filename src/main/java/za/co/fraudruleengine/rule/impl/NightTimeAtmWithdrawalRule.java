package za.co.fraudruleengine.rule.impl;

import org.springframework.stereotype.Component;
import za.co.fraudruleengine.model.ChannelType;
import za.co.fraudruleengine.model.RuleName;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.FraudRule;
import za.co.fraudruleengine.rule.RuleEvaluationUtils;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Fraud rule that detects high-value ATM or POS cash withdrawals made during off-hours.
 *
 * <p><strong>Why this pattern is high-risk in the Capitec context:</strong><br>
 * Card theft, skimming, and SIM-swap fraud often manifest as large cash withdrawals at ATMs
 * during the hours when the legitimate cardholder is most likely asleep (typically midnight to
 * 05:00). The combination of three independent signals — after-hours timing, a cash-dispensing
 * channel, and an above-threshold amount — produces a strong composite indicator that is
 * difficult to satisfy accidentally during normal customer behaviour.
 *
 * <p>This rule deliberately uses a <em>conjunction</em> of signals (AND logic) rather than
 * flagging any ATM transaction or any off-hours transaction individually. That keeps the
 * false-positive rate low while catching the highest-risk subset. The {@link OffHoursRule}
 * and {@link AmountThresholdRule} handle the individual signals.
 *
 * <p><strong>Configuration parameters</strong> (stored in {@code rule_configs.parameters}):
 * <ul>
 *   <li>{@code startHour}  — start of the off-hours window (inclusive, 24-h clock, default {@code 0})</li>
 *   <li>{@code endHour}    — end of the off-hours window (exclusive, default {@code 5})</li>
 *   <li>{@code minAmount}  — minimum transaction amount that triggers the rule (default {@code "1500.00"})</li>
 * </ul>
 *
 * <p>Eligible channels are {@link ChannelType#ATM} and {@link ChannelType#POS} (point-of-sale
 * cash-back), resolved from the raw channel string via {@link ChannelType#fromString(String)}.
 */
@Component
public class NightTimeAtmWithdrawalRule implements FraudRule {

    /**
     * Canonical rule name used as the join key with the {@code rule_configs} database row.
     * Sourced from {@link RuleName} to eliminate duplicated string literals.
     */
    public static final String RULE_NAME = RuleName.NIGHT_TIME_ATM_RULE.name();

    /** Channels that represent physical card-present cash access points. */
    private static final Set<ChannelType> CASH_CHANNELS = EnumSet.of(ChannelType.ATM, ChannelType.POS);

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    /**
     * Evaluates whether all three conditions are simultaneously true:
     * <ol>
     *   <li>The transaction was submitted during the configured off-hours window.</li>
     *   <li>The channel is a physical cash-access point ({@code ATM} or {@code POS}).</li>
     *   <li>The amount meets or exceeds the configured minimum threshold.</li>
     * </ol>
     *
     * @param transaction the transaction to evaluate
     * @param parameters  rule configuration; reads {@code startHour}, {@code endHour},
     *                    and {@code minAmount}
     * @return a matched {@link RuleResult} with the full risk weight only when all three
     *         conditions are met; otherwise a non-matching result with score {@code 0}
     */
    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        BigDecimal minAmount = parameters.getBigDecimal("minAmount", new BigDecimal("1500.00"));

        // Off-hours check delegated to shared utility to avoid duplication with OffHoursRule
        boolean isOffHours    = RuleEvaluationUtils.isOffHours(transaction, parameters);
        boolean isCashChannel = CASH_CHANNELS.contains(ChannelType.fromString(transaction.channel()));
        boolean isHighAmount  = transaction.amount().compareTo(minAmount) >= 0;

        boolean matched = isOffHours && isCashChannel && isHighAmount;

        int hour = transaction.transactionTime().getHour();
        String description = matched
                ? String.format("Night-time ATM/POS withdrawal detected — hour: %02d:00, channel: %s, amount: %s %s",
                        hour, transaction.channel(), transaction.amount(), transaction.currency())
                : String.format("Transaction does not match night-time ATM withdrawal pattern " +
                        "(offHours=%b, cashChannel=%b, highAmount=%b)", isOffHours, isCashChannel, isHighAmount);

        return new RuleResult(RULE_NAME, matched, matched ? parameters.riskWeight() : 0, description);
    }
}
