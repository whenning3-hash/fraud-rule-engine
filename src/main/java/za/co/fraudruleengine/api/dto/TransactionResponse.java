package za.co.fraudruleengine.api.dto;

import za.co.fraudruleengine.entity.TransactionEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String accountId,
        BigDecimal amount,
        String currency,
        String merchantName,
        String merchantCategory,
        String channel,
        String countryCode,
        LocalDateTime transactionTime,
        int riskScore,
        boolean fraudulent,
        LocalDateTime createdAt
) {
    public static TransactionResponse from(TransactionEntity entity) {
        return new TransactionResponse(
                entity.getId(),
                entity.getAccountId(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getMerchantName(),
                entity.getMerchantCategory(),
                entity.getChannel(),
                entity.getCountryCode(),
                entity.getTransactionTime(),
                entity.getRiskScore(),
                entity.isFraudulent(),
                entity.getCreatedAt()
        );
    }
}
