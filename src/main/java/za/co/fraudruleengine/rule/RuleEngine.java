package za.co.fraudruleengine.rule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import za.co.fraudruleengine.model.Transaction;
import za.co.fraudruleengine.entity.RuleConfigEntity;
import za.co.fraudruleengine.repository.RuleConfigJpaRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central orchestrator that executes all enabled fraud detection rules against a transaction.
 *
 * <p>At application startup, Spring injects every {@link FraudRule} implementation registered as
 * a bean into the {@code rules} list. On each call to {@link #evaluate(Transaction)}, the engine
 * fetches the live database configuration for all rules, skips any that are disabled or lack a
 * configuration row, runs the remaining rules, and accumulates their scores into an
 * {@link EvaluationResult}.
 *
 * <p>Fetching rule configuration per-evaluation (rather than caching at startup) means that an
 * operator can toggle a rule on/off or adjust its parameters at runtime without restarting the
 * service. This is intentional: fraud patterns can change rapidly and operational agility is a
 * first-class requirement.
 *
 * <p>The engine is stateless with respect to individual transactions; all state (e.g. velocity
 * counters, duplicate markers) is managed inside the rule implementations themselves via
 * injected infrastructure ports.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngine {

    private final List<FraudRule> rules;
    private final RuleConfigJpaRepository ruleConfigRepository;

    /**
     * Evaluates all enabled fraud rules against the given transaction and returns the aggregated
     * risk assessment.
     *
     * <p>The evaluation algorithm:
     * <ol>
     *   <li>Load all {@code rule_configs} rows from the database, keyed by rule name.</li>
     *   <li>Iterate over every injected {@link FraudRule} bean in declaration order.</li>
     *   <li>Skip rules that have no corresponding config row (logged as a warning) or whose
     *       config marks them as disabled (logged at debug).</li>
     *   <li>Construct a {@link RuleParameters} wrapper from the config and invoke the rule.</li>
     *   <li>Accumulate the risk score from any matching rule into {@code totalScore}.</li>
     * </ol>
     *
     * @param transaction the transaction to assess; must be a fully populated domain model
     *                    with a non-null {@code id} and {@code accountId}
     * @return an {@link EvaluationResult} containing individual rule outcomes and the aggregate
     *         risk score; never {@code null}
     */
    public EvaluationResult evaluate(Transaction transaction) {
        // Load all rule configs from the database and index by rule name for O(1) lookup per rule
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
