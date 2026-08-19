package za.co.fraudruleengine.unit.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.RuleEvaluationUtils;
import za.co.fraudruleengine.rule.RuleParameters;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RuleEvaluationUtils}.
 *
 * <p>{@code RuleEvaluationUtils.isOffHours()} is shared by both {@link za.co.fraudruleengine.rule.impl.OffHoursRule}
 * and {@link za.co.fraudruleengine.rule.impl.NightTimeAtmWithdrawalRule}.  Testing the utility
 * directly ensures the shared logic is correct independently of the two consumers.
 */
@DisplayName("RuleEvaluationUtils.isOffHours()")
class RuleEvaluationUtilsTest {

    // Default window: 00:00 (inclusive) to 05:00 (exclusive)
    private static final RuleParameters DEFAULT_PARAMS = new RuleParameters(20, Map.of(
            "startHour", "0",
            "endHour",   "5"));

    // ── inside window ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Hour 2 is inside default window [0, 5) — returns true")
    void hourInsideWindowReturnsTrue() {
        assertThat(RuleEvaluationUtils.isOffHours(txAt(2), DEFAULT_PARAMS)).isTrue();
    }

    @Test
    @DisplayName("Hour 0 (window start, inclusive) — returns true")
    void windowStartHourIsInclusive() {
        assertThat(RuleEvaluationUtils.isOffHours(txAt(0), DEFAULT_PARAMS)).isTrue();
    }

    @Test
    @DisplayName("Hour 4 (last hour inside window) — returns true")
    void lastHourInsideWindowReturnsTrue() {
        assertThat(RuleEvaluationUtils.isOffHours(txAt(4), DEFAULT_PARAMS)).isTrue();
    }

    // ── outside window ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Hour 5 (window end, exclusive) — returns false")
    void windowEndHourIsExclusive() {
        assertThat(RuleEvaluationUtils.isOffHours(txAt(5), DEFAULT_PARAMS)).isFalse();
    }

    @Test
    @DisplayName("Hour 14 (business hour) — returns false")
    void businessHourReturnsFalse() {
        assertThat(RuleEvaluationUtils.isOffHours(txAt(14), DEFAULT_PARAMS)).isFalse();
    }

    @Test
    @DisplayName("Hour 23 (last hour of day) — returns false with default window")
    void lastHourOfDayReturnsFalse() {
        assertThat(RuleEvaluationUtils.isOffHours(txAt(23), DEFAULT_PARAMS)).isFalse();
    }

    // ── custom window ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Custom window [22, 24) — hour 23 returns true")
    void customWindowLateNight() {
        RuleParameters lateParams = new RuleParameters(20, Map.of("startHour", "22", "endHour", "24"));
        assertThat(RuleEvaluationUtils.isOffHours(txAt(23), lateParams)).isTrue();
    }

    @Test
    @DisplayName("Default parameter fallback — no startHour/endHour key uses 0/5")
    void defaultParameterFallback() {
        RuleParameters emptyParams = new RuleParameters(20, Map.of());
        assertThat(RuleEvaluationUtils.isOffHours(txAt(3), emptyParams)).isTrue();
        assertThat(RuleEvaluationUtils.isOffHours(txAt(6), emptyParams)).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Transaction txAt(int hour) {
        return new Transaction(UUID.randomUUID(), "ACC-001", new BigDecimal("500.00"), "ZAR",
                "Test Merchant", "RETAIL", "ONLINE", "ZAF",
                LocalDateTime.now().withHour(hour).withMinute(0).withSecond(0).withNano(0));
    }
}
