package za.co.fraudruleengine.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request body for the {@code PUT /api/v1/rules/{id}} endpoint.
 *
 * <p>Allows operators to hot-tune individual fraud rules at runtime without redeploying
 * the service.  Three properties are updatable:
 * <ul>
 *   <li>{@code enabled} — activates or deactivates the rule in the evaluation pipeline.
 *       Disabling a rule immediately stops it from contributing to risk scores.</li>
 *   <li>{@code riskWeight} — the score added to a transaction when this rule fires.
 *       Must be non-negative; higher values make the rule a stronger fraud signal.</li>
 *   <li>{@code parameters} — rule-specific key/value thresholds (e.g. {@code maxAmount},
 *       {@code allowedCountries}).  The exact keys are rule-implementation-defined and
 *       documented in the OpenAPI spec.</li>
 * </ul>
 *
 * <p>All fields are required ({@code @NotNull}).  Partial updates are not supported —
 * callers must supply the complete desired state.
 */
public record RuleConfigUpdateRequest(
        @NotNull
        boolean enabled,

        @Min(value = 0, message = "Risk weight must be non-negative")
        int riskWeight,

        @NotNull(message = "Parameters are required")
        Map<String, String> parameters
) {}
