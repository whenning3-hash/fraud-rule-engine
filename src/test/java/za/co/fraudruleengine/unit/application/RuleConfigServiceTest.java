package za.co.fraudruleengine.unit.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import za.co.fraudruleengine.service.RuleConfigService;
import za.co.fraudruleengine.entity.RuleConfigEntity;
import za.co.fraudruleengine.repository.RuleConfigJpaRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleConfigServiceTest {

    @Mock RuleConfigJpaRepository ruleConfigRepository;

    @InjectMocks
    RuleConfigService ruleConfigService;

    @Test
    void findAll_shouldReturnAllRules() {
        List<RuleConfigEntity> rules = List.of(
                buildRule("VELOCITY_RULE"),
                buildRule("AMOUNT_THRESHOLD_RULE")
        );
        when(ruleConfigRepository.findAll()).thenReturn(rules);

        List<RuleConfigEntity> result = ruleConfigService.findAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getRuleName()).isEqualTo("VELOCITY_RULE");
    }

    @Test
    void findById_whenExists_shouldReturnRule() {
        UUID id = UUID.randomUUID();
        RuleConfigEntity rule = buildRule("VELOCITY_RULE");
        when(ruleConfigRepository.findById(id)).thenReturn(Optional.of(rule));

        RuleConfigEntity result = ruleConfigService.findById(id);

        assertThat(result.getRuleName()).isEqualTo("VELOCITY_RULE");
    }

    @Test
    void findById_whenNotFound_shouldThrowIllegalArgumentException() {
        UUID id = UUID.randomUUID();
        when(ruleConfigRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ruleConfigService.findById(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rule config not found");
    }

    @Test
    void update_shouldModifyFieldsAndSave() {
        UUID id = UUID.randomUUID();
        RuleConfigEntity rule = buildRule("VELOCITY_RULE");
        rule.setEnabled(true);
        rule.setRiskWeight(30);
        rule.setParameters(Map.of("maxTransactions", "5", "windowMinutes", "10"));

        when(ruleConfigRepository.findById(id)).thenReturn(Optional.of(rule));
        when(ruleConfigRepository.save(rule)).thenReturn(rule);

        Map<String, String> newParams = Map.of("maxTransactions", "3", "windowMinutes", "5");
        RuleConfigEntity result = ruleConfigService.update(id, false, 35, newParams);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getRiskWeight()).isEqualTo(35);
        assertThat(result.getParameters()).containsEntry("maxTransactions", "3");
        assertThat(result.getParameters()).containsEntry("windowMinutes", "5");
        verify(ruleConfigRepository).save(rule);
    }

    @Test
    void update_whenRuleNotFound_shouldThrow() {
        UUID id = UUID.randomUUID();
        when(ruleConfigRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ruleConfigService.update(id, true, 30, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_enableDisableRule_shouldPersistEnabledFlag() {
        UUID id = UUID.randomUUID();
        RuleConfigEntity rule = buildRule("OFF_HOURS_RULE");
        rule.setEnabled(true);

        when(ruleConfigRepository.findById(id)).thenReturn(Optional.of(rule));
        when(ruleConfigRepository.save(rule)).thenReturn(rule);

        ruleConfigService.update(id, false, 20, Map.of("startHour", "0", "endHour", "5"));

        assertThat(rule.isEnabled()).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private RuleConfigEntity buildRule(String name) {
        return RuleConfigEntity.builder()
                .id(UUID.randomUUID())
                .ruleName(name)
                .enabled(true)
                .riskWeight(30)
                .parameters(Map.of())
                .build();
    }
}
