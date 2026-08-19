package za.co.fraudruleengine.api.dto;

import za.co.fraudruleengine.model.AlertStatus;
import za.co.fraudruleengine.entity.FraudAlertEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * API response DTO representing a single fraud alert.
 *
 * <p>Returned by:
 * <ul>
 *   <li>{@code GET /api/v1/fraud-alerts} (paginated list)</li>
 *   <li>{@code GET /api/v1/fraud-alerts/{id}} (single lookup)</li>
 *   <li>{@code PATCH /api/v1/fraud-alerts/{id}/status} (status update — returns updated alert)</li>
 * </ul>
 *
 * <p>The {@link #from(FraudAlertEntity)} factory method maps the JPA entity to this
 * immutable record, keeping the API contract decoupled from the persistence model.
 *
 * <p>{@code matchedRules} lists the rule names that fired (e.g. {@code HIGH_AMOUNT},
 * {@code OFF_HOURS_TRANSACTION}).  {@code ruleDetails} is a human-readable summary of
 * the triggered conditions, stored verbatim from the rule engine output.
 */
public record AlertResponse(
        UUID id,
        UUID transactionId,
        String accountId,
        int totalRiskScore,
        AlertStatus status,
        List<String> matchedRules,
        String ruleDetails,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AlertResponse from(FraudAlertEntity entity) {
        return new AlertResponse(
                entity.getId(),
                entity.getTransactionId(),
                entity.getAccountId(),
                entity.getTotalRiskScore(),
                entity.getStatus(),
                entity.getMatchedRules(),
                entity.getRuleDetails(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
