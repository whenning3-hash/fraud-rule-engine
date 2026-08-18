package za.co.fraudruleengine.unit.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import za.co.fraudruleengine.application.AlertQueryService;
import za.co.fraudruleengine.domain.model.AlertStatus;
import za.co.fraudruleengine.infrastructure.persistence.entity.FraudAlertEntity;
import za.co.fraudruleengine.infrastructure.persistence.repository.AlertJpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertQueryServiceTest {

    @Mock AlertJpaRepository alertRepository;

    @InjectMocks
    AlertQueryService alertQueryService;

    private final Pageable pageable = PageRequest.of(0, 20);

    @Test
    void findAlerts_noFilters_shouldCallFindAll() {
        Page<FraudAlertEntity> expected = new PageImpl<>(List.of(buildAlert("ACC-001")));
        when(alertRepository.findAll(pageable)).thenReturn(expected);

        Page<FraudAlertEntity> result = alertQueryService.findAlerts(null, null, pageable);

        assertThat(result).isEqualTo(expected);
        verify(alertRepository).findAll(pageable);
        verify(alertRepository, never()).findByAccountId(any(), any());
        verify(alertRepository, never()).findByStatus(any(), any());
    }

    @Test
    void findAlerts_withAccountIdOnly_shouldFilterByAccount() {
        Page<FraudAlertEntity> expected = new PageImpl<>(List.of(buildAlert("ACC-002")));
        when(alertRepository.findByAccountId("ACC-002", pageable)).thenReturn(expected);

        Page<FraudAlertEntity> result = alertQueryService.findAlerts("ACC-002", null, pageable);

        assertThat(result).isEqualTo(expected);
        verify(alertRepository).findByAccountId("ACC-002", pageable);
    }

    @Test
    void findAlerts_withStatusOnly_shouldFilterByStatus() {
        Page<FraudAlertEntity> expected = new PageImpl<>(List.of(buildAlert("ACC-003")));
        when(alertRepository.findByStatus(AlertStatus.OPEN, pageable)).thenReturn(expected);

        Page<FraudAlertEntity> result = alertQueryService.findAlerts(null, AlertStatus.OPEN, pageable);

        assertThat(result).isEqualTo(expected);
        verify(alertRepository).findByStatus(AlertStatus.OPEN, pageable);
    }

    @Test
    void findAlerts_withBothFilters_shouldFilterByAccountAndStatus() {
        Page<FraudAlertEntity> expected = new PageImpl<>(List.of());
        when(alertRepository.findByAccountIdAndStatus("ACC-004", AlertStatus.REVIEWED, pageable))
                .thenReturn(expected);

        Page<FraudAlertEntity> result = alertQueryService.findAlerts("ACC-004", AlertStatus.REVIEWED, pageable);

        assertThat(result).isEqualTo(expected);
        verify(alertRepository).findByAccountIdAndStatus("ACC-004", AlertStatus.REVIEWED, pageable);
    }

    @Test
    void findById_whenExists_shouldReturnAlert() {
        UUID id = UUID.randomUUID();
        FraudAlertEntity alert = buildAlert("ACC-001");
        when(alertRepository.findById(id)).thenReturn(Optional.of(alert));

        FraudAlertEntity result = alertQueryService.findById(id);

        assertThat(result).isEqualTo(alert);
    }

    @Test
    void findById_whenNotFound_shouldThrowIllegalArgumentException() {
        UUID id = UUID.randomUUID();
        when(alertRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> alertQueryService.findById(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Alert not found");
    }

    @Test
    void updateStatus_shouldUpdateAndSaveAlert() {
        UUID id = UUID.randomUUID();
        FraudAlertEntity alert = buildAlert("ACC-005");
        alert.setStatus(AlertStatus.OPEN);
        when(alertRepository.findById(id)).thenReturn(Optional.of(alert));
        when(alertRepository.save(alert)).thenReturn(alert);

        FraudAlertEntity result = alertQueryService.updateStatus(id, AlertStatus.REVIEWED);

        assertThat(result.getStatus()).isEqualTo(AlertStatus.REVIEWED);
        verify(alertRepository).save(alert);
    }

    @Test
    void updateStatus_whenAlertNotFound_shouldThrow() {
        UUID id = UUID.randomUUID();
        when(alertRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> alertQueryService.updateStatus(id, AlertStatus.CLOSED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private FraudAlertEntity buildAlert(String accountId) {
        return FraudAlertEntity.builder()
                .id(UUID.randomUUID())
                .transactionId(UUID.randomUUID())
                .accountId(accountId)
                .totalRiskScore(60)
                .status(AlertStatus.OPEN)
                .matchedRules(List.of("AMOUNT_THRESHOLD_RULE"))
                .ruleDetails("[]")
                .build();
    }
}
