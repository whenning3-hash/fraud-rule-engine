package za.co.fraudruleengine.unit.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;
import za.co.fraudruleengine.rule.impl.AmountThresholdRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AmountThresholdRuleTest {

    private AmountThresholdRule rule;
    private RuleParameters params;

    @BeforeEach
    void setUp() {
        rule = new AmountThresholdRule();
        params = new RuleParameters(40, Map.of("maxAmount", "5000.00"));
    }

    @Test
    void shouldMatchWhenAmountExceedsThreshold() {
        Transaction tx = buildTransaction(new BigDecimal("6000.00"));
        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(40);
    }

    @Test
    void shouldNotMatchWhenAmountBelowThreshold() {
        Transaction tx = buildTransaction(new BigDecimal("1000.00"));
        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    void shouldNotMatchWhenAmountEqualsThreshold() {
        Transaction tx = buildTransaction(new BigDecimal("5000.00"));
        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isFalse();
    }

    @Test
    void shouldUseDefaultThresholdWhenParamMissing() {
        RuleParameters defaultParams = new RuleParameters(40, Map.of());
        Transaction tx = buildTransaction(new BigDecimal("10001.00"));
        RuleResult result = rule.evaluate(tx, defaultParams);
        assertThat(result.matched()).isTrue();
    }

    private Transaction buildTransaction(BigDecimal amount) {
        return new Transaction(UUID.randomUUID(), "ACC-001", amount, "ZAR",
                "Test Merchant", "RETAIL", "POS", "ZA", LocalDateTime.now());
    }
}
