package za.co.fraudruleengine.unit.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import za.co.fraudruleengine.model.ChannelType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChannelType} and its {@link ChannelType#fromString(String)} factory.
 *
 * <p>The factory is the primary contract: rules receive raw channel strings from the domain
 * model and use {@code fromString()} to convert them to a type-safe enum value before
 * performing set-membership checks.  Importantly, it must:
 * <ul>
 *   <li>Accept uppercase and lowercase input without throwing.</li>
 *   <li>Return {@code null} (not throw) for unrecognised or blank values so that rules can
 *       treat unknown channels as a non-match rather than crashing.</li>
 * </ul>
 */
@DisplayName("ChannelType.fromString() factory")
class ChannelTypeTest {

    // ── happy path — canonical uppercase values ──────────────────────────────

    @Test
    @DisplayName("\"ATM\" resolves to ChannelType.ATM")
    void fromStringUppercaseAtm() {
        assertThat(ChannelType.fromString("ATM")).isEqualTo(ChannelType.ATM);
    }

    @Test
    @DisplayName("\"POS\" resolves to ChannelType.POS")
    void fromStringUppercasePos() {
        assertThat(ChannelType.fromString("POS")).isEqualTo(ChannelType.POS);
    }

    @Test
    @DisplayName("\"ONLINE\" resolves to ChannelType.ONLINE")
    void fromStringUppercaseOnline() {
        assertThat(ChannelType.fromString("ONLINE")).isEqualTo(ChannelType.ONLINE);
    }

    @Test
    @DisplayName("\"MOBILE\" resolves to ChannelType.MOBILE")
    void fromStringUppercaseMobile() {
        assertThat(ChannelType.fromString("MOBILE")).isEqualTo(ChannelType.MOBILE);
    }

    // ── case-insensitivity ───────────────────────────────────────────────────

    @Test
    @DisplayName("\"atm\" (lowercase) resolves to ChannelType.ATM — case-insensitive")
    void fromStringLowercaseAtm() {
        assertThat(ChannelType.fromString("atm")).isEqualTo(ChannelType.ATM);
    }

    @Test
    @DisplayName("\"Pos\" (mixed case) resolves to ChannelType.POS — case-insensitive")
    void fromStringMixedCasePos() {
        assertThat(ChannelType.fromString("Pos")).isEqualTo(ChannelType.POS);
    }

    @Test
    @DisplayName("\"  ATM  \" (padded with spaces) resolves to ChannelType.ATM — trimmed")
    void fromStringTrimmedAtm() {
        assertThat(ChannelType.fromString("  ATM  ")).isEqualTo(ChannelType.ATM);
    }

    // ── null/blank/unknown — must return null, not throw ─────────────────────

    @Test
    @DisplayName("null input returns null without throwing")
    void fromStringNullReturnsNull() {
        assertThat(ChannelType.fromString(null)).isNull();
    }

    @Test
    @DisplayName("blank string returns null without throwing")
    void fromStringBlankReturnsNull() {
        assertThat(ChannelType.fromString("   ")).isNull();
    }

    @Test
    @DisplayName("empty string returns null without throwing")
    void fromStringEmptyReturnsNull() {
        assertThat(ChannelType.fromString("")).isNull();
    }

    @Test
    @DisplayName("unrecognised value returns null without throwing")
    void fromStringUnknownReturnsNull() {
        assertThat(ChannelType.fromString("WIRE_TRANSFER")).isNull();
    }
}
