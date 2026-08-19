package za.co.fraudruleengine.api.dto;

import jakarta.validation.constraints.NotNull;
import za.co.fraudruleengine.model.AlertStatus;

/**
 * Request body for the {@code PATCH /api/v1/fraud-alerts/{id}/status} endpoint.
 *
 * <p>Carries the single target {@link AlertStatus} value that the caller wants to
 * transition the alert to.  The application layer enforces the allowed state-machine
 * transitions ({@code OPEN → REVIEWED → CLOSED}); supplying a status that violates the
 * machine results in a {@code 409 Conflict} response.
 *
 * <p>{@code status} is validated as {@code @NotNull} so that a missing or null body
 * field returns a {@code 400 Bad Request} before the service layer is reached.
 */
public record AlertStatusUpdateRequest(
        @NotNull(message = "Status is required")
        AlertStatus status
) {}
