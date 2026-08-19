package za.co.fraudruleengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fraudruleengine.api.dto.TransactionRequest;
import za.co.fraudruleengine.model.AlertStatus;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.EvaluationResult;
import za.co.fraudruleengine.rule.RuleEngine;
import za.co.fraudruleengine.entity.FraudAlertEntity;
import za.co.fraudruleengine.entity.TransactionEntity;
import za.co.fraudruleengine.repository.AlertJpaRepository;
import za.co.fraudruleengine.repository.TransactionJpaRepository;
import za.co.fraudruleengine.util.LogMaskUtil;

import java.util.UUID;

/**
 * Application service that orchestrates the end-to-end fraud evaluation workflow for a
 * single incoming transaction.
 *
 * <p>This class sits at the boundary between the HTTP API layer and the domain model. Its
 * responsibilities are:
 * <ol>
 *   <li><strong>Persistence of the raw transaction</strong> — the entity is saved before
 *       evaluation so that every submitted transaction is recorded regardless of the
 *       evaluation outcome (important for audit and replay scenarios).</li>
 *   <li><strong>Domain mapping</strong> — converts between the JPA entity (persistence
 *       concern) and the immutable {@link Transaction} domain model (rule concern), keeping
 *       those two layers decoupled.</li>
 *   <li><strong>Rule engine delegation</strong> — passes the domain model to the
 *       {@link RuleEngine} and receives an {@link EvaluationResult}.</li>
 *   <li><strong>Alert creation</strong> — when the risk score meets or exceeds the configured
 *       threshold, creates a {@link FraudAlertEntity} in the {@code OPEN} state, including a
 *       JSON snapshot of all individual rule results for analyst investigation.</li>
 *   <li><strong>Risk score write-back</strong> — updates the transaction entity with the
 *       computed score and fraudulent flag before the final save.</li>
 * </ol>
 *
 * <p>The entire operation runs within a single database transaction ({@code @Transactional})
 * so that the transaction record and any associated alert are always persisted atomically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudEvaluationService {

    private final RuleEngine ruleEngine;
    private final TransactionJpaRepository transactionRepository;
    private final AlertJpaRepository alertRepository;
    private final ObjectMapper objectMapper;

    /** Configurable via {@code fraud.score.threshold}; defaults to {@code 60}. */
    @Value("${fraud.score.threshold:60}")
    private int fraudScoreThreshold;

    /**
     * Persists the transaction, runs it through the fraud rule engine, and raises a fraud alert
     * if the aggregate risk score meets the threshold.
     *
     * <p>The transaction is saved twice: once before evaluation (to guarantee persistence) and
     * once after (to capture the computed risk score and fraudulent flag). Both saves occur
     * within the same transaction boundary.
     *
     * @param request the inbound transaction payload from the API layer; must pass Bean Validation
     * @return the fully persisted {@link TransactionEntity} populated with {@code riskScore} and
     *         {@code fraudulent} flag after evaluation; never {@code null}
     */
    @Transactional
    public TransactionEntity evaluate(TransactionRequest request) {
        TransactionEntity entity = mapToEntity(request);
        // Capture return value: JPA save() may return a managed proxy that differs from the
        // passed instance (e.g. if an ID-generation strategy or entity listener modifies it).
        entity = transactionRepository.save(entity);

        Transaction transaction = mapToDomain(entity);
        EvaluationResult result = ruleEngine.evaluate(transaction);

        entity.setRiskScore(result.totalScore());

        if (result.isFraudulent(fraudScoreThreshold)) {
            entity.setFraudulent(true);
            createAlert(entity, result);
            log.info("FRAUD DETECTED — transaction: {}, account: {}, score: {}, rules: {}",
                    entity.getId(), LogMaskUtil.maskAccount(entity.getAccountId()),
                    result.totalScore(), result.matchedRuleNames());
        } else {
            log.debug("Transaction {} passed — score: {} (threshold: {})",
                    entity.getId(), result.totalScore(), fraudScoreThreshold);
        }

        return transactionRepository.save(entity);
    }

    /**
     * Creates and persists a {@link FraudAlertEntity} for a transaction that has been classified
     * as fraudulent.
     *
     * <p>The full list of {@link za.co.fraudruleengine.rule.RuleResult} objects is
     * serialised to JSON and stored in {@code rule_details} to give analysts the complete
     * evidence picture, including rules that did not fire. If serialisation fails (which should
     * not happen in normal operation), an empty array is stored rather than failing the whole
     * transaction — the alert is still raised, just without the detailed breakdown.
     *
     * @param transaction the persisted transaction entity that triggered the alert
     * @param result      the aggregated evaluation result containing matched rule names and scores
     */
    private void createAlert(TransactionEntity transaction, EvaluationResult result) {
        String ruleDetailsJson;
        try {
            ruleDetailsJson = objectMapper.writeValueAsString(result.ruleResults());
        } catch (JsonProcessingException e) {
            // Serialisation failure must not prevent the alert from being persisted
            log.warn("Failed to serialize rule results for transaction {} — storing empty detail: {}",
                    transaction.getId(), e.getMessage());
            ruleDetailsJson = "[]";
        }

        FraudAlertEntity alert = FraudAlertEntity.builder()
                .id(UUID.randomUUID())
                .transactionId(transaction.getId())
                .accountId(transaction.getAccountId())
                .totalRiskScore(result.totalScore())
                .status(AlertStatus.OPEN)
                .matchedRules(result.matchedRuleNames())
                .ruleDetails(ruleDetailsJson)
                .build();

        alertRepository.save(alert);
    }

    /**
     * Maps a persisted {@link TransactionEntity} to the immutable {@link Transaction} domain
     * model consumed by the rule engine.
     *
     * <p>This separation ensures that the domain rule logic has no dependency on JPA annotations
     * or persistence concerns.
     *
     * @param entity the persisted entity with a populated UUID
     * @return an equivalent immutable domain model
     */
    private Transaction mapToDomain(TransactionEntity entity) {
        return new Transaction(
                entity.getId(),
                entity.getAccountId(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getMerchantName(),
                entity.getMerchantCategory(),
                entity.getChannel(),
                entity.getCountryCode(),
                entity.getTransactionTime()
        );
    }

    /**
     * Converts an incoming {@link TransactionRequest} DTO into a new {@link TransactionEntity}
     * ready for initial persistence.
     *
     * <p>The UUID is generated here rather than by the database to allow the transaction ID to
     * be logged and referenced by the rule engine before the first save completes.
     *
     * @param request the validated inbound DTO
     * @return a new entity with a generated UUID, all request fields populated, and
     *         {@code riskScore = 0} / {@code fraudulent = false} as pre-evaluation defaults
     */
    private TransactionEntity mapToEntity(TransactionRequest request) {
        return TransactionEntity.builder()
                .id(UUID.randomUUID())
                .accountId(request.accountId())
                .amount(request.amount())
                .currency(request.currency())
                .merchantName(request.merchantName())
                .merchantCategory(request.merchantCategory())
                .channel(request.channel())
                .countryCode(request.countryCode())
                .transactionTime(request.transactionTime())
                .riskScore(0)
                .fraudulent(false)
                .build();
    }
}
