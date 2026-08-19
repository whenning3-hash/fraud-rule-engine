package za.co.fraudruleengine.rule.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import za.co.fraudruleengine.model.RuleName;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.rule.FraudRule;
import za.co.fraudruleengine.rule.RuleParameters;
import za.co.fraudruleengine.rule.RuleResult;
import za.co.fraudruleengine.rule.TransactionHistoryPort;

import java.util.Set;

/**
 * Fraud rule that detects impossible-travel patterns by checking whether a transaction's country
 * differs from the countries seen in recent transactions on the same account.
 *
 * <p><strong>Why this pattern is high-risk in the Capitec context:</strong><br>
 * SIM-swap attacks, card cloning, and card-not-present fraud often result in transactions
 * appearing simultaneously — or in rapid succession — from different countries. A legitimate
 * customer physically cannot transact in South Africa and the United Kingdom within a few hours
 * of each other. Detecting this "impossible travel" scenario is a high-precision fraud signal
 * used by major card networks (Visa, Mastercard) and tier-1 banks globally.
 *
 * <p>The rule queries recent transaction history for the same account via the
 * {@link TransactionHistoryPort} domain port. If any previous transaction within the configured
 * look-back window used a different country code, the rule fires.
 *
 * <p><strong>Configuration parameters</strong> (stored in {@code rule_configs.parameters}):
 * <ul>
 *   <li>{@code windowHours} — how many hours back to look for prior transactions
 *       (default: {@code 24})</li>
 * </ul>
 *
 * <p><strong>Note:</strong> If an account has no prior transactions within the window (new account
 * or long-inactive account), the rule does not fire — this avoids false positives on first-ever
 * international usage. The mismatch must be against a <em>confirmed historical transaction</em>.
 *
 * <p>Country codes follow ISO 3166-1 alpha-3 (matching the {@link Transaction#countryCode()} field),
 * e.g. {@code "ZAF"} for South Africa, {@code "GBR"} for the United Kingdom.
 */
@Component
@RequiredArgsConstructor
public class CountryMismatchRule implements FraudRule {

    /** Canonical rule name used as the join key with the {@code rule_configs} database row. */
    public static final String RULE_NAME = RuleName.COUNTRY_MISMATCH_RULE.name();

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
     * Evaluates whether the current transaction's country code differs from any country seen
     * in recent transactions on the same account.
     *
     * <p>The rule fires when:
     * <ol>
     *   <li>The current transaction has a non-null, non-blank {@code countryCode}.</li>
     *   <li>There is at least one prior transaction for the same account within
     *       {@code windowHours} hours.</li>
     *   <li>At least one of those prior transactions used a <em>different</em> country code.</li>
     * </ol>
     *
     * @param transaction the transaction to evaluate
     * @param parameters  rule configuration; reads {@code windowHours}
     * @return a matched {@link RuleResult} with the full risk weight when a country mismatch is
     *         detected; otherwise a non-matching result with score {@code 0}
     */
    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        int windowHours = parameters.getInt("windowHours", 24);
        String currentCountry = transaction.countryCode();

        // Cannot evaluate country mismatch if the current transaction has no country code
        if (currentCountry == null || currentCountry.isBlank()) {
            return new RuleResult(RULE_NAME, false, 0,
                    "Transaction has no country code — country mismatch check skipped");
        }

        // Query recent countries from historical transactions, excluding this transaction itself
        Set<String> recentCountries = transactionHistoryPort
                .getRecentCountries(transaction.accountId(), windowHours, transaction.id());

        // No prior transactions in the window: cannot determine mismatch; treat as clean
        if (recentCountries.isEmpty()) {
            return new RuleResult(RULE_NAME, false, 0,
                    String.format("No prior transactions in the last %d hours — country mismatch check skipped",
                            windowHours));
        }

        // Check if the current country differs from ANY country seen in the recent window
        boolean hasMismatch = recentCountries.stream()
                .anyMatch(c -> !c.equalsIgnoreCase(currentCountry));

        String description = hasMismatch
                ? String.format("Country mismatch detected — current: %s, recent countries: %s (window: %dh)",
                        currentCountry, recentCountries, windowHours)
                : String.format("Country matches recent transactions — current: %s, recent: %s",
                        currentCountry, recentCountries);

        return new RuleResult(RULE_NAME, hasMismatch, hasMismatch ? parameters.riskWeight() : 0, description);
    }
}
