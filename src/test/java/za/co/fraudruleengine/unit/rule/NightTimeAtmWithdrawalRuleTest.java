package za.co.fraudruleengine.unit.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;
import za.co.fraudruleengine.rule.impl.NightTimeAtmWithdrawalRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NightTimeAtmWithdrawalRule}.
 *
 * <p>Validates the conjunction (AND) logic of the three conditions:
 * <ol>
 *   <li>Transaction hour falls within the configured off-hours window (midnight–05:00).</li>
 *   <li>Channel is a cash-dispensing channel (ATM or POS).</li>
 *   <li>Amount meets or exceeds the configured minimum threshold.</li>
 * </ol>
 *
 * <p>Each condition is also tested in isolation (only one condition fails) to confirm that
 * the rule correctly requires <em>all three</em> simultaneously.
 */
class NightTimeAtmWithdrawalRuleTest {

    /** Default parameters matching the seeded rule config (V5 migration). */
    private static final int RISK_WEIGHT = 45;
    private static final String START_HOUR = "0";
    private static final String END_HOUR = "5";
    private static final String MIN_AMOUNT = "1500.00";

    private NightTimeAtmWithdrawalRule rule;
    private RuleParameters params;

    @BeforeEach
    void setUp() {
        rule = new NightTimeAtmWithdrawalRule();
        params = buildParams(RISK_WEIGHT, START_HOUR, END_HOUR, MIN_AMOUNT);
    }

    // ---------------------------------------------------------------------------
    // Happy path — all three conditions met
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("02:00 ATM R2000 — all three conditions met, rule fires")
    void shouldMatchWhenAllConditionsAreSatisfied() {
        // 02:00 is within the off-hours window, ATM is a cash channel, R2000 > R1500 minimum
        Transaction tx = buildTransaction(new BigDecimal("2000.00"), "ATM", hour(2));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(RISK_WEIGHT);
        assertThat(result.description()).contains("Night-time ATM/POS withdrawal detected");
    }

    @Test
    @DisplayName("03:30 POS R5000 — POS cash-back counts as a cash channel")
    void shouldMatchPosChannelInOffHoursWithHighAmount() {
        Transaction tx = buildTransaction(new BigDecimal("5000.00"), "POS", hour(3));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(RISK_WEIGHT);
    }

    @Test
    @DisplayName("Exactly at window start (00:00) and minimum amount — boundary is inclusive")
    void shouldMatchAtWindowStartAndMinimumAmount() {
        Transaction tx = buildTransaction(new BigDecimal("1500.00"), "ATM", hour(0));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isTrue();
    }

    // ---------------------------------------------------------------------------
    // Each condition failing independently — rule must NOT fire
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("14:00 ATM R2000 — daytime hour, rule does not fire")
    void shouldNotMatchWhenHourIsOutsideOffHoursWindow() {
        // 14:00 is a normal business hour — not off-hours
        Transaction tx = buildTransaction(new BigDecimal("2000.00"), "ATM", hour(14));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    @DisplayName("02:00 ONLINE R2000 — online channel is not a cash channel, rule does not fire")
    void shouldNotMatchWhenChannelIsOnlineNotCash() {
        // ONLINE is not a cash-dispensing channel — card theft pattern requires physical access
        Transaction tx = buildTransaction(new BigDecimal("2000.00"), "ONLINE", hour(2));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    @DisplayName("02:00 ATM R500 — amount below minimum, rule does not fire")
    void shouldNotMatchWhenAmountIsBelowMinimum() {
        // R500 is below the R1500 minimum — small ATM withdrawals are normal
        Transaction tx = buildTransaction(new BigDecimal("500.00"), "ATM", hour(2));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
    }

    @Test
    @DisplayName("05:00 ATM R2000 — window end is exclusive, rule does not fire")
    void shouldNotMatchAtWindowEndHourExclusive() {
        // endHour=5 means 05:00 itself is outside the window (exclusive upper bound)
        Transaction tx = buildTransaction(new BigDecimal("2000.00"), "ATM", hour(5));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
    }

    // ---------------------------------------------------------------------------
    // Default parameter fallback
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("No parameters configured — defaults used (0–5, R1500)")
    void shouldUseDefaultParametersWhenNoneConfigured() {
        RuleParameters emptyParams = new RuleParameters(45, Map.of());
        Transaction tx = buildTransaction(new BigDecimal("2000.00"), "ATM", hour(3));

        RuleResult result = rule.evaluate(tx, emptyParams);

        assertThat(result.matched()).isTrue();
    }

    // ---------------------------------------------------------------------------
    // Channel case-insensitivity
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Channel value 'atm' (lowercase) — matched case-insensitively")
    void shouldMatchLowercaseAtmChannel() {
        Transaction tx = buildTransaction(new BigDecimal("2000.00"), "atm", hour(2));

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isTrue();
    }

    // ---------------------------------------------------------------------------
    // Rule identity
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("getRuleName returns NIGHT_TIME_ATM_RULE")
    void shouldReturnCorrectRuleName() {
        assertThat(rule.getRuleName()).isEqualTo(NightTimeAtmWithdrawalRule.RULE_NAME);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private RuleParameters buildParams(int weight, String startHour, String endHour, String minAmount) {
        return new RuleParameters(weight, Map.of(
                "startHour", startHour,
                "endHour", endHour,
                "minAmount", minAmount));
    }

    private Transaction buildTransaction(BigDecimal amount, String channel, LocalDateTime time) {
        return new Transaction(UUID.randomUUID(), "ACC-001", amount, "ZAR",
                "Test Merchant", "GENERAL", channel, "ZAF", time);
    }

    /** Builds a {@link LocalDateTime} for today at the given hour. */
    private LocalDateTime hour(int h) {
        return LocalDateTime.now().withHour(h).withMinute(0).withSecond(0).withNano(0);
    }
}
