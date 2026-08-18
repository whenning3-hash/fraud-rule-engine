package za.co.fraudruleengine.domain.rule.impl;

import org.springframework.stereotype.Component;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.FraudRule;
import za.co.fraudruleengine.domain.rule.RuleParameters;
import za.co.fraudruleengine.domain.rule.RuleResult;

import java.math.BigDecimal;
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
 * <p>Eligible channels are {@code ATM} and {@code POS} (point-of-sale cash-back), represented as
 * the channel string stored on the {@link Transaction} domain model.
 */
@Component
public class NightTimeAtmWithdrawalRule implements FraudRule {

    /** Canonical rule name used as the join key with the {@code rule_configs} database row. */
    public static final String RULE_NAME = "NIGHT_TIME_ATM_RULE";

    /** Channels that represent physical card-present cash access points. */
    private static final Set<String> CASH_CHANNELS = Set.of("ATM", "POS");

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    /**
     * Evaluates whether all three conditions are simultaneously true:
     * <ol>
     *   <li>The transaction was submitted during the configured off-hours window.</li>
     *   <li>The channel is a physical cash-access point (ATM or POS).</li>
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
        int startHour = parameters.getInt("startHour", 0);
        int endHour   = parameters.getInt("endHour",   5);
        BigDecimal minAmount = parameters.getBigDecimal("minAmount", new BigDecimal("1500.00"));

        int hour = transaction.transactionTime().getHour();

        // All three conditions must hold for the rule to fire
        boolean isOffHours  = hour >= startHour && hour < endHour;
        boolean isCashChannel = CASH_CHANNELS.contains(transaction.channel().toUpperCase());
        boolean isHighAmount  = transaction.amount().compareTo(minAmount) >= 0;

        boolean matched = isOffHours && isCashChannel && isHighAmount;

        String description = matched
                ? String.format("Night-time ATM/POS withdrawal detected — hour: %02d:00, channel: %s, amount: %s %s",
                        hour, transaction.channel(), transaction.amount(), transaction.currency())
                : String.format("Transaction does not match night-time ATM withdrawal pattern " +
                        "(offHours=%b, cashChannel=%b, highAmount=%b)", isOffHours, isCashChannel, isHighAmount);

        return new RuleResult(RULE_NAME, matched, matched ? parameters.riskWeight() : 0, description);
    }
}
