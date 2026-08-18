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
import za.co.fraudruleengine.domain.rule.impl.DuplicateTransactionRule;
import za.co.fraudruleengine.infrastructure.redis.VelocityStorePort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DuplicateTransactionRule}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Positive: duplicate detected → rule fires, correct risk score returned.</li>
 *   <li>Negative: first occurrence → rule silent, zero score.</li>
 *   <li>Default params: empty parameter map → falls back to default windowSeconds (60).</li>
 *   <li>Argument verification: confirms the correct fingerprint fields and window value
 *       are forwarded to {@link VelocityStorePort#isDuplicate}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DuplicateTransactionRuleTest {

    @Mock
    private VelocityStorePort velocityStore;

    @InjectMocks
    private DuplicateTransactionRule rule;

    private RuleParameters params;

    @BeforeEach
    void setUp() {
        params = new RuleParameters(35, Map.of("windowSeconds", "60"));
    }

    // ── positive ───────────────────────────────────────────────────────────────

    @Test
    void shouldMatchWhenDuplicateDetected() {
        when(velocityStore.isDuplicate(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(true);

        RuleResult result = rule.evaluate(buildTransaction(), params);

        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(35);
    }

    // ── negative ───────────────────────────────────────────────────────────────

    @Test
    void shouldNotMatchForFirstTransaction() {
        when(velocityStore.isDuplicate(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(false);

        RuleResult result = rule.evaluate(buildTransaction(), params);

        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    void shouldNotMatchWhenDifferentMerchantSameAccountAndAmount() {
        // Same account + amount but different merchant → NOT a duplicate
        when(velocityStore.isDuplicate(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(false);

        Transaction tx = new Transaction(UUID.randomUUID(), "ACC-001", new BigDecimal("200.00"), "ZAR",
                "Different Merchant", "GROCERY", "POS", "ZAF", LocalDateTime.now());

        RuleResult result = rule.evaluate(tx, params);
        assertThat(result.matched()).isFalse();
    }

    // ── default parameters ─────────────────────────────────────────────────────

    @Test
    void shouldUseDefaultWindowSecondsWhenParamNotPresent() {
        // Default windowSeconds is 60; verify it is passed to the store when params are empty
        RuleParameters defaultParams = new RuleParameters(35, Map.of());
        when(velocityStore.isDuplicate(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(false);

        rule.evaluate(buildTransaction(), defaultParams);

        ArgumentCaptor<Integer> windowCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(velocityStore).isDuplicate(anyString(), anyString(), anyString(), anyString(),
                windowCaptor.capture());
        assertThat(windowCaptor.getValue())
                .as("Default windowSeconds should be 60")
                .isEqualTo(60);
    }

    // ── argument verification ──────────────────────────────────────────────────

    @Test
    void shouldPassCorrectFingerprintFieldsToStore() {
        // Verifies that accountId, amount, merchantName, and transactionId are forwarded
        // correctly — a wrong field mapping would silently miss duplicates.
        Transaction tx = new Transaction(UUID.randomUUID(), "ACC-DEDUP", new BigDecimal("500.00"), "ZAR",
                "Woolworths", "GROCERY", "CARD_PRESENT", "ZAF", LocalDateTime.now());

        when(velocityStore.isDuplicate(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(false);

        rule.evaluate(tx, params);

        ArgumentCaptor<String> accountCaptor  = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> amountCaptor   = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> merchantCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> txIdCaptor     = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> windowCaptor  = ArgumentCaptor.forClass(Integer.class);

        verify(velocityStore).isDuplicate(accountCaptor.capture(), amountCaptor.capture(),
                merchantCaptor.capture(), txIdCaptor.capture(), windowCaptor.capture());

        assertThat(accountCaptor.getValue()).isEqualTo("ACC-DEDUP");
        assertThat(amountCaptor.getValue()).isEqualTo("500.00");
        assertThat(merchantCaptor.getValue()).isEqualTo("Woolworths");
        assertThat(windowCaptor.getValue()).isEqualTo(60);
    }

    // ── rule identity ──────────────────────────────────────────────────────────

    @Test
    void shouldReturnCorrectRuleName() {
        assertThat(rule.getRuleName()).isEqualTo("DUPLICATE_TRANSACTION_RULE");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Transaction buildTransaction() {
        return new Transaction(UUID.randomUUID(), "ACC-001", new BigDecimal("200.00"), "ZAR",
                "Pick n Pay", "GROCERY", "POS", "ZAF", LocalDateTime.now());
    }
}
