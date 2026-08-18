package za.co.fraudruleengine.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.fraudruleengine.api.dto.RuleConfigResponse;
import za.co.fraudruleengine.api.dto.RuleConfigUpdateRequest;
import za.co.fraudruleengine.service.RuleConfigService;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for inspecting and modifying fraud detection rule configurations.
 *
 * <p>The rule engine fetches live configuration from the database on every evaluation, so updates
 * made through this controller take effect immediately on the next transaction submission — no
 * restart or cache invalidation is required. This makes it possible for a fraud-operations team
 * to respond dynamically to emerging fraud patterns by:
 * <ul>
 *   <li>Lowering a rule's risk weight to reduce false positives during a known high-traffic event</li>
 *   <li>Tightening a velocity threshold during an active card-testing attack</li>
 *   <li>Disabling a rule temporarily for maintenance or investigation</li>
 * </ul>
 *
 * <p>All endpoints under {@code /api/v1/rules} require a valid JWT Bearer token unless security
 * is disabled via {@code fraud.security.enabled=false}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Rules", description = "View and configure fraud detection rules")
public class RuleController {

    private final RuleConfigService ruleConfigService;

    /**
     * Returns the current configuration for all registered fraud rules.
     *
     * <p>The result includes all rules seeded during database initialisation, regardless of
     * whether they are currently enabled or disabled.
     *
     * @return HTTP 200 OK with a list of {@link RuleConfigResponse} objects; one per registered rule
     */
    @GetMapping
    @Operation(summary = "List all fraud rule configurations")
    public ResponseEntity<List<RuleConfigResponse>> listRules() {
        return ResponseEntity.ok(
                ruleConfigService.findAll().stream()
                        .map(RuleConfigResponse::from)
                        .toList()
        );
    }

    /**
     * Retrieves the configuration for a single fraud rule by its UUID.
     *
     * @param id the UUID of the rule configuration to retrieve
     * @return HTTP 200 OK with the {@link RuleConfigResponse}, or HTTP 404 if not found
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a specific rule configuration")
    public ResponseEntity<RuleConfigResponse> getRule(@PathVariable UUID id) {
        return ResponseEntity.ok(RuleConfigResponse.from(ruleConfigService.findById(id)));
    }

    /**
     * Updates the enabled flag, risk weight, and parameter map for a specific rule configuration.
     *
     * <p>All three mutable fields are replaced in a single request. Partial updates are not
     * supported — the caller must supply the complete desired state for all three fields. The
     * before and after state are logged at INFO level to provide an audit trail.
     *
     * @param id      the UUID of the rule configuration to update
     * @param request the new configuration values; must pass Bean Validation constraints
     * @return HTTP 200 OK with the updated {@link RuleConfigResponse}
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Update a rule's enabled flag, risk weight, and parameters")
    public ResponseEntity<RuleConfigResponse> updateRule(
            @PathVariable UUID id,
            @Valid @RequestBody RuleConfigUpdateRequest request) {
        log.info("Rule config update — id: {}, enabled: {}, riskWeight: {}",
                id, request.enabled(), request.riskWeight());
        RuleConfigResponse updated = RuleConfigResponse.from(
                ruleConfigService.update(id, request.enabled(), request.riskWeight(), request.parameters()));
        log.info("Rule {} ({}) updated — enabled: {}, riskWeight: {}",
                id, updated.ruleName(), updated.enabled(), updated.riskWeight());
        return ResponseEntity.ok(updated);
    }
}
