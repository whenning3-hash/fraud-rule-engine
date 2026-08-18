package za.co.fraudruleengine.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import za.co.fraudruleengine.infrastructure.persistence.entity.TransactionEntity;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link TransactionEntity}.
 *
 * <p>Extends {@link JpaRepository} for standard CRUD operations, and declares additional
 * query methods required by the fraud rule engine's historical analysis features.
 *
 * <p><strong>Custom query methods:</strong>
 * <ul>
 *   <li>{@link #findDistinctCountryCodes} — used by the country-mismatch fraud rule to
 *       detect impossible-travel patterns within a rolling time window.</li>
 *   <li>{@link #existsByAccountIdAndMerchantCategoryAndIdNot} — used by the unusual merchant
 *       category rule to determine whether an account has previously transacted in a given
 *       merchant category.</li>
 * </ul>
 *
 * <p>All query methods exclude the current transaction being evaluated (via
 * {@code id != :currentId}) to prevent a rule from trivially satisfying itself against its
 * own transaction record.
 */
@Repository
public interface TransactionJpaRepository extends JpaRepository<TransactionEntity, UUID> {

    /**
     * Returns the distinct ISO 3166-1 alpha-3 country codes found in recent transactions for
     * the given account, within a rolling time window, excluding the specified transaction.
     *
     * <p>Used by {@code CountryMismatchRule} to detect impossible-travel fraud patterns.
     * Results are distinct — the same country will not appear more than once — so the rule can
     * efficiently compare the set against the current transaction's country.
     *
     * @param accountId      the account to query
     * @param since          the earliest transaction timestamp to include (window start)
     * @param currentTxId    the ID of the transaction currently under evaluation; excluded from
     *                       results so the rule is not trivially satisfied by itself
     * @return an unordered set of distinct country codes; empty if no qualifying records exist
     */
    @Query("""
            SELECT DISTINCT t.countryCode
            FROM TransactionEntity t
            WHERE t.accountId = :accountId
              AND t.transactionTime >= :since
              AND t.id != :currentTxId
              AND t.countryCode IS NOT NULL
            """)
    Set<String> findDistinctCountryCodes(
            @Param("accountId") String accountId,
            @Param("since") LocalDateTime since,
            @Param("currentTxId") UUID currentTxId);

    /**
     * Returns {@code true} if at least one previous transaction exists for the given account
     * in the specified merchant category, excluding the current transaction.
     *
     * <p>Used by {@code UnusualMerchantCategoryRule} to determine whether a given merchant
     * category is "established" behaviour for an account. If no prior record exists, the
     * category is considered unusual and the rule fires.
     *
     * @param accountId        the account to query
     * @param merchantCategory the MCC or category string to look up
     * @param currentTxId      the ID of the current transaction; excluded so the rule
     *                         correctly identifies true first-time category usage
     * @return {@code true} if at least one prior transaction exists; {@code false} otherwise
     */
    boolean existsByAccountIdAndMerchantCategoryAndIdNot(
            String accountId,
            String merchantCategory,
            UUID currentTxId);
}
