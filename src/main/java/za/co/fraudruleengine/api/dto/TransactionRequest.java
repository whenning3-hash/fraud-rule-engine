package za.co.fraudruleengine.api.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Inbound DTO for a transaction submitted for fraud evaluation.
 *
 * <p>Field-level {@code @Size} constraints mirror the database column sizes defined in
 * {@code V1__create_transactions.sql} — this ensures that a client supplying an over-length
 * value receives a clean HTTP 400 with a descriptive field error rather than a cryptic 500
 * caused by a database truncation or constraint violation at the persistence layer.
 */
public record TransactionRequest(
        @NotBlank(message = "Account ID is required")
        @Size(max = 100, message = "Account ID must not exceed 100 characters")
        String accountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
        String currency,

        @NotBlank(message = "Merchant name is required")
        @Size(max = 255, message = "Merchant name must not exceed 255 characters")
        String merchantName,

        @Size(max = 100, message = "Merchant category must not exceed 100 characters")
        String merchantCategory,

        @NotBlank(message = "Channel is required")
        @Size(max = 20, message = "Channel must not exceed 20 characters")
        String channel,

        @Size(min = 2, max = 3, message = "Country code must be a 2- or 3-letter ISO code")
        String countryCode,

        @NotNull(message = "Transaction time is required")
        LocalDateTime transactionTime
) {}
