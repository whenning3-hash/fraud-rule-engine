package za.co.fraudruleengine.api.dto;

import za.co.fraudruleengine.infrastructure.persistence.entity.RuleConfigEntity;

import java.util.Map;
import java.util.UUID;

public record RuleConfigResponse(
        UUID id,
        String ruleName,
        boolean enabled,
        int riskWeight,
        Map<String, String> parameters
) {
    public static RuleConfigResponse from(RuleConfigEntity entity) {
        return new RuleConfigResponse(
                entity.getId(),
                entity.getRuleName(),
                entity.isEnabled(),
                entity.getRiskWeight(),
                entity.getParameters()
        );
    }
}
