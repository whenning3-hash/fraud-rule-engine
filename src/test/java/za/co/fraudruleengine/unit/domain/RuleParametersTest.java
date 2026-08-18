package za.co.fraudruleengine.unit.domain;

import org.junit.jupiter.api.Test;
import za.co.fraudruleengine.domain.rule.RuleParameters;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleParametersTest {

    // ── getInt ───────────────────────────────────────────────────────────────

    @Test
    void getInt_validValue_parsesCorrectly() {
        RuleParameters params = new RuleParameters(30, Map.of("maxTransactions", "5"));
        assertThat(params.getInt("maxTransactions", 10)).isEqualTo(5);
    }

    @Test
    void getInt_missingKey_returnsDefault() {
        RuleParameters params = new RuleParameters(30, Map.of());
        assertThat(params.getInt("windowMinutes", 10)).isEqualTo(10);
    }

    @Test
    void getInt_nonNumericValue_returnsDefaultAndDoesNotThrow() {
        RuleParameters params = new RuleParameters(30, Map.of("maxTransactions", "not-a-number"));
        // Must not throw; must fall back silently (with a WARN log)
        assertThat(params.getInt("maxTransactions", 5)).isEqualTo(5);
    }

    @Test
    void getInt_zeroValue_parsesCorrectly() {
        RuleParameters params = new RuleParameters(30, Map.of("startHour", "0"));
        assertThat(params.getInt("startHour", 1)).isZero();
    }

    // ── getBigDecimal ────────────────────────────────────────────────────────

    @Test
    void getBigDecimal_validValue_parsesCorrectly() {
        RuleParameters params = new RuleParameters(40, Map.of("maxAmount", "10000.00"));
        assertThat(params.getBigDecimal("maxAmount", BigDecimal.ZERO))
                .isEqualByComparingTo("10000.00");
    }

    @Test
    void getBigDecimal_missingKey_returnsDefault() {
        RuleParameters params = new RuleParameters(40, Map.of());
        assertThat(params.getBigDecimal("maxAmount", new BigDecimal("5000.00")))
                .isEqualByComparingTo("5000.00");
    }

    @Test
    void getBigDecimal_nonNumericValue_returnsDefaultAndDoesNotThrow() {
        RuleParameters params = new RuleParameters(40, Map.of("maxAmount", "abc"));
        // Must not throw; must fall back silently (with a WARN log)
        assertThat(params.getBigDecimal("maxAmount", new BigDecimal("9999.00")))
                .isEqualByComparingTo("9999.00");
    }

    @Test
    void getBigDecimal_integerStringValue_parsesCorrectly() {
        // Rule configs may store "10000" without a decimal point — still valid BigDecimal
        RuleParameters params = new RuleParameters(40, Map.of("maxAmount", "10000"));
        assertThat(params.getBigDecimal("maxAmount", BigDecimal.ZERO))
                .isEqualByComparingTo("10000");
    }
}
