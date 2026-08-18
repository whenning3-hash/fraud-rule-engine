package za.co.fraudruleengine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.fraudruleengine.entity.RuleConfigEntity;
import za.co.fraudruleengine.repository.RuleConfigJpaRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application service for reading and updating fraud rule configurations.
 *
 * <p>Rule configurations are stored in the {@code rule_configs} table and control the live
 * behaviour of the {@link za.co.fraudruleengine.rule.RuleEngine}. Because the engine
 * fetches configuration on every evaluation, changes made through this service take effect
 * immediately on the next transaction submission — no restart is required.
 *
 * <p>This service is read-heavy (supporting the rules management UI and the engine itself) and
 * uses {@code @Transactional(readOnly = true)} at the class level for efficient query execution,
 * with the single mutating method overriding to a writable transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RuleConfigService {

    private final RuleConfigJpaRepository ruleConfigRepository;

    /**
     * Returns all persisted rule configurations.
     *
     * <p>The result set is small and bounded (one row per registered rule), so no pagination
     * is applied.
     *
     * @return an unordered list of all {@link RuleConfigEntity} instances; never {@code null},
     *         may be empty if the seed migration has not been applied
     */
    public List<RuleConfigEntity> findAll() {
        return ruleConfigRepository.findAll();
    }

    /**
     * Retrieves a single rule configuration by its UUID.
     *
     * @param id the primary key of the rule config to retrieve
     * @return the matching {@link RuleConfigEntity}; never {@code null}
     * @throws IllegalArgumentException if no config exists with the given ID, mapped to
     *                                  HTTP 404 by {@link za.co.fraudruleengine.api.GlobalExceptionHandler}
     */
    public RuleConfigEntity findById(UUID id) {
        return ruleConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rule config not found: " + id));
    }

    /**
     * Updates the enabled flag, risk weight, and parameter map of an existing rule configuration.
     *
     * <p>All three mutable fields are updated together in a single operation, matching the PATCH
     * semantics exposed by the API. The previous values are logged at INFO level to provide an
     * audit trail for compliance purposes.
     *
     * @param id         the UUID of the rule configuration to update
     * @param enabled    whether the rule should be active on the next evaluation cycle
     * @param riskWeight the new risk weight; the score added when this rule fires
     * @param parameters the replacement parameter map (e.g. {@code {"maxAmount": "15000.00"}})
     * @return the updated and persisted {@link RuleConfigEntity}; never {@code null}
     * @throws IllegalArgumentException if no config exists with the given ID
     */
    @Transactional
    public RuleConfigEntity update(UUID id, boolean enabled, int riskWeight, Map<String, String> parameters) {
        RuleConfigEntity config = findById(id);
        log.info("Rule config update — rule: {}, enabled: {} → {}, riskWeight: {} → {}",
                config.getRuleName(), config.isEnabled(), enabled, config.getRiskWeight(), riskWeight);
        config.setEnabled(enabled);
        config.setRiskWeight(riskWeight);
        config.setParameters(parameters);
        return ruleConfigRepository.save(config);
    }
}
