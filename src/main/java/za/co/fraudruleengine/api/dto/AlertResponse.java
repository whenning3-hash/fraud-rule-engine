package za.co.fraudruleengine.api.dto;

import za.co.fraudruleengine.domain.model.AlertStatus;
import za.co.fraudruleengine.infrastructure.persistence.entity.FraudAlertEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
