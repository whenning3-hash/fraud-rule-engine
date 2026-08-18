package za.co.fraudruleengine.domain.rule;

public record RuleResult(
        String ruleName,
        boolean matched,
        int riskScore,
        String description
) {}
