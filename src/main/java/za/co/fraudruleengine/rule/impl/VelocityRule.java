package za.co.fraudruleengine.rule.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import za.co.fraudruleengine.model.RuleName;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.FraudRule;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;
import za.co.fraudruleengine.redis.VelocityStorePort;

/**
 * Fraud rule that detects abnormally high transaction frequency for a given account.
 *
 * <p>Velocity-based fraud (also known as rapid-fire or card-testing attacks) involves making many
 * small transactions in quick succession to either test card validity or to drain an account
 * before the fraud-monitoring system flags the activity. This rule uses a Redis sorted-set
 * time-window to count how many transactions the account has made within the configured period
 * before evaluating the current transaction.
 *
 * <p><strong>Important ordering guarantee:</strong> the current transaction's count is read
 * <em>before</em> it is recorded. This means the rule fires when the number of <em>prior</em>
 * transactions in the window already reaches or exceeds {@code maxTransactions}, which avoids an
 * off-by-one that would allow exactly {@code maxTransactions} to pass undetected.
 *
 * <p><strong>Configuration parameters</strong> (stored in {@code rule_configs.parameters}):
 * <ul>
 *   <li>{@code maxTransactions} — maximum number of transactions allowed in the window before
 *       the rule fires (default: {@code 5})</li>
 *   <li>{@code windowMinutes} — the rolling time window in minutes (default: {@code 10})</li>
 * </ul>
 *
 * <p>Redis sorted-set entries for a given account expire after 1 hour (managed by
 * {@link za.co.fraudruleengine.redis.VelocityStore}), so velocity data is
 * automatically cleaned up without a separate maintenance job.
 */
@Component
@RequiredArgsConstructor
public class VelocityRule implements FraudRule {

    /** Canonical rule name used as the join key with the {@code rule_configs} database row. */
    public static final String RULE_NAME = RuleName.VELOCITY_RULE.name();

    private final VelocityStorePort velocityStore;

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    /**
     * Evaluates the transaction velocity for the originating account and records the current
     * transaction in the Redis velocity store.
     *
     * <p>The count is read before the transaction is recorded so that the threshold comparison
     * reflects the number of preceding transactions within the window, not including the current one.
     *
     * @param transaction the transaction to evaluate
     * @param parameters  rule configuration; reads {@code maxTransactions} and {@code windowMinutes}
     * @return a {@link RuleResult} with {@code matched = true} and the full risk weight when the
     *         prior transaction count within the window equals or exceeds {@code maxTransactions};
     *         otherwise {@code matched = false} with a score of {@code 0}
     */
    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        int maxTransactions = parameters.getInt("maxTransactions", 5);
        int windowMinutes = parameters.getInt("windowMinutes", 10);

        // Read the existing count BEFORE recording this transaction to avoid counting the current one
        long count = velocityStore.getTransactionCount(transaction.accountId(), windowMinutes);
        velocityStore.recordTransaction(transaction.accountId(), transaction.id().toString());

        boolean matched = count >= maxTransactions;

        String description = matched
                ? String.format("Account %s made %d transactions in the last %d minutes (max allowed: %d)",
                        transaction.accountId(), count, windowMinutes, maxTransactions)
                : String.format("Velocity within limits: %d/%d transactions in %d minutes",
                        count, maxTransactions, windowMinutes);

        return new RuleResult(RULE_NAME, matched, matched ? parameters.riskWeight() : 0, description);
    }
}
