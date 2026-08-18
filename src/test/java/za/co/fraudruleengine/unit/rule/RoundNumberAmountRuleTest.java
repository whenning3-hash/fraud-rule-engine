package za.co.fraudruleengine.unit.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;
import za.co.fraudruleengine.rule.impl.RoundNumberAmountRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RoundNumberAmountRule}.
 *
 * <p>Covers the core structuring-detection logic:
 * <ul>
 *   <li>Round multiples above the minimum threshold are flagged.</li>
 *   <li>Non-round amounts above the threshold are not flagged.</li>
 *   <li>Round amounts below the minimum threshold are not flagged (avoids everyday round payments).</li>
 *   <li>Boundary conditions: exactly at threshold, fractional remainder.</li>
 *   <li>Default parameters are applied when not configured.</li>
 * </ul>
 *
 * <p>All tests use the default divisor of R1 000 and minimum amount of R5 000 unless
 * overridden by the individual test case.
 */
class RoundNumberAmountRuleTest {

    private RoundNumberAmountRule rule;

    @BeforeEach
    void setUp() {
        rule = new RoundNumberAmountRule();
    }

    // ---------------------------------------------------------------------------
    // Matching tests — rule SHOULD fire
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("R10 000 above minimum — matches as round structuring amount")
    void shouldMatchWhenAmountIsRoundAndAboveMinimum() {
        RuleParameters params = buildParams(25, "1000", "5000.00");
        Transaction tx = buildTransaction(new BigDecimal("10000.00"));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(25);
        assertThat(result.description()).contains("structuring indicator");
    }

    @Test
    @DisplayName("Exact minimum amount R5 000 — matches (boundary inclusive)")
    void shouldMatchWhenAmountEqualsMinimum() {
        RuleParameters params = buildParams(25, "1000", "5000.00");
        Transaction tx = buildTransaction(new BigDecimal("5000.00"));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(25);
    }

    @Test
    @DisplayName("Large round amount R50 000 — matches as high-value structuring")
    void shouldMatchLargeRoundAmount() {
        RuleParameters params = buildParams(25, "1000", "5000.00");
        Transaction tx = buildTransaction(new BigDecimal("50000.00"));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isTrue();
    }

    // ---------------------------------------------------------------------------
    // Non-matching tests — rule SHOULD NOT fire
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("R5 001 — above minimum but not round — does not match")
    void shouldNotMatchWhenAmountIsNotRound() {
        RuleParameters params = buildParams(25, "1000", "5000.00");
        Transaction tx = buildTransaction(new BigDecimal("5001.00"));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    @DisplayName("R1 000 below minimum — round but below threshold, does not match")
    void shouldNotMatchRoundAmountBelowMinimum() {
        RuleParameters params = buildParams(25, "1000", "5000.00");
        Transaction tx = buildTransaction(new BigDecimal("1000.00"));

        RuleResult result = rule.evaluate(tx, params);

        // R1 000 is a common everyday payment — must not be flagged
        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    @DisplayName("R4 999.99 — just below minimum — does not match")
    void shouldNotMatchJustBelowMinimum() {
        RuleParameters params = buildParams(25, "1000", "5000.00");
        Transaction tx = buildTransaction(new BigDecimal("4999.99"));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
    }

    @Test
    @DisplayName("R9 999.99 — above minimum but irregular — does not match")
    void shouldNotMatchIrregularAmountAboveMinimum() {
        RuleParameters params = buildParams(25, "1000", "5000.00");
        Transaction tx = buildTransaction(new BigDecimal("9999.99"));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
    }

    // ---------------------------------------------------------------------------
    // Default parameter fallback
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("No parameters configured — uses defaults (divisor=1000, minAmount=5000)")
    void shouldUseDefaultsWhenParametersNotConfigured() {
        // Empty parameters — rule must fall back to divisor=1000, minAmount=5000.00
        RuleParameters emptyParams = new RuleParameters(25, Map.of());
        Transaction tx = buildTransaction(new BigDecimal("7000.00"));

        RuleResult result = rule.evaluate(tx, emptyParams);

        assertThat(result.matched()).isTrue();
    }

    // ---------------------------------------------------------------------------
    // Rule identity
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("getRuleName returns ROUND_NUMBER_AMOUNT_RULE")
    void shouldReturnCorrectRuleName() {
        assertThat(rule.getRuleName()).isEqualTo(RoundNumberAmountRule.RULE_NAME);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private RuleParameters buildParams(int weight, String divisor, String minAmount) {
        return new RuleParameters(weight, Map.of("divisor", divisor, "minAmount", minAmount));
    }

    private Transaction buildTransaction(BigDecimal amount) {
        return new Transaction(UUID.randomUUID(), "ACC-001", amount, "ZAR",
                "Test Merchant", "RETAIL", "ONLINE", "ZAF", LocalDateTime.now());
    }
}
