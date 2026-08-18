package za.co.fraudruleengine.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.fraudruleengine.api.dto.AlertResponse;
import za.co.fraudruleengine.api.dto.AlertStatusUpdateRequest;
import za.co.fraudruleengine.application.AlertQueryService;
import za.co.fraudruleengine.domain.model.AlertStatus;

import java.util.UUID;

/**
 * REST controller for querying and managing fraud alerts raised by the rule engine.
 *
 * <p>Fraud alerts are created automatically when a transaction's aggregate risk score meets the
 * configured threshold. This controller provides the endpoints through which a fraud-operations
 * team interacts with those alerts: listing open alerts for triage, inspecting individual alert
 * detail, and advancing alerts through the {@link AlertStatus} lifecycle.
 *
 * <p>All endpoints under {@code /api/v1/alerts} require a valid JWT Bearer token unless security
 * is disabled via {@code fraud.security.enabled=false}.
 *
 * <p>List results are paginated (default page size 20, sorted by {@code createdAt}) to support
 * high-volume fraud operations dashboards without unbounded result sets.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@Tag(name = "Alerts", description = "Query and manage fraud alerts")
public class AlertController {

    private final AlertQueryService alertQueryService;

    /**
     * Returns a paginated list of fraud alerts, with optional filtering by account and/or status.
     *
     * <p>Typical use cases:
     * <ul>
     *   <li>Operations dashboard: {@code GET /api/v1/alerts?status=OPEN} to show the triage queue</li>
     *   <li>Account investigation: {@code GET /api/v1/alerts?accountId=ACC123} for a full history</li>
     *   <li>Combined filter: {@code ?accountId=ACC123&status=REVIEWED}</li>
     * </ul>
     *
     * @param accountId optional filter; only alerts for this account are returned when supplied
     * @param status    optional filter; only alerts in this {@link AlertStatus} are returned when supplied
     * @param pageable  pagination and sort parameters; defaults to 20 results per page sorted by creation time
     * @return HTTP 200 OK with a {@link Page} of {@link AlertResponse} objects
     */
    @GetMapping
    @Operation(summary = "List fraud alerts with optional filters")
    public ResponseEntity<Page<AlertResponse>> listAlerts(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) AlertStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(
                alertQueryService.findAlerts(accountId, status, pageable)
                        .map(AlertResponse::from)
        );
    }

    /**
     * Retrieves a single fraud alert by ID, including the full matched rule detail payload.
     *
     * <p>The {@code ruleDetails} field in the response contains the complete JSON snapshot of
     * all rule evaluation results, enabling analysts to understand exactly which signals fired
     * and why.
     *
     * @param id the UUID of the alert to retrieve
     * @return HTTP 200 OK with the {@link AlertResponse}, or HTTP 404 if the alert does not exist
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a fraud alert by ID including matched rule details")
    public ResponseEntity<AlertResponse> getAlert(@PathVariable UUID id) {
        return ResponseEntity.ok(AlertResponse.from(alertQueryService.findById(id)));
    }

    /**
     * Updates the lifecycle status of a fraud alert (e.g. from {@code OPEN} to {@code REVIEWED}).
     *
     * <p>Used by fraud analysts to track investigation progress. The transition is logged both
     * at the controller level (request intent) and within {@link AlertQueryService} (before/after
     * state), providing a full audit trail.
     *
     * @param id      the UUID of the alert to update
     * @param request the status update payload; must contain a valid {@link AlertStatus} value
     * @return HTTP 200 OK with the updated {@link AlertResponse}
     */
    @PatchMapping("/{id}/status")
    @Operation(summary = "Update the status of a fraud alert")
    public ResponseEntity<AlertResponse> updateAlertStatus(
            @PathVariable UUID id,
            @Valid @RequestBody AlertStatusUpdateRequest request) {
        log.info("Alert status update requested — id: {}, newStatus: {}", id, request.status());
        AlertResponse updated = AlertResponse.from(alertQueryService.updateStatus(id, request.status()));
        log.info("Alert {} status updated to {}", id, request.status());
        return ResponseEntity.ok(updated);
    }
}
