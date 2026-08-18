package za.co.fraudruleengine.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fraudruleengine.api.dto.TransactionRequest;
import za.co.fraudruleengine.domain.model.AlertStatus;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.EvaluationResult;
import za.co.fraudruleengine.domain.rule.RuleEngine;
import za.co.fraudruleengine.infrastructure.persistence.entity.FraudAlertEntity;
import za.co.fraudruleengine.infrastructure.persistence.entity.TransactionEntity;
import za.co.fraudruleengine.infrastructure.persistence.repository.AlertJpaRepository;
import za.co.fraudruleengine.infrastructure.persistence.repository.TransactionJpaRepository;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudEvaluationService {

    private final RuleEngine ruleEngine;
    private final TransactionJpaRepository transactionRepository;
    private final AlertJpaRepository alertRepository;
    private final ObjectMapper objectMapper;

    @Value("${fraud.score.threshold:60}")
    private int fraudScoreThreshold;

    @Transactional
    public TransactionEntity evaluate(TransactionRequest request) {
        TransactionEntity entity = mapToEntity(request);
        transactionRepository.save(entity);

        Transaction transaction = mapToDomain(entity);
        EvaluationResult result = ruleEngine.evaluate(transaction);

        entity.setRiskScore(result.totalScore());

        if (result.isFraudulent(fraudScoreThreshold)) {
            entity.setFraudulent(true);
            createAlert(entity, result);
            log.info("FRAUD DETECTED for transaction {} — score: {}, rules: {}",
                    entity.getId(), result.totalScore(), result.matchedRuleNames());
        } else {
            log.debug("Transaction {} passed — score: {} (threshold: {})",
                    entity.getId(), result.totalScore(), fraudScoreThreshold);
        }

        return transactionRepository.save(entity);
    }

    private void createAlert(TransactionEntity transaction, EvaluationResult result) {
        String ruleDetailsJson;
        try {
            ruleDetailsJson = objectMapper.writeValueAsString(result.ruleResults());
        } catch (JsonProcessingException e) {
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
