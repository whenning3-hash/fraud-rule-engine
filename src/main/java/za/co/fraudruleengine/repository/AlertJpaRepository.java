package za.co.fraudruleengine.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.fraudruleengine.model.AlertStatus;
import za.co.fraudruleengine.entity.FraudAlertEntity;

import java.util.UUID;

@Repository
public interface AlertJpaRepository extends JpaRepository<FraudAlertEntity, UUID> {

    Page<FraudAlertEntity> findByAccountId(String accountId, Pageable pageable);

    Page<FraudAlertEntity> findByStatus(AlertStatus status, Pageable pageable);

    Page<FraudAlertEntity> findByAccountIdAndStatus(String accountId, AlertStatus status, Pageable pageable);
}
