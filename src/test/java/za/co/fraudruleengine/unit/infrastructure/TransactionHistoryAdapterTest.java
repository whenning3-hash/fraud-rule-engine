package za.co.fraudruleengine.unit.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import za.co.fraudruleengine.repository.TransactionHistoryAdapter;
import za.co.fraudruleengine.repository.TransactionJpaRepository;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionHistoryAdapter}.
 *
 * <p>Verifies that the adapter correctly translates domain-level parameters into
 * repository-level queries, and that it returns results from the repository unchanged.
 * The {@link TransactionJpaRepository} is mocked so no database is required.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>{@code getRecentCountries}: correct arguments forwarded to the repository; empty and
 *       non-empty result sets are passed through correctly.</li>
 *   <li>{@code hasPreviousCategoryTransaction}: correct arguments forwarded; both
 *       {@code true} and {@code false} return values are passed through correctly.</li>
 *   <li>Time window calculation: the {@code since} timestamp is computed correctly
 *       as {@code now - windowHours}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TransactionHistoryAdapterTest {

    @Mock
    private TransactionJpaRepository transactionJpaRepository;

    private TransactionHistoryAdapter adapter;

    /** Captures the LocalDateTime 'since' argument passed to the repository. */
    @Captor
    private ArgumentCaptor<LocalDateTime> sinceCaptor;

    @BeforeEach
    void setUp() {
        adapter = new TransactionHistoryAdapter(transactionJpaRepository);
    }

    // ---------------------------------------------------------------------------
    // getRecentCountries — country mismatch queries
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("getRecentCountries returns whatever the repository returns")
    void shouldReturnCountriesFromRepository() {
        UUID txId = UUID.randomUUID();
        Set<String> expected = Set.of("ZAF", "GBR");

        when(transactionJpaRepository.findDistinctCountryCodes(any(), any(), any()))
                .thenReturn(expected);

        Set<String> result = adapter.getRecentCountries("ACC-001", 24, txId);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("getRecentCountries returns empty set when repository finds no records")
    void shouldReturnEmptySetWhenNoPriorCountries() {
        when(transactionJpaRepository.findDistinctCountryCodes(any(), any(), any()))
                .thenReturn(Set.of());

        Set<String> result = adapter.getRecentCountries("ACC-001", 24, UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getRecentCountries passes correct accountId and transactionId to repository")
    void shouldDelegateCorrectAccountIdAndTransactionIdForCountries() {
        UUID txId = UUID.randomUUID();
        when(transactionJpaRepository.findDistinctCountryCodes(any(), any(), any()))
                .thenReturn(Set.of("ZAF"));

        adapter.getRecentCountries("ACC-007", 12, txId);

        verify(transactionJpaRepository)
                .findDistinctCountryCodes(eq("ACC-007"), any(), eq(txId));
    }

    @Test
    @DisplayName("getRecentCountries calculates 'since' as now minus windowHours")
    void shouldCalculateSinceTimestampCorrectly() {
        // Record time just before calling the adapter so we can assert the 'since' is close
        LocalDateTime before = LocalDateTime.now().minusHours(24).minusSeconds(1);
        LocalDateTime after  = LocalDateTime.now().minusHours(24).plusSeconds(1);

        when(transactionJpaRepository.findDistinctCountryCodes(any(), sinceCaptor.capture(), any()))
                .thenReturn(Set.of());

        adapter.getRecentCountries("ACC-001", 24, UUID.randomUUID());

        LocalDateTime since = sinceCaptor.getValue();
        assertThat(since).isAfterOrEqualTo(before);
        assertThat(since).isBeforeOrEqualTo(after);
    }

    // ---------------------------------------------------------------------------
    // hasPreviousCategoryTransaction — category history queries
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("hasPreviousCategoryTransaction returns true when repository finds a prior record")
    void shouldReturnTrueWhenPriorCategoryExists() {
        UUID txId = UUID.randomUUID();
        when(transactionJpaRepository.existsByAccountIdAndMerchantCategoryAndIdNot(
                "ACC-001", "RETAIL", txId))
                .thenReturn(true);

        boolean result = adapter.hasPreviousCategoryTransaction("ACC-001", "RETAIL", txId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("hasPreviousCategoryTransaction returns false for first-time category usage")
    void shouldReturnFalseWhenNoPriorCategoryRecord() {
        UUID txId = UUID.randomUUID();
        when(transactionJpaRepository.existsByAccountIdAndMerchantCategoryAndIdNot(
                "ACC-001", "FOREX", txId))
                .thenReturn(false);

        boolean result = adapter.hasPreviousCategoryTransaction("ACC-001", "FOREX", txId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("hasPreviousCategoryTransaction passes correct arguments to repository")
    void shouldDelegateCorrectArgumentsForCategoryCheck() {
        UUID txId = UUID.randomUUID();
        when(transactionJpaRepository.existsByAccountIdAndMerchantCategoryAndIdNot(any(), any(), any()))
                .thenReturn(false);

        adapter.hasPreviousCategoryTransaction("ACC-009", "GAMBLING", txId);

        verify(transactionJpaRepository)
                .existsByAccountIdAndMerchantCategoryAndIdNot("ACC-009", "GAMBLING", txId);
    }
}
