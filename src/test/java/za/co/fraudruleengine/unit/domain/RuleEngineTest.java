package za.co.fraudruleengine.unit.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.*;
import za.co.fraudruleengine.infrastructure.persistence.entity.RuleConfigEntity;
import za.co.fraudruleengine.infrastructure.persistence.repository.RuleConfigJpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleEngineTest {

    @Mock
    RuleConfigJpaRepository ruleConfigRepository;

    @Mock
    FraudRule ruleA;

    @Mock
    FraudRule ruleB;

    RuleEngine ruleEngine;

    Transaction transaction;

    @BeforeEach
    void setUp() {
        ruleEngine = new RuleEngine(List.of(ruleA, ruleB), ruleConfigRepository);
        transaction = new Transaction(UUID.randomUUID(), "ACC-001",
                new BigDecimal("1000.00"), "ZAR", "Shop", "RETAIL",
                "POS", "ZA", LocalDateTime.now());
    }

    @Test
    void shouldEvaluateAllEnabledRulesAndAggregateScores() {
        when(ruleA.getRuleName()).thenReturn("RULE_A");
        when(ruleB.getRuleName()).thenReturn("RULE_B");
        when(ruleConfigRepository.findAll()).thenReturn(List.of(
                ruleConfig("RULE_A", true, 30),
                ruleConfig("RULE_B", true, 20)
        ));
        when(ruleA.evaluate(eq(transaction), any())).thenReturn(new RuleResult("RULE_A", true, 30, "matched"));
        when(ruleB.evaluate(eq(transaction), any())).thenReturn(new RuleResult("RULE_B", true, 20, "matched"));

        EvaluationResult result = ruleEngine.evaluate(transaction);

        assertThat(result.totalScore()).isEqualTo(50);
        assertThat(result.matchedRuleNames()).containsExactlyInAnyOrder("RULE_A", "RULE_B");
    }

    @Test
    void shouldSkipDisabledRule() {
        when(ruleA.getRuleName()).thenReturn("RULE_A");
        when(ruleB.getRuleName()).thenReturn("RULE_B");
        when(ruleConfigRepository.findAll()).thenReturn(List.of(
                ruleConfig("RULE_A", true, 30),
                ruleConfig("RULE_B", false, 20)   // disabled
        ));
        when(ruleA.evaluate(eq(transaction), any())).thenReturn(new RuleResult("RULE_A", true, 30, "matched"));

        EvaluationResult result = ruleEngine.evaluate(transaction);

        assertThat(result.totalScore()).isEqualTo(30);
        verify(ruleB, never()).evaluate(any(), any());
    }

    @Test
    void shouldSkipRuleWithNoConfiguration() {
        when(ruleA.getRuleName()).thenReturn("RULE_A");
        when(ruleB.getRuleName()).thenReturn("RULE_B_NO_CONFIG");
        when(ruleConfigRepository.findAll()).thenReturn(List.of(
                ruleConfig("RULE_A", true, 30)
                // RULE_B_NO_CONFIG intentionally omitted
        ));
        when(ruleA.evaluate(eq(transaction), any())).thenReturn(new RuleResult("RULE_A", true, 30, "matched"));

        EvaluationResult result = ruleEngine.evaluate(transaction);

        assertThat(result.totalScore()).isEqualTo(30);
        verify(ruleB, never()).evaluate(any(), any());
    }

    @Test
    void shouldReturnZeroScoreWhenNoRulesMatch() {
        when(ruleA.getRuleName()).thenReturn("RULE_A");
        when(ruleB.getRuleName()).thenReturn("RULE_B");
        when(ruleConfigRepository.findAll()).thenReturn(List.of(
                ruleConfig("RULE_A", true, 30),
                ruleConfig("RULE_B", true, 20)
        ));
        when(ruleA.evaluate(eq(transaction), any())).thenReturn(new RuleResult("RULE_A", false, 0, "no match"));
        when(ruleB.evaluate(eq(transaction), any())).thenReturn(new RuleResult("RULE_B", false, 0, "no match"));

        EvaluationResult result = ruleEngine.evaluate(transaction);

        assertThat(result.totalScore()).isZero();
        assertThat(result.matchedRuleNames()).isEmpty();
    }

    @Test
    void shouldOnlyAddScoreForMatchedRules() {
        when(ruleA.getRuleName()).thenReturn("RULE_A");
        when(ruleB.getRuleName()).thenReturn("RULE_B");
        when(ruleConfigRepository.findAll()).thenReturn(List.of(
                ruleConfig("RULE_A", true, 40),
                ruleConfig("RULE_B", true, 20)
        ));
        when(ruleA.evaluate(eq(transaction), any())).thenReturn(new RuleResult("RULE_A", true, 40, "matched"));
        when(ruleB.evaluate(eq(transaction), any())).thenReturn(new RuleResult("RULE_B", false, 0, "not matched"));

        EvaluationResult result = ruleEngine.evaluate(transaction);

        assertThat(result.totalScore()).isEqualTo(40);
        assertThat(result.matchedRuleNames()).containsExactly("RULE_A");
    }

    @Test
    void evaluationResultIsFraudulentWhenScoreMeetsThreshold() {
        EvaluationResult result = new EvaluationResult(
                List.of(new RuleResult("R", true, 60, "matched")), 60);
        assertThat(result.isFraudulent(60)).isTrue();
    }

    @Test
    void evaluationResultIsNotFraudulentWhenScoreBelowThreshold() {
        EvaluationResult result = new EvaluationResult(
                List.of(new RuleResult("R", true, 59, "matched")), 59);
        assertThat(result.isFraudulent(60)).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private RuleConfigEntity ruleConfig(String name, boolean enabled, int weight) {
        return RuleConfigEntity.builder()
                .id(UUID.randomUUID())
                .ruleName(name)
                .enabled(enabled)
                .riskWeight(weight)
                .parameters(Map.of())
                .build();
    }
}
