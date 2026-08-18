package za.co.fraudruleengine.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import za.co.fraudruleengine.util.LogMaskUtil;

/**
 * Redis-backed implementation of {@link VelocityStorePort} that tracks transaction frequency
 * and detects duplicate transactions using time-windowed data structures.
 *
 * <p>Two separate Redis key spaces are managed:
 * <ul>
 *   <li><strong>Velocity keys</strong> ({@code velocity:<accountId>}) — Redis sorted sets where
 *       each member is a transaction ID and the score is the Unix epoch timestamp in milliseconds.
 *       Time-window queries use {@code ZCOUNT} over a score range, which runs in O(log N) time.
 *       Keys expire after 1 hour; stale entries beyond 1 hour are also pruned on each write to
 *       bound memory usage.</li>
 *   <li><strong>Duplicate keys</strong> ({@code dup:<accountId>:<amount>:<merchant>}) — simple
 *       string keys with a TTL equal to the configured duplicate detection window. The first
 *       occurrence of a given (account, amount, merchant) tuple sets the key; any subsequent
 *       occurrence within the TTL window finds the key already present and returns a duplicate
 *       signal.</li>
 * </ul>
 *
 * <p>All Redis operations use {@link StringRedisTemplate} so keys and values are human-readable
 * strings, which simplifies operational debugging via the Redis CLI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VelocityStore implements VelocityStorePort {

    private final StringRedisTemplate redisTemplate;

    /** Key prefix for sorted-set velocity tracking per account. */
    private static final String VELOCITY_KEY_PREFIX = "velocity:";

    /** Key prefix for duplicate detection string keys per account/amount/merchant tuple. */
    private static final String DUPLICATE_KEY_PREFIX = "dup:";

    /**
     * Returns the number of transactions recorded for the given account within the specified
     * rolling time window.
     *
     * <p>Uses Redis {@code ZCOUNT} over the sorted-set score range
     * {@code [now - windowMinutes*60000, now]} to count only entries within the window, without
     * loading the full set into memory.
     *
     * @param accountId     the account whose transaction count to query
     * @param windowMinutes the width of the rolling time window in minutes
     * @return the number of recorded transactions within the window; {@code 0} if none or if
     *         the key does not exist
     */
    public long getTransactionCount(String accountId, int windowMinutes) {
        String key = VELOCITY_KEY_PREFIX + accountId;
        long now = Instant.now().toEpochMilli();
        long windowStart = now - (windowMinutes * 60_000L);
        Long count = redisTemplate.opsForZSet().count(key, windowStart, now);
        long result = count != null ? count : 0L;
        log.debug("Velocity check — account: {}, window: {}min, count: {}", LogMaskUtil.maskAccount(accountId), windowMinutes, result);
        return result;
    }

    /**
     * Records a transaction in the velocity sorted set for the given account.
     *
     * <p>The transaction ID is stored as the member and the current epoch millisecond timestamp
     * as the score, enabling efficient range queries. After recording, two maintenance operations
     * are performed:
     * <ol>
     *   <li>The key's TTL is refreshed to 1 hour to prevent indefinite growth.</li>
     *   <li>Entries older than 1 hour are pruned via {@code ZREMRANGEBYSCORE} to bound memory
     *       consumption — velocity data older than 1 hour has no further use.</li>
     * </ol>
     *
     * @param accountId     the account to record the transaction against
     * @param transactionId the unique transaction identifier used as the sorted-set member
     */
    public void recordTransaction(String accountId, String transactionId) {
        String key = VELOCITY_KEY_PREFIX + accountId;
        long now = Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(key, transactionId, now);
        // Refresh TTL to ensure the key survives at least 1 more hour from now
        redisTemplate.expire(key, Duration.ofHours(1));
        // Prune entries older than 1 hour to prevent unbounded sorted-set growth
        long cutoff = now - Duration.ofHours(1).toMillis();
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, cutoff);
        log.debug("Velocity recorded — account: {}, transactionId: {}", LogMaskUtil.maskAccount(accountId), transactionId);
    }

    /**
     * Determines whether a transaction with the same account, amount, and merchant has been
     * seen within the configured duplicate detection window.
     *
     * <p>The deduplication key is constructed as {@code dup:<accountId>:<amount>:<sanitisedMerchant>}
     * where the merchant name is lowercased and whitespace is replaced with underscores to
     * normalise minor formatting differences. The key existence check and conditional set are
     * performed as two separate operations — this is not atomic, but the inherent idempotency
     * of the key-set operation means the worst case is a brief window where two concurrent
     * transactions of the same type could both be considered non-duplicates on first check.
     *
     * @param accountId     the originating account
     * @param amount        the transaction amount as a plain decimal string (no currency symbol)
     * @param merchantName  the receiving merchant name; whitespace is normalised before keying
     * @param transactionId the current transaction ID, stored as the key value for traceability
     * @param windowSeconds the TTL of the duplicate detection key in seconds
     * @return {@code true} if a duplicate key already exists in Redis (i.e. a matching
     *         transaction was already seen within {@code windowSeconds}); {@code false} if the
     *         current transaction is the first of its kind in the window
     */
    public boolean isDuplicate(String accountId, String amount, String merchantName,
                                String transactionId, int windowSeconds) {
        // Normalise merchant name to reduce false negatives from minor formatting variation
        String sanitizedMerchant = merchantName.toLowerCase().replaceAll("\\s+", "_");
        String key = String.format("%s%s:%s:%s", DUPLICATE_KEY_PREFIX, accountId, amount, sanitizedMerchant);

        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.FALSE.equals(exists)) {
            // First occurrence: set the key with the configured TTL so subsequent calls detect it
            redisTemplate.opsForValue().set(key, transactionId, Duration.ofSeconds(windowSeconds));
            log.debug("Duplicate check — account: {}, amount: {}, merchant: {} — not a duplicate",
                    LogMaskUtil.maskAccount(accountId), LogMaskUtil.maskAmount(amount), merchantName);
            return false;
        }
        log.debug("Duplicate check — account: {}, amount: {}, merchant: {} — DUPLICATE DETECTED",
                LogMaskUtil.maskAccount(accountId), LogMaskUtil.maskAmount(amount), merchantName);
        return true;
    }
}
