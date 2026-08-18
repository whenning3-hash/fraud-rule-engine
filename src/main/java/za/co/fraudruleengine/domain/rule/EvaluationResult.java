package za.co.fraudruleengine.domain.rule;

import java.util.List;

public record EvaluationResult(List<RuleResult> ruleResults, int totalScore) {

    public List<String> matchedRuleNames() {
        return ruleResults.stream()
                .filter(RuleResult::matched)
                .map(RuleResult::ruleName)
                .toList();
    }

    public boolean isFraudulent(int threshold) {
        return totalScore >= threshold;
    }
}
