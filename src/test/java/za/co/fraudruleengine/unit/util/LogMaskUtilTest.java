package za.co.fraudruleengine.unit.util;

import org.junit.jupiter.api.Test;
import za.co.fraudruleengine.util.LogMaskUtil;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogMaskUtil}.
 *
 * <p>Masking behaviour is a security control, not just a formatting concern — any regression that
 * causes raw account numbers or amounts to appear in logs is a POPIA / PCI-DSS violation. These
 * tests pin the exact masking contract so that changes are intentional and visible in review.
 */
class LogMaskUtilTest {

    // ── maskAccount ────────────────────────────────────────────────────────────

    @Test
    void maskAccount_shortId_showsPrefixAndMask() {
        // 7 chars: "ACC-001" → keep first 3 + *** + last 3
        assertThat(LogMaskUtil.maskAccount("ACC-001")).isEqualTo("ACC***001");
    }

    @Test
    void maskAccount_longAccountNumber_masksMiddle() {
        // 10 chars: "1234567890" → first 3 + *** + last 3
        assertThat(LogMaskUtil.maskAccount("1234567890")).isEqualTo("123***890");
    }

    @Test
    void maskAccount_veryShortId_showsPrefixOnly() {
        // 4 chars or fewer: only show up to first 3 + ***
        assertThat(LogMaskUtil.maskAccount("AB12")).isEqualTo("AB1***");
    }

    @Test
    void maskAccount_threeCharId_showsAllPlusMarker() {
        assertThat(LogMaskUtil.maskAccount("ABC")).isEqualTo("ABC***");
    }

    @Test
    void maskAccount_null_returnsNullPlaceholder() {
        assertThat(LogMaskUtil.maskAccount(null)).isEqualTo("null");
    }

    @Test
    void maskAccount_doesNotContainRawMiddleDigits() {
        // Ensure no raw account middle segment leaks through
        String masked = LogMaskUtil.maskAccount("9876543210");
        assertThat(masked).doesNotContain("654");
    }

    // ── maskAmount ─────────────────────────────────────────────────────────────

    @Test
    void maskAmount_anyValue_returnsMaskedPlaceholder() {
        assertThat(LogMaskUtil.maskAmount("50000.00")).isEqualTo(LogMaskUtil.MASKED);
        assertThat(LogMaskUtil.maskAmount("1.00")).isEqualTo(LogMaskUtil.MASKED);
        assertThat(LogMaskUtil.maskAmount(null)).isEqualTo(LogMaskUtil.MASKED);
    }

    @Test
    void maskAmount_doesNotRevealRawValue() {
        assertThat(LogMaskUtil.maskAmount("99999.99")).doesNotContain("9");
    }
}
