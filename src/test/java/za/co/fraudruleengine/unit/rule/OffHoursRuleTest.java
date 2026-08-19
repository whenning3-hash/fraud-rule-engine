package za.co.fraudruleengine.unit.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;
import za.co.fraudruleengine.rule.impl.OffHoursRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OffHoursRuleTest {

    private OffHoursRule rule;
    private RuleParameters params;

    @BeforeEach
    void setUp() {
        rule = new OffHoursRule();
        params = new RuleParameters(20, Map.of("startHour", "0", "endHour", "5"));
    }

    @Test
    void shouldMatchForTransactionAtMidnight() {
        Transaction tx = buildTransactionAtHour(2);
        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(20);
    }

    @Test
    void shouldNotMatchForDaytimeTransaction() {
        Transaction tx = buildTransactionAtHour(14);
        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    void shouldNotMatchAtUpperBoundaryHour() {
        // endHour is exclusive: hour == 5 should NOT match [0, 5)
        Transaction tx = buildTransactionAtHour(5);
        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isFalse();
    }

    @Test
    void shouldMatchAtLowerBoundaryHour() {
        // startHour is inclusive: hour == 0 SHOULD match [0, 5)
        Transaction tx = buildTransactionAtHour(0);
        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isTrue();
    }

    @Test
    void shouldUseDefaultParametersWhenParamsMissing() {
        // Empty params → startHour defaults to 0, endHour defaults to 5
        RuleParameters defaultParams = new RuleParameters(20, Map.of());
        Transaction tx = buildTransactionAtHour(3);
        RuleResult result = rule.evaluate(tx, defaultParams);
        assertThat(result.matched()).isTrue();
    }

    @Test
    void shouldReturnCorrectRuleName() {
        assertThat(rule.getRuleName()).isEqualTo(OffHoursRule.RULE_NAME);
    }

    @Test
    void ruleNameShouldMatchExpectedString() {
        // Explicitly verify the string value so DB join key cannot drift without a test failure
        assertThat(OffHoursRule.RULE_NAME).isEqualTo("OFF_HOURS_RULE");
    }

    private Transaction buildTransactionAtHour(int hour) {
        return new Transaction(UUID.randomUUID(), "ACC-001", new BigDecimal("500.00"), "ZAR",
                "Test Merchant", "RETAIL", "POS", "ZA",
                LocalDateTime.now().withHour(hour).withMinute(0));
    }
}
