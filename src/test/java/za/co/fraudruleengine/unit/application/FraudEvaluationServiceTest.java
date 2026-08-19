package za.co.fraudruleengine.unit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import za.co.fraudruleengine.api.dto.TransactionRequest;
import za.co.fraudruleengine.service.FraudEvaluationService;
import za.co.fraudruleengine.model.AlertStatus;
import za.co.fraudruleengine.rule.EvaluationResult;
import za.co.fraudruleengine.rule.RuleEngine;
import za.co.fraudruleengine.rule.RuleResult;
import za.co.fraudruleengine.entity.FraudAlertEntity;
import za.co.fraudruleengine.entity.TransactionEntity;
import za.co.fraudruleengine.repository.AlertJpaRepository;
import za.co.fraudruleengine.repository.TransactionJpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudEvaluationServiceTest {

    @Mock RuleEngine ruleEngine;
    @Mock TransactionJpaRepository transactionRepository;
    @Mock AlertJpaRepository alertRepository;
    @Mock ObjectMapper objectMapper;

    @InjectMocks
    FraudEvaluationService service;

    @BeforeEach
    void setUp() {
        // @Value("${fraud.score.threshold:60}") is not injected in plain Mockito tests;
        // set the field explicitly so tests run with the real production default.
        ReflectionTestUtils.setField(service, "fraudScoreThreshold", 60);
    }

    @Test
    void shouldSaveTransactionAndNotCreateAlertWhenScoreBelowThreshold() throws JsonProcessingException {
        TransactionRequest request = buildRequest("ACC-001", new BigDecimal("500.00"),
                LocalDateTime.now().withHour(10));
        EvaluationResult lowScore = new EvaluationResult(
                List.of(new RuleResult("AMOUNT_THRESHOLD_RULE", false, 0, "ok")), 0);

        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleEngine.evaluate(any())).thenReturn(lowScore);

        TransactionEntity result = service.evaluate(request);

        assertThat(result.getRiskScore()).isZero();
        assertThat(result.isFraudulent()).isFalse();
        verify(alertRepository, never()).save(any());
        verify(transactionRepository, times(2)).save(any()); // initial save + update after scoring
    }

    @Test
    void shouldCreateAlertWhenScoreMeetsThreshold() throws JsonProcessingException {
        TransactionRequest request = buildRequest("ACC-002", new BigDecimal("15000.00"),
                LocalDateTime.now().withHour(3));
        EvaluationResult highScore = new EvaluationResult(List.of(
                new RuleResult("AMOUNT_THRESHOLD_RULE", true, 40, "exceeded"),
                new RuleResult("OFF_HOURS_RULE", true, 20, "off-hours")
        ), 60);

        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleEngine.evaluate(any())).thenReturn(highScore);
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        TransactionEntity result = service.evaluate(request);

        assertThat(result.getRiskScore()).isEqualTo(60);
        assertThat(result.isFraudulent()).isTrue();
        verify(alertRepository).save(any(FraudAlertEntity.class));
    }

    @Test
    void alertShouldHaveCorrectFieldsWhenCreated() throws JsonProcessingException {
        TransactionRequest request = buildRequest("ACC-003", new BigDecimal("20000.00"),
                LocalDateTime.now().withHour(2));
        EvaluationResult fraud = new EvaluationResult(List.of(
                new RuleResult("AMOUNT_THRESHOLD_RULE", true, 40, "exceeded"),
                new RuleResult("OFF_HOURS_RULE", true, 20, "off-hours")
        ), 60);

        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleEngine.evaluate(any())).thenReturn(fraud);
        when(objectMapper.writeValueAsString(any())).thenReturn("[{\"matched\":true}]");

        service.evaluate(request);

        ArgumentCaptor<FraudAlertEntity> alertCaptor = ArgumentCaptor.forClass(FraudAlertEntity.class);
        verify(alertRepository).save(alertCaptor.capture());

        FraudAlertEntity savedAlert = alertCaptor.getValue();
        assertThat(savedAlert.getAccountId()).isEqualTo("ACC-003");
        assertThat(savedAlert.getTotalRiskScore()).isEqualTo(60);
        assertThat(savedAlert.getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(savedAlert.getMatchedRules()).containsExactlyInAnyOrder(
                "AMOUNT_THRESHOLD_RULE", "OFF_HOURS_RULE");
        assertThat(savedAlert.getTransactionId()).isNotNull();
        assertThat(savedAlert.getId()).isNotNull();
    }

    @Test
    void shouldSetTransactionFieldsCorrectlyFromRequest() throws JsonProcessingException {
        LocalDateTime txTime = LocalDateTime.of(2025, 1, 15, 10, 30);
        TransactionRequest request = new TransactionRequest(
                "ACC-099", new BigDecimal("750.00"), "ZAR",
                "Test Shop", "GROCERY", "POS", "ZA", txTime);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleEngine.evaluate(any())).thenReturn(new EvaluationResult(List.of(), 0));

        ArgumentCaptor<TransactionEntity> txCaptor = ArgumentCaptor.forClass(TransactionEntity.class);
        service.evaluate(request);

        verify(transactionRepository, atLeastOnce()).save(txCaptor.capture());
        TransactionEntity saved = txCaptor.getAllValues().get(0);
        assertThat(saved.getAccountId()).isEqualTo("ACC-099");
        assertThat(saved.getAmount()).isEqualByComparingTo("750.00");
        assertThat(saved.getCurrency()).isEqualTo("ZAR");
        assertThat(saved.getMerchantName()).isEqualTo("Test Shop");
        assertThat(saved.getChannel()).isEqualTo("POS");
        assertThat(saved.getTransactionTime()).isEqualTo(txTime);
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void shouldHandleJsonSerializationFailureGracefully() throws JsonProcessingException {
        TransactionRequest request = buildRequest("ACC-004", new BigDecimal("50000.00"),
                LocalDateTime.now().withHour(3));
        EvaluationResult highScore = new EvaluationResult(
                List.of(new RuleResult("AMOUNT_THRESHOLD_RULE", true, 60, "matched")), 60);

        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleEngine.evaluate(any())).thenReturn(highScore);
        when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("fail") {});

        // Should not throw — falls back to "[]"
        TransactionEntity result = service.evaluate(request);
        assertThat(result.isFraudulent()).isTrue();
    }

    @Test
    void initialSave_returnValueIsUsedForSubsequentOperations() throws JsonProcessingException {
        // Arrange: first save returns a "managed" entity with a specific known UUID
        // (simulating a JPA proxy or an entity listener that modifies the returned instance).
        // The alert's transactionId must reference the UUID from the managed entity, not the
        // pre-save instance — this proves the `entity = save(entity)` assignment is honoured.
        TransactionRequest request = buildRequest("ACC-005", new BigDecimal("50000.00"),
                LocalDateTime.now().withHour(1));
        EvaluationResult highScore = new EvaluationResult(
                List.of(new RuleResult("AMOUNT_THRESHOLD_RULE", true, 60, "matched")), 60);

        java.util.UUID managedId = java.util.UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        TransactionEntity managedEntity = new TransactionEntity();
        managedEntity.setId(managedId);
        managedEntity.setAccountId("ACC-005");
        managedEntity.setAmount(new BigDecimal("50000.00"));
        managedEntity.setCurrency("ZAR");
        managedEntity.setMerchantName("Test Merchant");
        managedEntity.setMerchantCategory("RETAIL");
        managedEntity.setChannel("ONLINE");
        managedEntity.setCountryCode("ZA");
        managedEntity.setTransactionTime(request.transactionTime());
        managedEntity.setRiskScore(0);
        managedEntity.setFraudulent(false);

        // First save returns the "managed" entity; second save returns whatever it's passed
        when(transactionRepository.save(any()))
                .thenReturn(managedEntity)                          // initial save → managed proxy
                .thenAnswer(inv -> inv.getArgument(0));             // update save → same object
        when(ruleEngine.evaluate(any())).thenReturn(highScore);
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        service.evaluate(request);

        // The alert must reference the ID from the managed entity, not any other entity
        ArgumentCaptor<FraudAlertEntity> alertCaptor = ArgumentCaptor.forClass(FraudAlertEntity.class);
        verify(alertRepository).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getTransactionId()).isEqualTo(managedId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TransactionRequest buildRequest(String accountId, BigDecimal amount, LocalDateTime time) {
        return new TransactionRequest(accountId, amount, "ZAR",
                "Test Merchant", "RETAIL", "ONLINE", "ZA", time);
    }
}
