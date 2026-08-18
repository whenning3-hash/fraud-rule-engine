package za.co.fraudruleengine.api.dto;

import jakarta.validation.constraints.NotNull;
import za.co.fraudruleengine.domain.model.AlertStatus;

public record AlertStatusUpdateRequest(
        @NotNull(message = "Status is required")
        AlertStatus status
) {}
