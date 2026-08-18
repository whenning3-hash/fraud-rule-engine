package za.co.fraudruleengine.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record Transaction(
        UUID id,
        String accountId,
        BigDecimal amount,
        String currency,
        String merchantName,
        String merchantCategory,
        String channel,
        String countryCode,
        LocalDateTime transactionTime
) {}
