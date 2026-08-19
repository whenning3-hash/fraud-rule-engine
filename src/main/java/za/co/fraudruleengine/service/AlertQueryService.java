package za.co.fraudruleengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fraudruleengine.model.AlertStatus;
import za.co.fraudruleengine.entity.FraudAlertEntity;
import za.co.fraudruleengine.repository.AlertJpaRepository;

import java.util.UUID;

/**
 * Application service providing query and lifecycle management operations for fraud alerts.
 *
 * <p>This service follows the CQRS principle at the application layer: the class-level
 * {@code @Transactional(readOnly = true)} annotation makes all query methods run in a read-only
 * transaction, which allows the JPA provider to optimise dirty-checking and enables certain
 * database read replicas to be used. The single write method ({@link #updateStatus}) overrides
 * this with a read-write transaction annotation.
 *
 * <p>Alert queries support filtering by {@code accountId}, {@code status}, or both, delegating
 * to the appropriate repository method. When neither filter is supplied the full paginated result
 * set is returned, which is useful for a fraud-operations dashboard displaying all open alerts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlertQueryService {

    private final AlertJpaRepository alertRepository;

    /**
     * Returns a paginated list of fraud alerts, optionally filtered by account and/or status.
     *
     * <p>The filtering strategy is:
     * <ul>
     *   <li>Both supplied: filters on both account and status (most selective)</li>
     *   <li>Account only: returns all alerts for that account across all statuses</li>
     *   <li>Status only: returns all alerts in that status across all accounts</li>
     *   <li>Neither supplied: returns all alerts (full dataset, paginated)</li>
     * </ul>
     *
     * @param accountId optional account identifier to filter on; {@code null} means no filter
     * @param status    optional alert status to filter on; {@code null} means no filter
     * @param pageable  pagination and sort configuration provided by the caller
     * @return a {@link Page} of matching {@link FraudAlertEntity} instances; never {@code null}
     */
    public Page<FraudAlertEntity> findAlerts(String accountId, AlertStatus status, Pageable pageable) {
        if (accountId != null && status != null) {
            return alertRepository.findByAccountIdAndStatus(accountId, status, pageable);
        } else if (accountId != null) {
            return alertRepository.findByAccountId(accountId, pageable);
        } else if (status != null) {
            return alertRepository.findByStatus(status, pageable);
        }
        return alertRepository.findAll(pageable);
    }

    /**
     * Retrieves a single fraud alert by its unique identifier.
     *
     * @param id the UUID of the alert to retrieve
     * @return the matching {@link FraudAlertEntity}; never {@code null}
     * @throws IllegalArgumentException if no alert exists with the given ID, which is mapped to
     *                                  HTTP 404 by {@link za.co.fraudruleengine.api.GlobalExceptionHandler}
     */
    public FraudAlertEntity findById(UUID id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + id));
    }

    /**
     * Transitions a fraud alert to a new lifecycle status, enforcing the valid state machine.
     *
     * <p>This method overrides the class-level read-only transaction with a writable one.
     * The previous status is captured before the update for audit logging, giving operations
     * teams a clear trail of every state change.
     *
     * <p>Valid transitions:
     * <ul>
     *   <li>{@code OPEN → REVIEWED} — analyst picks up the alert</li>
     *   <li>{@code OPEN → CLOSED} — direct close (confirmed false positive during triage)</li>
     *   <li>{@code REVIEWED → CLOSED} — investigation complete</li>
     * </ul>
     *
     * <p>Any other transition (e.g. {@code CLOSED → OPEN}) is rejected with
     * {@link IllegalStateException}, which maps to HTTP 409 Conflict.
     *
     * @param id        the UUID of the alert to update
     * @param newStatus the target status to transition the alert to
     * @return the updated and persisted {@link FraudAlertEntity}; never {@code null}
     * @throws IllegalArgumentException if no alert exists with the given ID (→ 404)
     * @throws IllegalStateException    if the transition is not permitted by the state machine (→ 409)
     */
    @Transactional
    public FraudAlertEntity updateStatus(UUID id, AlertStatus newStatus) {
        FraudAlertEntity alert = findById(id);
        AlertStatus current = alert.getStatus();

        // Enforce state machine — only forward transitions are permitted.
        if (!current.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    String.format("Invalid alert status transition: %s → %s. "
                            + "Allowed transitions from %s: %s",
                            current, newStatus, current,
                            current == AlertStatus.CLOSED
                                    ? "none (terminal state)"
                                    : current.name().equals("OPEN") ? "REVIEWED, CLOSED" : "CLOSED"));
        }

        alert.setStatus(newStatus);
        log.info("Alert {} status transition: {} → {}", id, current, newStatus);
        return alertRepository.save(alert);
    }
}
