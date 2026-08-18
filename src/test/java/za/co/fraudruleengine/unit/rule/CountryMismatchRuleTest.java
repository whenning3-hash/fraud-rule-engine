package za.co.fraudruleengine.unit.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;
import za.co.fraudruleengine.rule.TransactionHistoryPort;
import za.co.fraudruleengine.rule.impl.CountryMismatchRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CountryMismatchRule}.
 *
 * <p>Verifies the impossible-travel detection logic using a mocked
 * {@link TransactionHistoryPort} to isolate the rule from the database layer. Tests cover:
 * <ul>
 *   <li>Mismatch detected when current country differs from recent history.</li>
 *   <li>No mismatch when country is consistent with history.</li>
 *   <li>Skip when account has no prior history in the window.</li>
 *   <li>Skip when the current transaction has no country code.</li>
 *   <li>Correct delegation to {@link TransactionHistoryPort#getRecentCountries}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CountryMismatchRuleTest {

    /** Mocked domain port — isolates the rule from JPA/database. */
    @Mock
    private TransactionHistoryPort transactionHistoryPort;

    private CountryMismatchRule rule;

    /** Standard parameters matching the seeded rule config (V5 migration). */
    private RuleParameters params;

    @BeforeEach
    void setUp() {
        rule = new CountryMismatchRule(transactionHistoryPort);
        params = new RuleParameters(50, Map.of("windowHours", "24"));
    }

    // ---------------------------------------------------------------------------
    // Matching tests — mismatch detected
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Current country GBR, recent history shows ZAF — impossible travel, rule fires")
    void shouldMatchWhenCurrentCountryDiffersFromRecentHistory() {
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransaction(txId, "GBR");

        // History shows ZAF — GBR vs ZAF is a mismatch
        when(transactionHistoryPort.getRecentCountries(eq("ACC-001"), eq(24), eq(txId)))
                .thenReturn(Set.of("ZAF"));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(50);
        assertThat(result.description()).contains("mismatch detected");
    }

    @Test
    @DisplayName("Multiple countries in history, one matches and one does not — rule fires on any mismatch")
    void shouldMatchWhenAnyHistoricalCountryDiffers() {
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransaction(txId, "ZAF");

        // History shows mixed countries — ZAF matches but GBR does not
        when(transactionHistoryPort.getRecentCountries(any(), anyInt(), any()))
                .thenReturn(Set.of("ZAF", "GBR"));

        RuleResult result = rule.evaluate(tx, params);

        // Presence of GBR in history alongside current ZAF is a mismatch
        assertThat(result.matched()).isTrue();
    }

    // ---------------------------------------------------------------------------
    // Non-matching tests — no mismatch
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Current country ZAF, history also shows only ZAF — consistent, rule does not fire")
    void shouldNotMatchWhenCountryIsConsistentWithHistory() {
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransaction(txId, "ZAF");

        // History only shows ZAF — same as current transaction
        when(transactionHistoryPort.getRecentCountries(any(), anyInt(), any()))
                .thenReturn(Set.of("ZAF"));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    @DisplayName("No prior transactions in the window — cannot determine mismatch, rule skipped")
    void shouldNotMatchWhenNoPriorHistoryInWindow() {
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransaction(txId, "ZAF");

        // Empty set: no prior transactions exist in the window
        when(transactionHistoryPort.getRecentCountries(any(), anyInt(), any()))
                .thenReturn(Set.of());

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.description()).contains("skipped");
    }

    // ---------------------------------------------------------------------------
    // Edge cases — missing data
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Transaction has null country code — cannot evaluate, rule skipped without calling port")
    void shouldSkipWhenCurrentTransactionHasNullCountryCode() {
        Transaction tx = buildTransactionNoCountry(UUID.randomUUID());

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.description()).contains("skipped");
        // The port should NOT be called when country is null — avoid unnecessary DB query
        verify(transactionHistoryPort, org.mockito.Mockito.never())
                .getRecentCountries(any(), anyInt(), any());
    }

    @Test
    @DisplayName("Transaction has blank country code — treated same as null, rule skipped")
    void shouldSkipWhenCurrentTransactionHasBlankCountryCode() {
        Transaction tx = new Transaction(UUID.randomUUID(), "ACC-001",
                new BigDecimal("2000.00"), "ZAR", "Test Merchant", "RETAIL",
                "ONLINE", "   ", LocalDateTime.now());

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.description()).contains("skipped");
    }

    // ---------------------------------------------------------------------------
    // Default parameters
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("No windowHours configured — defaults to 24 hours")
    void shouldUseDefaultWindowHoursWhenNotConfigured() {
        RuleParameters emptyParams = new RuleParameters(50, Map.of());
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransaction(txId, "GBR");

        when(transactionHistoryPort.getRecentCountries(any(), anyInt(), any()))
                .thenReturn(Set.of("ZAF"));

        RuleResult result = rule.evaluate(tx, emptyParams);

        // Default window hours (24) used — rule should still fire
        assertThat(result.matched()).isTrue();
        // Verify the port was called with the default windowHours=24
        verify(transactionHistoryPort).getRecentCountries(eq("ACC-001"), eq(24), eq(txId));
    }

    // ---------------------------------------------------------------------------
    // Rule identity
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("getRuleName returns COUNTRY_MISMATCH_RULE")
    void shouldReturnCorrectRuleName() {
        assertThat(rule.getRuleName()).isEqualTo(CountryMismatchRule.RULE_NAME);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private Transaction buildTransaction(UUID id, String countryCode) {
        return new Transaction(id, "ACC-001", new BigDecimal("2000.00"), "ZAR",
                "Test Merchant", "RETAIL", "ONLINE", countryCode, LocalDateTime.now());
    }

    private Transaction buildTransactionNoCountry(UUID id) {
        return new Transaction(id, "ACC-001", new BigDecimal("2000.00"), "ZAR",
                "Test Merchant", "RETAIL", "ONLINE", null, LocalDateTime.now());
    }
}
