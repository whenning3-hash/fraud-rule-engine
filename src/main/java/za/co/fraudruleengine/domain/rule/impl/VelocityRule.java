package za.co.fraudruleengine.domain.rule.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.FraudRule;
import za.co.fraudruleengine.domain.rule.RuleParameters;
import za.co.fraudruleengine.domain.rule.RuleResult;
import za.co.fraudruleengine.infrastructure.redis.VelocityStorePort;

@Component
@RequiredArgsConstructor
public class VelocityRule implements FraudRule {

    public static final String RULE_NAME = "VELOCITY_RULE";

    private final VelocityStorePort velocityStore;

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        int maxTransactions = parameters.getInt("maxTransactions", 5);
        int windowMinutes = parameters.getInt("windowMinutes", 10);

        long count = velocityStore.getTransactionCount(transaction.accountId(), windowMinutes);
        velocityStore.recordTransaction(transaction.accountId(), transaction.id().toString());

        boolean matched = count >= maxTransactions;

        String description = matched
                ? String.format("Account %s made %d transactions in the last %d minutes (max allowed: %d)",
                        transaction.accountId(), count, windowMinutes, maxTransactions)
                : String.format("Velocity within limits: %d/%d transactions in %d minutes",
                        count, maxTransactions, windowMinutes);

        return new RuleResult(RULE_NAME, matched, matched ? parameters.riskWeight() : 0, description);
    }
}
