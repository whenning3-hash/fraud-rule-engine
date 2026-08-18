package za.co.fraudruleengine.redis;

/**
 * Port interface for velocity and duplicate detection operations.
 * Decouples the fraud rules from the Redis implementation,
 * making rules trivially testable without infrastructure.
 */
public interface VelocityStorePort {

    /**
     * Returns the number of transactions recorded for the given account
     * within the specified time window (in minutes) ending now.
     */
    long getTransactionCount(String accountId, int windowMinutes);

    /**
     * Records a transaction in the velocity tracking store.
     */
    void recordTransaction(String accountId, String transactionId);

    /**
     * Checks if an identical transaction (same account, amount, merchant) was
     * seen within the given window. Records the fingerprint if this is the first occurrence.
     *
     * @return {@code true} if a duplicate was detected; {@code false} for first occurrence
     */
    boolean isDuplicate(String accountId, String amount, String merchantName,
                        String transactionId, int windowSeconds);
}
