package za.co.fraudruleengine.rule.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.FraudRule;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;
import za.co.fraudruleengine.rule.TransactionHistoryPort;

/**
 * Fraud rule that flags transactions in a merchant category the account has never used before.
 *
 * <p><strong>Why first-time category usage is suspicious in the Capitec context:</strong><br>
 * Account takeover and social-engineering fraud often reveal themselves when the fraudster's
 * spending pattern diverges from the legitimate cardholder's established behaviour. A customer
 * who has never purchased from a foreign exchange dealer, a cryptocurrency exchange, or a
 * luxury goods retailer suddenly transacting in that category is a meaningful anomaly signal.
 * This is sometimes called "behavioural deviation" detection and is a standard component of
 * modern fraud scoring models used by Capitec, FNB, and global card schemes.
 *
 * <p>The rule is intentionally low-weight (default risk weight: {@code 15}) because a new
 * category alone is not conclusive — it is most valuable as a <em>contributor</em> to a
 * composite score alongside other signals such as velocity, off-hours activity, or a country
 * mismatch. The low weight avoids false positives for customers who legitimately expand their
 * spending behaviour.
 *
 * <p><strong>Configuration parameters</strong> (stored in {@code rule_configs.parameters}):
 * <p>This rule has no user-configurable parameters beyond the standard {@code riskWeight}.
 * All historical lookups use the full available transaction history for the account.
 *
 * <p>Merchant categories correspond to the {@link za.co.fraudruleengine.model.Transaction#merchantCategory()}
 * field, which maps to the Merchant Category Code (MCC) equivalent used in the Capitec platform
 * (e.g. {@code "ELECTRONICS"}, {@code "FOREX"}, {@code "GAMBLING"}).
 */
@Component
@RequiredArgsConstructor
public class UnusualMerchantCategoryRule implements FraudRule {

    /** Canonical rule name used as the join key with the {@code rule_configs} database row. */
    public static final String RULE_NAME = "UNUSUAL_MERCHANT_CATEGORY_RULE";

    /**
     * Domain port for querying historical transaction data.
     * Injected by Spring; the concrete implementation lives in the infrastructure layer.
     */
    private final TransactionHistoryPort transactionHistoryPort;

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    /**
     * Evaluates whether the current transaction uses a merchant category the account has
     * never transacted in before.
     *
     * <p>The rule fires when:
     * <ol>
     *   <li>The current transaction has a non-null, non-blank {@code merchantCategory}.</li>
     *   <li>No prior transaction exists for the same account and category, as reported by
     *       {@link TransactionHistoryPort#hasPreviousCategoryTransaction}.</li>
     * </ol>
     *
     * <p>If the account has no prior transaction history at all, the very first transaction in
     * any category will fire this rule. For new accounts this is expected behaviour — the
     * low risk weight ensures this does not produce a false positive when combined with a
     * sufficiently high fraud score threshold.
     *
     * @param transaction the transaction to evaluate
     * @param parameters  rule configuration; no parameters are consumed beyond {@code riskWeight}
     * @return a matched {@link RuleResult} with the full risk weight for first-time category usage;
     *         otherwise a non-matching result with score {@code 0}
     */
    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        String category = transaction.merchantCategory();

        // Cannot evaluate without a merchant category on the transaction
        if (category == null || category.isBlank()) {
            return new RuleResult(RULE_NAME, false, 0,
                    "Transaction has no merchant category — unusual category check skipped");
        }

        // Check whether the account has used this category before (excluding the current transaction)
        boolean hasPrior = transactionHistoryPort
                .hasPreviousCategoryTransaction(transaction.accountId(), category, transaction.id());

        // The rule fires only on the FIRST occurrence — no prior history means this is new behaviour
        boolean isUnusual = !hasPrior;

        String description = isUnusual
                ? String.format("First-time merchant category detected — account: %s, category: %s",
                        transaction.accountId(), category)
                : String.format("Merchant category '%s' is established for account %s",
                        category, transaction.accountId());

        return new RuleResult(RULE_NAME, isUnusual, isUnusual ? parameters.riskWeight() : 0, description);
    }
}
