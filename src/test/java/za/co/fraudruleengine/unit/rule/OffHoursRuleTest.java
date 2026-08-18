package za.co.fraudruleengine.unit.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.RuleParameters;
import za.co.fraudruleengine.domain.rule.RuleResult;
import za.co.fraudruleengine.domain.rule.impl.OffHoursRule;

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
    void shouldNotMatchAtBoundaryHour() {
        Transaction tx = buildTransactionAtHour(5);
        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isFalse();
    }

    private Transaction buildTransactionAtHour(int hour) {
        return new Transaction(UUID.randomUUID(), "ACC-001", new BigDecimal("500.00"), "ZAR",
                "Test Merchant", "RETAIL", "POS", "ZA",
                LocalDateTime.now().withHour(hour).withMinute(0));
    }
}
