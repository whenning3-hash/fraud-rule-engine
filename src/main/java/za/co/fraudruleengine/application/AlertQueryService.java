package za.co.fraudruleengine.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fraudruleengine.domain.model.AlertStatus;
import za.co.fraudruleengine.infrastructure.persistence.entity.FraudAlertEntity;
import za.co.fraudruleengine.infrastructure.persistence.repository.AlertJpaRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlertQueryService {

    private final AlertJpaRepository alertRepository;

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

    public FraudAlertEntity findById(UUID id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + id));
    }

    @Transactional
    public FraudAlertEntity updateStatus(UUID id, AlertStatus newStatus) {
        FraudAlertEntity alert = findById(id);
        alert.setStatus(newStatus);
        return alertRepository.save(alert);
    }
}
