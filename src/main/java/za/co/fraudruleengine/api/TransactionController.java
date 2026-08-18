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
import za.co.fraudruleengine.service.FraudEvaluationService;
import za.co.fraudruleengine.entity.TransactionEntity;
import za.co.fraudruleengine.repository.TransactionJpaRepository;

import java.util.UUID;

/**
 * REST controller for submitting transactions for fraud evaluation and retrieving their results.
 *
 * <p>This is the primary entry point into the fraud engine for upstream systems. Clients POST a
 * transaction and receive a synchronous response that includes the computed risk score and
 * fraudulent classification. This synchronous design keeps integration straightforward; if
 * asynchronous processing were required in future, the POST could be made to return an accepted
 * status with a callback or polling URL.
 *
 * <p>All endpoints under {@code /api/v1/transactions} require a valid JWT Bearer token unless
 * security is disabled via {@code fraud.security.enabled=false}.
 *
 * <p>Validation failures (missing required fields, invalid types) are handled centrally by
 * {@link GlobalExceptionHandler} and returned as RFC 7807 {@code ProblemDetail} responses.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Submit transactions for fraud evaluation")
public class TransactionController {

    private final FraudEvaluationService fraudEvaluationService;
    private final TransactionJpaRepository transactionRepository;

    /**
     * Submits a transaction for fraud evaluation and returns the evaluation result.
     *
     * <p>The transaction is persisted, evaluated against all enabled fraud rules, and the
     * resulting risk score and fraud flag are returned synchronously. If the score meets the
     * configured threshold, a fraud alert is also created in the background within the same
     * database transaction.
     *
     * @param request the inbound transaction payload; must pass Bean Validation constraints
     * @return HTTP 201 Created with a {@link TransactionResponse} body containing the risk
     *         score and whether the transaction was classified as fraudulent
     */
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

    /**
     * Retrieves the persisted details and fraud evaluation result for a specific transaction.
     *
     * <p>Useful for clients that need to look up the evaluation outcome after the fact, for
     * example to display the result in an operations console or to resolve a dispute.
     *
     * @param id the UUID of the transaction to retrieve
     * @return HTTP 200 OK with the {@link TransactionResponse}, or HTTP 404 Not Found if no
     *         transaction with the given ID exists
     */
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
