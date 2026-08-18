package za.co.fraudruleengine.unit.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.RuleParameters;
import za.co.fraudruleengine.domain.rule.RuleResult;
import za.co.fraudruleengine.domain.rule.impl.VelocityRule;
import za.co.fraudruleengine.infrastructure.redis.VelocityStore;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VelocityRuleTest {

    @Mock
    private VelocityStore velocityStore;

    @InjectMocks
    private VelocityRule rule;

    private RuleParameters params;

    @BeforeEach
    void setUp() {
        params = new RuleParameters(30, Map.of("maxTransactions", "3", "windowMinutes", "5"));
    }

    @Test
    void shouldMatchWhenVelocityExceedsThreshold() {
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(4L);
        Transaction tx = buildTransaction();
        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(30);
    }

    @Test
    void shouldNotMatchWhenVelocityBelowThreshold() {
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(2L);
        Transaction tx = buildTransaction();
        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    private Transaction buildTransaction() {
        return new Transaction(UUID.randomUUID(), "ACC-001", new BigDecimal("100.00"), "ZAR",
                "Merchant", "RETAIL", "ONLINE", "ZA", LocalDateTime.now());
    }
}
