package za.co.fraudruleengine.unit.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import za.co.fraudruleengine.model.RuleName;
import za.co.fraudruleengine.rule.impl.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link RuleName} enum.
 *
 * <p>Verifies that each enum constant's {@link Enum#name()} value exactly matches the
 * corresponding {@code RULE_NAME} constant on each rule implementation class.  This is the
 * primary guarantee the enum provides: a single, typo-proof source of truth for every rule
 * identifier used as a join key against the {@code rule_configs} database table.
 *
 * <p>If a rule constant is renamed or a new rule is added without updating the enum, these
 * tests will fail at compile time (if the constant is removed from the rule class) or at
 * test time (if the string value diverges).
 */
@DisplayName("RuleName enum — each value matches its rule class RULE_NAME constant")
class RuleNameTest {

    @Test
    @DisplayName("VELOCITY_RULE matches VelocityRule.RULE_NAME")
    void velocityRuleNameMatchesConstant() {
        assertThat(RuleName.VELOCITY_RULE.name()).isEqualTo(VelocityRule.RULE_NAME);
    }

    @Test
    @DisplayName("AMOUNT_THRESHOLD_RULE matches AmountThresholdRule.RULE_NAME")
    void amountThresholdRuleNameMatchesConstant() {
        assertThat(RuleName.AMOUNT_THRESHOLD_RULE.name()).isEqualTo(AmountThresholdRule.RULE_NAME);
    }

    @Test
    @DisplayName("OFF_HOURS_RULE matches OffHoursRule.RULE_NAME")
    void offHoursRuleNameMatchesConstant() {
        assertThat(RuleName.OFF_HOURS_RULE.name()).isEqualTo(OffHoursRule.RULE_NAME);
    }

    @Test
    @DisplayName("DUPLICATE_TRANSACTION_RULE matches DuplicateTransactionRule.RULE_NAME")
    void duplicateTransactionRuleNameMatchesConstant() {
        assertThat(RuleName.DUPLICATE_TRANSACTION_RULE.name()).isEqualTo(DuplicateTransactionRule.RULE_NAME);
    }

    @Test
    @DisplayName("ROUND_NUMBER_AMOUNT_RULE matches RoundNumberAmountRule.RULE_NAME")
    void roundNumberAmountRuleNameMatchesConstant() {
        assertThat(RuleName.ROUND_NUMBER_AMOUNT_RULE.name()).isEqualTo(RoundNumberAmountRule.RULE_NAME);
    }

    @Test
    @DisplayName("NIGHT_TIME_ATM_RULE matches NightTimeAtmWithdrawalRule.RULE_NAME")
    void nightTimeAtmRuleNameMatchesConstant() {
        assertThat(RuleName.NIGHT_TIME_ATM_RULE.name()).isEqualTo(NightTimeAtmWithdrawalRule.RULE_NAME);
    }

    @Test
    @DisplayName("COUNTRY_MISMATCH_RULE matches CountryMismatchRule.RULE_NAME")
    void countryMismatchRuleNameMatchesConstant() {
        assertThat(RuleName.COUNTRY_MISMATCH_RULE.name()).isEqualTo(CountryMismatchRule.RULE_NAME);
    }

    @Test
    @DisplayName("UNUSUAL_MERCHANT_CATEGORY_RULE matches UnusualMerchantCategoryRule.RULE_NAME")
    void unusualMerchantCategoryRuleNameMatchesConstant() {
        assertThat(RuleName.UNUSUAL_MERCHANT_CATEGORY_RULE.name()).isEqualTo(UnusualMerchantCategoryRule.RULE_NAME);
    }

    @Test
    @DisplayName("Exactly 8 rule names are registered — add test if a new rule is introduced")
    void enumHasExactlyEightValues() {
        assertThat(RuleName.values()).hasSize(8);
    }
}
