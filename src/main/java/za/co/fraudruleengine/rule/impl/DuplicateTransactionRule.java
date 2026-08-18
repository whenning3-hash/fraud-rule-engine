package za.co.fraudruleengine.rule.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.FraudRule;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;
import za.co.fraudruleengine.redis.VelocityStorePort;

/**
 * Fraud rule that detects duplicate transactions submitted within a configurable time window.
 *
 * <p>A duplicate is defined as a second (or subsequent) transaction from the same account with
 * an identical amount and merchant name arriving within {@code windowSeconds}. This pattern is
 * indicative of:
 * <ul>
 *   <li>Double-submission errors in point-of-sale integrations</li>
 *   <li>Replay attacks where a captured request is retransmitted by a fraudster</li>
 *   <li>Client-side retry loops that do not honour idempotency semantics</li>
 * </ul>
 *
 * <p>Deduplication state is maintained in Redis using a key composed of
 * {@code accountId:amount:merchant} with a TTL equal to {@code windowSeconds}. The first
 * transaction in a duplicate group sets the key (and is not flagged); all subsequent identical
 * transactions within the TTL window are flagged as duplicates.
 *
 * <p><strong>Configuration parameters</strong> (stored in {@code rule_configs.parameters}):
 * <ul>
 *   <li>{@code windowSeconds} — the duplicate detection window in seconds (default: {@code 60})</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DuplicateTransactionRule implements FraudRule {

    /** Canonical rule name used as the join key with the {@code rule_configs} database row. */
    public static final String RULE_NAME = "DUPLICATE_TRANSACTION_RULE";

    private final VelocityStorePort velocityStore;

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    /**
     * Checks whether a transaction with the same account, amount, and merchant has been seen
     * within the configured time window.
     *
     * <p>The check-and-set operation is delegated to {@link VelocityStorePort#isDuplicate}, which
     * atomically records the transaction if it is not a duplicate, ensuring the first occurrence
     * in any duplicate group is always allowed through.
     *
     * @param transaction the transaction to evaluate
     * @param parameters  rule configuration; reads {@code windowSeconds}
     * @return a {@link RuleResult} with {@code matched = true} and the full risk weight when a
     *         duplicate is detected; otherwise {@code matched = false} with a score of {@code 0}
     */
    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        int windowSeconds = parameters.getInt("windowSeconds", 60);

        // Delegate duplicate detection to Redis; the store handles key creation and TTL atomically
        boolean isDuplicate = velocityStore.isDuplicate(
                transaction.accountId(),
                transaction.amount().toPlainString(),
                transaction.merchantName(),
                transaction.id().toString(),
                windowSeconds
        );

        String description = isDuplicate
                ? String.format("Duplicate detected: same account, amount (%s), and merchant (%s) within %d seconds",
                        transaction.amount(), transaction.merchantName(), windowSeconds)
                : "No duplicate transaction detected";

        return new RuleResult(RULE_NAME, isDuplicate, isDuplicate ? parameters.riskWeight() : 0, description);
    }
}
