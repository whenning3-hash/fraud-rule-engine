package za.co.fraudruleengine.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable domain model representing a financial transaction submitted for fraud evaluation.
 *
 * <p>This record is the primary input to the fraud rule engine. It is a pure value object with no
 * JPA or persistence concerns — it is constructed from a {@link za.co.fraudruleengine.infrastructure.persistence.entity.TransactionEntity}
 * inside {@link za.co.fraudruleengine.application.FraudEvaluationService} and passed to
 * {@link za.co.fraudruleengine.domain.rule.RuleEngine#evaluate(Transaction)}.
 *
 * <p>Keeping the domain model separate from the JPA entity ensures that rule implementations have
 * no dependency on the persistence layer and can be unit-tested in isolation.
 *
 * @param id              unique identifier assigned at ingestion time
 * @param accountId       the customer account originating the transaction
 * @param amount          transaction amount; stored with up to 4 decimal places
 * @param currency        ISO 4217 currency code (e.g. {@code "ZAR"}, {@code "USD"})
 * @param merchantName    human-readable name of the receiving merchant
 * @param merchantCategory merchant category code (MCC) or classification string
 * @param channel         originating channel (e.g. {@code "ONLINE"}, {@code "POS"}, {@code "ATM"})
 * @param countryCode     ISO 3166-1 alpha-3 country code of the transaction (e.g. {@code "ZAF"})
 * @param transactionTime the moment at which the transaction occurred, used by time-based rules
 */
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
