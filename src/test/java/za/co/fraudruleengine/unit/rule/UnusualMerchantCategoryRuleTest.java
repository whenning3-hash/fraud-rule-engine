package za.co.fraudruleengine.unit.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.RuleParameters;
import za.co.fraudruleengine.domain.rule.RuleResult;
import za.co.fraudruleengine.domain.rule.TransactionHistoryPort;
import za.co.fraudruleengine.domain.rule.impl.UnusualMerchantCategoryRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UnusualMerchantCategoryRule}.
 *
 * <p>Verifies the behavioural-deviation detection logic using a mocked
 * {@link TransactionHistoryPort} to isolate the rule from the database layer. Tests cover:
 * <ul>
 *   <li>First-time category usage is flagged (no prior history → rule fires).</li>
 *   <li>Established category usage is not flagged (prior history exists → rule silent).</li>
 *   <li>Transactions with null or blank merchant category are skipped without calling the port.</li>
 *   <li>The port is called with the correct account ID, category, and transaction ID.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UnusualMerchantCategoryRuleTest {

    /** Mocked domain port — isolates the rule from JPA/database. */
    @Mock
    private TransactionHistoryPort transactionHistoryPort;

    private UnusualMerchantCategoryRule rule;
    private RuleParameters params;

    @BeforeEach
    void setUp() {
        rule = new UnusualMerchantCategoryRule(transactionHistoryPort);
        // Risk weight of 15 matches the seeded value in V5 migration
        params = new RuleParameters(15, Map.of());
    }

    // ---------------------------------------------------------------------------
    // Matching tests — first-time category usage
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("FOREX category, no prior history — first-time usage detected, rule fires")
    void shouldMatchWhenCategoryIsUsedForFirstTime() {
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransaction(txId, "FOREX");

        // Port returns false: no prior transaction exists for this category
        when(transactionHistoryPort.hasPreviousCategoryTransaction(
                eq("ACC-001"), eq("FOREX"), eq(txId)))
                .thenReturn(false);

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(15);
        assertThat(result.description()).contains("First-time merchant category");
    }

    @Test
    @DisplayName("GAMBLING category, no prior history — rule fires")
    void shouldMatchFirstTimeGamblingCategory() {
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransaction(txId, "GAMBLING");

        when(transactionHistoryPort.hasPreviousCategoryTransaction(any(), eq("GAMBLING"), any()))
                .thenReturn(false);

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isTrue();
        assertThat(result.riskScore()).isEqualTo(15);
    }

    // ---------------------------------------------------------------------------
    // Non-matching tests — established category usage
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("RETAIL category with existing history — established usage, rule does not fire")
    void shouldNotMatchWhenCategoryHasPriorHistory() {
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransaction(txId, "RETAIL");

        // Port returns true: prior transactions exist for RETAIL on this account
        when(transactionHistoryPort.hasPreviousCategoryTransaction(
                eq("ACC-001"), eq("RETAIL"), eq(txId)))
                .thenReturn(true);

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.riskScore()).isZero();
        assertThat(result.description()).contains("established");
    }

    // ---------------------------------------------------------------------------
    // Edge cases — missing merchant category data
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Transaction has null merchant category — rule skipped without calling port")
    void shouldSkipWhenMerchantCategoryIsNull() {
        Transaction tx = buildTransactionNoCategory(UUID.randomUUID());

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.description()).contains("skipped");
        // Port must NOT be called when there is no category to look up
        verify(transactionHistoryPort, org.mockito.Mockito.never())
                .hasPreviousCategoryTransaction(any(), any(), any());
    }

    @Test
    @DisplayName("Transaction has blank merchant category — treated same as null, rule skipped")
    void shouldSkipWhenMerchantCategoryIsBlank() {
        Transaction tx = new Transaction(UUID.randomUUID(), "ACC-001",
                new BigDecimal("2000.00"), "ZAR", "Test Merchant", "   ",
                "ONLINE", "ZAF", LocalDateTime.now());

        RuleResult result = rule.evaluate(tx, params);

        assertThat(result.matched()).isFalse();
        assertThat(result.description()).contains("skipped");
    }

    // ---------------------------------------------------------------------------
    // Port delegation verification
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Port is called with correct accountId, merchantCategory, and transactionId")
    void shouldDelegateToPortWithCorrectArguments() {
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransaction(txId, "ELECTRONICS");

        when(transactionHistoryPort.hasPreviousCategoryTransaction(any(), any(), any()))
                .thenReturn(false);

        rule.evaluate(tx, params);

        // Verify the port is invoked with exactly the transaction's account, category, and ID
        verify(transactionHistoryPort)
                .hasPreviousCategoryTransaction("ACC-001", "ELECTRONICS", txId);
    }

    // ---------------------------------------------------------------------------
    // Rule identity
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("getRuleName returns UNUSUAL_MERCHANT_CATEGORY_RULE")
    void shouldReturnCorrectRuleName() {
        assertThat(rule.getRuleName()).isEqualTo(UnusualMerchantCategoryRule.RULE_NAME);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private Transaction buildTransaction(UUID id, String merchantCategory) {
        return new Transaction(id, "ACC-001", new BigDecimal("2000.00"), "ZAR",
                "Test Merchant", merchantCategory, "ONLINE", "ZAF", LocalDateTime.now());
    }

    private Transaction buildTransactionNoCategory(UUID id) {
        return new Transaction(id, "ACC-001", new BigDecimal("2000.00"), "ZAR",
                "Test Merchant", null, "ONLINE", "ZAF", LocalDateTime.now());
    }
}
