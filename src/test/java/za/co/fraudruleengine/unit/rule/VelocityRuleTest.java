package za.co.fraudruleengine.unit.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.RuleParameters;
import za.co.fraudruleengine.domain.rule.RuleResult;
import za.co.fraudruleengine.domain.rule.impl.VelocityRule;
import za.co.fraudruleengine.infrastructure.redis.VelocityStorePort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VelocityRule}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Positive: transaction count exceeds configured threshold → rule fires.</li>
 *   <li>Negative: count below threshold → rule silent.</li>
 *   <li>Boundary: count exactly equals threshold → fires (inclusive {@code >=}).</li>
 *   <li>Negative boundary: count one below threshold → does not fire.</li>
 *   <li>Default params: empty param map → defaults to maxTransactions=5, windowMinutes=10.</li>
 *   <li>Argument verification: confirms the correct windowMinutes value is forwarded to
 *       the store — a hardcoded window would otherwise go undetected.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class VelocityRuleTest {

    @Mock
    private VelocityStorePort velocityStore;

    @InjectMocks
    private VelocityRule rule;

    private RuleParameters params;

    @BeforeEach
    void setUp() {
        params = new RuleParameters(30, Map.of("maxTransactions", "3", "windowMinutes", "5"));
    }

    // ── positive ───────────────────────────────────────────────────────────────

    @Test
    void shouldMatchWhenVelocityExceedsThreshold() {
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(4L);

        RuleResult result = rule.evaluate(buildTransaction(), params);

        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(30);
    }

    @Test
    void shouldMatchWhenVelocityEqualsThreshold() {
        // count == maxTransactions → fires; the comparison is >= (inclusive lower bound)
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(3L);

        RuleResult result = rule.evaluate(buildTransaction(), params);

        assertThat(result.matched()).isTrue();
    }

    // ── negative ───────────────────────────────────────────────────────────────

    @Test
    void shouldNotMatchWhenVelocityBelowThreshold() {
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(2L);

        RuleResult result = rule.evaluate(buildTransaction(), params);

        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    void shouldNotMatchWhenCountIsOneBeforeThreshold() {
        // maxTransactions = 3 → count 2 must NOT fire
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(2L);

        RuleResult result = rule.evaluate(buildTransaction(), params);

        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    void shouldNotMatchForBrandNewAccountWithZeroTransactions() {
        // A brand-new account has no prior history; count = 0 must not trigger velocity
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(0L);

        RuleResult result = rule.evaluate(buildTransaction(), params);

        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    // ── default parameters ─────────────────────────────────────────────────────

    @Test
    void shouldUseDefaultParametersWhenParamsMissing() {
        // Empty params → maxTransactions defaults to 5, windowMinutes defaults to 10.
        // Count 6 > default 5 → rule fires.
        RuleParameters defaultParams = new RuleParameters(30, Map.of());
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(6L);

        RuleResult result = rule.evaluate(buildTransaction(), defaultParams);

        assertThat(result.matched()).isTrue();
    }

    @Test
    void shouldNotMatchBelowDefaultThresholdWhenParamsMissing() {
        // Default maxTransactions = 5; count 4 must not fire
        RuleParameters defaultParams = new RuleParameters(30, Map.of());
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(4L);

        RuleResult result = rule.evaluate(buildTransaction(), defaultParams);

        assertThat(result.matched()).isFalse();
    }

    // ── argument verification ──────────────────────────────────────────────────

    @Test
    void shouldPassConfiguredWindowMinutesToStore() {
        // Verifies that the windowMinutes from params is forwarded to the store.
        // A regression that hardcodes a different window value would be caught here.
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(1L);

        rule.evaluate(buildTransaction(), params);

        ArgumentCaptor<Integer> windowCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(velocityStore).getTransactionCount(anyString(), windowCaptor.capture());
        assertThat(windowCaptor.getValue())
                .as("windowMinutes from params (5) must be forwarded to the velocity store")
                .isEqualTo(5);
    }

    @Test
    void shouldPassAccountIdToStore() {
        when(velocityStore.getTransactionCount(anyString(), anyInt())).thenReturn(1L);
        Transaction tx = buildTransaction();

        rule.evaluate(tx, params);

        ArgumentCaptor<String> accountCaptor = ArgumentCaptor.forClass(String.class);
        verify(velocityStore).getTransactionCount(accountCaptor.capture(), anyInt());
        assertThat(accountCaptor.getValue()).isEqualTo(tx.accountId());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Transaction buildTransaction() {
        return new Transaction(UUID.randomUUID(), "ACC-001", new BigDecimal("100.00"), "ZAR",
                "Merchant", "RETAIL", "ONLINE", "ZAF", LocalDateTime.now());
    }
}
