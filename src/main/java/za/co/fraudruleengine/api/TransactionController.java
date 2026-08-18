package za.co.fraudruleengine.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.fraudruleengine.api.dto.TransactionRequest;
import za.co.fraudruleengine.api.dto.TransactionResponse;
import za.co.fraudruleengine.application.FraudEvaluationService;
import za.co.fraudruleengine.infrastructure.persistence.entity.TransactionEntity;
import za.co.fraudruleengine.infrastructure.persistence.repository.TransactionJpaRepository;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Submit transactions for fraud evaluation")
public class TransactionController {

    private final FraudEvaluationService fraudEvaluationService;
    private final TransactionJpaRepository transactionRepository;

    @PostMapping
    @Operation(summary = "Submit a transaction for fraud evaluation")
    public ResponseEntity<TransactionResponse> submitTransaction(
            @Valid @RequestBody TransactionRequest request) {
        log.info("Transaction received — account: {}, amount: {} {}, channel: {}",
                request.accountId(), request.amount(), request.currency(), request.channel());
        TransactionEntity result = fraudEvaluationService.evaluate(request);
        log.debug("Transaction {} evaluated — fraudulent: {}, riskScore: {}",
                result.getId(), result.isFraudulent(), result.getRiskScore());
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get transaction details and fraud status")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable UUID id) {
        log.debug("Transaction lookup — id: {}", id);
        return transactionRepository.findById(id)
                .map(TransactionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.warn("Transaction not found: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }
}
