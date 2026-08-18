package za.co.fraudruleengine.infrastructure.persistence.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.fraudruleengine.domain.rule.TransactionHistoryPort;
import za.co.fraudruleengine.infrastructure.persistence.repository.TransactionJpaRepository;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Infrastructure adapter that implements {@link TransactionHistoryPort} using JPA queries against
 * the {@code transactions} table.
 *
 * <p>This class is the driven adapter in the Ports &amp; Adapters (hexagonal) architecture pattern:
 * <ul>
 *   <li>The <strong>port</strong> ({@link TransactionHistoryPort}) is defined in the domain layer
 *       with no persistence dependencies.</li>
 *   <li>This <strong>adapter</strong> bridges the domain port to the concrete Spring Data JPA
 *       repository, keeping all JPA concerns out of the domain.</li>
 * </ul>
 *
 * <p>The adapter translates domain-level parameters (e.g. {@code windowHours}) into persistence-
 * level parameters (e.g. a concrete {@link LocalDateTime} cutoff) before delegating to the
 * repository.
 *
 * <p>All operations are read-only and do not modify any state.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionHistoryAdapter implements TransactionHistoryPort {

    private final TransactionJpaRepository transactionJpaRepository;

    /**
     * {@inheritDoc}
     *
     * <p>Queries the {@code transactions} table for distinct country codes seen in transactions
     * for the given account within the specified rolling time window. The window is calculated
     * as {@code now - windowHours} using the system clock at the time of each call.
     *
     * @param accountId            the account to query
     * @param windowHours          the width of the rolling time window in hours
     * @param currentTransactionId the current transaction's ID; excluded from query results
     * @return distinct country codes from qualifying transactions; empty set if none
     */
    @Override
    public Set<String> getRecentCountries(String accountId, int windowHours,
                                          UUID currentTransactionId) {
        // Calculate the start of the rolling window from the current time
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);

        Set<String> countries = transactionJpaRepository
                .findDistinctCountryCodes(accountId, since, currentTransactionId);

        log.debug("Country history — account: {}, window: {}h, countries found: {}",
                accountId, windowHours, countries);

        return countries;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks the {@code transactions} table for the existence of at least one prior
     * transaction for the given account in the specified merchant category, excluding the
     * current transaction being evaluated.
     *
     * @param accountId            the account to query
     * @param merchantCategory     the merchant category to look up
     * @param currentTransactionId the current transaction's ID; excluded from the existence check
     * @return {@code true} if at least one prior record exists; {@code false} for first-time usage
     */
    @Override
    public boolean hasPreviousCategoryTransaction(String accountId, String merchantCategory,
                                                  UUID currentTransactionId) {
        boolean hasPrior = transactionJpaRepository
                .existsByAccountIdAndMerchantCategoryAndIdNot(accountId, merchantCategory, currentTransactionId);

        log.debug("Category history — account: {}, category: {}, hasPrior: {}",
                accountId, merchantCategory, hasPrior);

        return hasPrior;
    }
}
