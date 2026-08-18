package za.co.fraudruleengine.domain.rule;

import java.util.Set;
import java.util.UUID;

/**
 * Domain port (interface) for querying historical transaction data needed by fraud rules.
 *
 * <p>Follows the Ports and Adapters (hexagonal) architecture pattern: the domain layer defines
 * this interface, and the infrastructure layer provides the concrete implementation
 * ({@code TransactionHistoryAdapter}). This ensures that fraud rules have no compile-time
 * dependency on JPA, Spring Data, or any persistence technology.
 *
 * <p>Current consumers:
 * <ul>
 *   <li>{@code CountryMismatchRule} — detects impossible-travel scenarios by checking whether
 *       the current transaction's country differs from recent transactions on the same account.</li>
 *   <li>{@code UnusualMerchantCategoryRule} — flags the first time an account transacts in a
 *       given merchant category, indicating a possible account takeover or card-not-present fraud.</li>
 * </ul>
 */
public interface TransactionHistoryPort {

    /**
     * Returns the distinct ISO 3166-1 alpha-2 country codes seen in recent transactions for the
     * given account, excluding the current transaction being evaluated.
     *
     * <p>Used by the country-mismatch rule to detect impossible-travel patterns (e.g. a
     * transaction in ZA followed immediately by one in GB).
     *
     * @param accountId        the account to query
     * @param windowHours      how many hours back to look
     * @param currentTransactionId the ID of the transaction currently under evaluation;
     *                         excluded from the result set so the rule is not trivially satisfied
     * @return an unordered set of country codes; empty if no prior transactions exist within
     *         the window
     */
    Set<String> getRecentCountries(String accountId, int windowHours, UUID currentTransactionId);

    /**
     * Returns {@code true} if the given account has previously submitted at least one transaction
     * with the specified merchant category, excluding the current transaction being evaluated.
     *
     * <p>Used by the unusual-merchant-category rule to detect first-time category usage, which
     * can indicate account takeover or social-engineering fraud.
     *
     * @param accountId            the account to query
     * @param merchantCategory     the MCC-equivalent category code to look up
     * @param currentTransactionId the ID of the transaction currently under evaluation;
     *                             excluded so the rule correctly identifies true first-time usage
     * @return {@code true} if at least one prior transaction exists for this account and category;
     *         {@code false} if this is the first time this account uses this category
     */
    boolean hasPreviousCategoryTransaction(String accountId, String merchantCategory,
                                           UUID currentTransactionId);
}
