package za.co.fraudruleengine.infrastructure.persistence.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.fraudruleengine.domain.model.AlertStatus;
import za.co.fraudruleengine.infrastructure.persistence.entity.FraudAlertEntity;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface AlertJpaRepository extends JpaRepository<FraudAlertEntity, UUID> {

    Page<FraudAlertEntity> findByAccountId(String accountId, Pageable pageable);

    Page<FraudAlertEntity> findByStatus(AlertStatus status, Pageable pageable);

    Page<FraudAlertEntity> findByAccountIdAndStatus(String accountId, AlertStatus status, Pageable pageable);

    Page<FraudAlertEntity> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to, Pageable pageable);
}
