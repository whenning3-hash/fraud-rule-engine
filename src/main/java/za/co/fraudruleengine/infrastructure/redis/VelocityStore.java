package za.co.fraudruleengine.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class VelocityStore implements VelocityStorePort {

    private final StringRedisTemplate redisTemplate;

    private static final String VELOCITY_KEY_PREFIX = "velocity:";
    private static final String DUPLICATE_KEY_PREFIX = "dup:";

    public long getTransactionCount(String accountId, int windowMinutes) {
        String key = VELOCITY_KEY_PREFIX + accountId;
        long now = Instant.now().toEpochMilli();
        long windowStart = now - (windowMinutes * 60_000L);
        Long count = redisTemplate.opsForZSet().count(key, windowStart, now);
        long result = count != null ? count : 0L;
        log.debug("Velocity check — account: {}, window: {}min, count: {}", accountId, windowMinutes, result);
        return result;
    }

    public void recordTransaction(String accountId, String transactionId) {
        String key = VELOCITY_KEY_PREFIX + accountId;
        long now = Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(key, transactionId, now);
        redisTemplate.expire(key, Duration.ofHours(1));
        long cutoff = now - Duration.ofHours(1).toMillis();
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, cutoff);
        log.debug("Velocity recorded — account: {}, transactionId: {}", accountId, transactionId);
    }

    public boolean isDuplicate(String accountId, String amount, String merchantName,
                                String transactionId, int windowSeconds) {
        String sanitizedMerchant = merchantName.toLowerCase().replaceAll("\\s+", "_");
        String key = String.format("%s%s:%s:%s", DUPLICATE_KEY_PREFIX, accountId, amount, sanitizedMerchant);
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.FALSE.equals(exists)) {
            redisTemplate.opsForValue().set(key, transactionId, Duration.ofSeconds(windowSeconds));
            log.debug("Duplicate check — account: {}, amount: {}, merchant: {} — not a duplicate", accountId, amount, merchantName);
            return false;
        }
        log.debug("Duplicate check — account: {}, amount: {}, merchant: {} — DUPLICATE DETECTED", accountId, amount, merchantName);
        return true;
    }
}
