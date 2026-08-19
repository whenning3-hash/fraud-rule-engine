package za.co.fraudruleengine.api.dto;

import za.co.fraudruleengine.entity.RuleConfigEntity;

import java.util.Map;
import java.util.UUID;

/**
 * API response DTO representing the current configuration of a single fraud rule.
 *
 * <p>Returned by:
 * <ul>
 *   <li>{@code GET /api/v1/rules} (list of all rule configurations)</li>
 *   <li>{@code GET /api/v1/rules/{id}} (single rule lookup)</li>
 *   <li>{@code PUT /api/v1/rules/{id}} (rule update — returns updated configuration)</li>
 * </ul>
 *
 * <p>The {@link #from(RuleConfigEntity)} factory method maps the JPA entity to this
 * immutable record, keeping the REST API contract decoupled from the persistence model.
 *
 * <p>{@code parameters} is a key/value map of rule-specific thresholds
 * (e.g. {@code maxAmount}, {@code allowedCountries}) that operators can adjust at runtime
 * via the update endpoint to tune rule sensitivity without redeploying the service.
 */
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
