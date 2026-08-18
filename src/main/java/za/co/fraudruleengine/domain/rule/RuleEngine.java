package za.co.fraudruleengine.domain.rule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.infrastructure.persistence.entity.RuleConfigEntity;
import za.co.fraudruleengine.infrastructure.persistence.repository.RuleConfigJpaRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngine {

    private final List<FraudRule> rules;
    private final RuleConfigJpaRepository ruleConfigRepository;

    public EvaluationResult evaluate(Transaction transaction) {
        Map<String, RuleConfigEntity> configs = ruleConfigRepository.findAll()
                .stream()
                .collect(Collectors.toMap(RuleConfigEntity::getRuleName, Function.identity()));

        List<RuleResult> results = new ArrayList<>();
        int totalScore = 0;

        for (FraudRule rule : rules) {
            RuleConfigEntity config = configs.get(rule.getRuleName());
            if (config == null) {
                log.warn("No configuration found for rule: {}. Skipping.", rule.getRuleName());
                continue;
            }
            if (!config.isEnabled()) {
                log.debug("Rule {} is disabled. Skipping.", rule.getRuleName());
                continue;
            }

            RuleParameters params = new RuleParameters(config.getRiskWeight(), config.getParameters());
            RuleResult result = rule.evaluate(transaction, params);
            results.add(result);

            if (result.matched()) {
                totalScore += result.riskScore();
                log.info("Rule {} MATCHED for account {} — score contribution: {}",
                        rule.getRuleName(), transaction.accountId(), result.riskScore());
            }
        }

        log.info("Evaluation complete for transaction {} — total score: {}", transaction.id(), totalScore);
        return new EvaluationResult(results, totalScore);
    }
}
