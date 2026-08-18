package za.co.fraudruleengine.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record RuleConfigUpdateRequest(
        @NotNull
        boolean enabled,

        @Min(value = 0, message = "Risk weight must be non-negative")
        int riskWeight,

        @NotNull(message = "Parameters are required")
        Map<String, String> parameters
) {}
