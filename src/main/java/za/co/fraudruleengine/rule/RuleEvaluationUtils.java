package za.co.fraudruleengine.rule;

import za.co.fraudruleengine.model.Transaction;

/**
 * Shared utility methods for fraud rule evaluation logic.
 *
 * <p>Extracted to avoid copy-paste duplication between rules that share common predicates.
 * Each method is stateless and operates solely on the supplied arguments — no Spring
 * context is required and every method is straightforwardly unit-testable in isolation.
 */
public final class RuleEvaluationUtils {

    /** Utility class — do not instantiate. */
    private RuleEvaluationUtils() {}

    /**
     * Returns {@code true} if the transaction falls within the configured off-hours window.
     *
     * <p>The window is a half-open interval: {@code startHour <= transactionHour < endHour}.
     * Default parameters ({@code startHour=0}, {@code endHour=5}) cover midnight to 05:00,
     * the highest-risk period for card-not-present and card-theft fraud.
     *
     * <p>This helper is shared by {@link za.co.fraudruleengine.rule.impl.OffHoursRule} and
     * {@link za.co.fraudruleengine.rule.impl.NightTimeAtmWithdrawalRule}, which both perform
     * an identical time-of-day check as part of their evaluation logic.
     *
     * @param transaction the transaction whose {@code transactionTime} is inspected
     * @param parameters  rule configuration providing {@code startHour} and {@code endHour}
     * @return {@code true} when the transaction hour falls within {@code [startHour, endHour)}
     */
    public static boolean isOffHours(Transaction transaction, RuleParameters parameters) {
        int startHour = parameters.getInt("startHour", 0);
        int endHour   = parameters.getInt("endHour",   5);
        int txHour    = transaction.transactionTime().getHour();
        return txHour >= startHour && txHour < endHour;
    }
}
