package za.co.fraudruleengine.api.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionRequest(
        @NotBlank(message = "Account ID is required")
        String accountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
        String currency,

        @NotBlank(message = "Merchant name is required")
        String merchantName,

        String merchantCategory,

        @NotBlank(message = "Channel is required")
        String channel,

        String countryCode,

        @NotNull(message = "Transaction time is required")
        LocalDateTime transactionTime
) {}
