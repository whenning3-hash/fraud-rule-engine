package za.co.fraudruleengine.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.fraudruleengine.model.AlertStatus;
import za.co.fraudruleengine.entity.FraudAlertEntity;

import java.util.UUID;

/**
 * Spring Data JPA repository for {@link FraudAlertEntity} — the persistent store for
 * fraud alerts raised by the rule engine when a transaction's risk score exceeds the
 * configured threshold.
 *
 * <p>Provides paginated query methods for the two most common alert-list filters used by
 * investigators: filter by account ID, filter by alert status, and the combined variant.
 * All three return a {@link Page} so that the REST API can expose cursor-based pagination
 * without loading the full alert history into memory.
 *
 * <p>Alert lifecycle transitions ({@code OPEN → REVIEWED → CLOSED}) are enforced by the
 * application layer ({@code AlertService}) — this repository performs no state-machine
 * validation and will persist any {@link za.co.fraudruleengine.model.AlertStatus} value
 * supplied to it.
 */
@Repository
public interface AlertJpaRepository extends JpaRepository<FraudAlertEntity, UUID> {

    Page<FraudAlertEntity> findByAccountId(String accountId, Pageable pageable);

    Page<FraudAlertEntity> findByStatus(AlertStatus status, Pageable pageable);

    Page<FraudAlertEntity> findByAccountIdAndStatus(String accountId, AlertStatus status, Pageable pageable);
}
