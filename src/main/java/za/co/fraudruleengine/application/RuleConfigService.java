package za.co.fraudruleengine.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fraudruleengine.infrastructure.persistence.entity.RuleConfigEntity;
import za.co.fraudruleengine.infrastructure.persistence.repository.RuleConfigJpaRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RuleConfigService {

    private final RuleConfigJpaRepository ruleConfigRepository;

    public List<RuleConfigEntity> findAll() {
        return ruleConfigRepository.findAll();
    }

    public RuleConfigEntity findById(UUID id) {
        return ruleConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rule config not found: " + id));
    }

    @Transactional
    public RuleConfigEntity update(UUID id, boolean enabled, int riskWeight, Map<String, String> parameters) {
        RuleConfigEntity config = findById(id);
        config.setEnabled(enabled);
        config.setRiskWeight(riskWeight);
        config.setParameters(parameters);
        return ruleConfigRepository.save(config);
    }
}
