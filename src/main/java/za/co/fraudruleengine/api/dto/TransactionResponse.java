package za.co.fraudruleengine.api.dto;

import za.co.fraudruleengine.entity.TransactionEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * API response DTO for a submitted transaction and its fraud evaluation result.
 *
 * <p>Returned as the body of a {@code 201 Created} response from
 * {@code POST /api/v1/transactions} and as the body of a {@code 200 OK} response from
 * {@code GET /api/v1/transactions/{id}}.
 *
 * <p>Key result fields:
 * <ul>
 *   <li>{@code riskScore} — cumulative score from all rules that fired (0–100+).
 *       Scores above the configured threshold ({@code fraud.score.threshold}, default 60)
 *       cause a fraud alert to be raised and the transaction to be flagged.</li>
 *   <li>{@code fraudulent} — {@code true} when {@code riskScore ≥ threshold};
 *       this is the primary signal for downstream fraud-alert workflows.</li>
 * </ul>
 *
 * <p>The {@link #from(TransactionEntity)} factory method maps the JPA entity to this
 * immutable record, decoupling the REST contract from the persistence model.
 */
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
