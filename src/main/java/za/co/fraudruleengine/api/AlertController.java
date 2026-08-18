package za.co.fraudruleengine.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@Tag(name = "Alerts", description = "Query and manage fraud alerts")
public class AlertController {

    private final AlertQueryService alertQueryService;

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

    @GetMapping("/{id}")
    @Operation(summary = "Get a fraud alert by ID including matched rule details")
    public ResponseEntity<AlertResponse> getAlert(@PathVariable UUID id) {
        return ResponseEntity.ok(AlertResponse.from(alertQueryService.findById(id)));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update the status of a fraud alert")
    public ResponseEntity<AlertResponse> updateAlertStatus(
            @PathVariable UUID id,
            @Valid @RequestBody AlertStatusUpdateRequest request) {
        return ResponseEntity.ok(
                AlertResponse.from(alertQueryService.updateStatus(id, request.status()))
        );
    }
}
