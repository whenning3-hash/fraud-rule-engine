package za.co.fraudruleengine.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.fraudruleengine.api.dto.RuleConfigResponse;
import za.co.fraudruleengine.api.dto.RuleConfigUpdateRequest;
import za.co.fraudruleengine.application.RuleConfigService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Tag(name = "Rules", description = "View and configure fraud detection rules")
public class RuleController {

    private final RuleConfigService ruleConfigService;

    @GetMapping
    @Operation(summary = "List all fraud rule configurations")
    public ResponseEntity<List<RuleConfigResponse>> listRules() {
        return ResponseEntity.ok(
                ruleConfigService.findAll().stream()
                        .map(RuleConfigResponse::from)
                        .toList()
        );
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a specific rule configuration")
    public ResponseEntity<RuleConfigResponse> getRule(@PathVariable UUID id) {
        return ResponseEntity.ok(RuleConfigResponse.from(ruleConfigService.findById(id)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a rule's enabled flag, risk weight, and parameters")
    public ResponseEntity<RuleConfigResponse> updateRule(
            @PathVariable UUID id,
            @Valid @RequestBody RuleConfigUpdateRequest request) {
        return ResponseEntity.ok(RuleConfigResponse.from(
                ruleConfigService.update(id, request.enabled(), request.riskWeight(), request.parameters())
        ));
    }
}
