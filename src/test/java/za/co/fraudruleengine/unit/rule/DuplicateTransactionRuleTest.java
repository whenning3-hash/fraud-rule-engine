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
import za.co.fraudruleengine.domain.rule.impl.DuplicateTransactionRule;
import za.co.fraudruleengine.infrastructure.redis.VelocityStore;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DuplicateTransactionRuleTest {

    @Mock
    private VelocityStore velocityStore;

    @InjectMocks
    private DuplicateTransactionRule rule;

    private RuleParameters params;

    @BeforeEach
    void setUp() {
        params = new RuleParameters(35, Map.of("windowSeconds", "60"));
    }

    @Test
    void shouldMatchWhenDuplicateDetected() {
        when(velocityStore.isDuplicate(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(true);
        RuleResult result = rule.evaluate(buildTransaction(), params);
        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(35);
    }

    @Test
    void shouldNotMatchForFirstTransaction() {
        when(velocityStore.isDuplicate(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(false);
        RuleResult result = rule.evaluate(buildTransaction(), params);
        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    private Transaction buildTransaction() {
        return new Transaction(UUID.randomUUID(), "ACC-001", new BigDecimal("200.00"), "ZAR",
                "Pick n Pay", "GROCERY", "POS", "ZA", LocalDateTime.now());
    }
}
