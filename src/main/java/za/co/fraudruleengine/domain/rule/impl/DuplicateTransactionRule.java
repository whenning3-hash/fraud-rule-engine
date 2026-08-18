package za.co.fraudruleengine.domain.rule.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.FraudRule;
import za.co.fraudruleengine.domain.rule.RuleParameters;
import za.co.fraudruleengine.domain.rule.RuleResult;
import za.co.fraudruleengine.infrastructure.redis.VelocityStore;

@Component
@RequiredArgsConstructor
public class DuplicateTransactionRule implements FraudRule {

    public static final String RULE_NAME = "DUPLICATE_TRANSACTION_RULE";

    private final VelocityStore velocityStore;

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        int windowSeconds = parameters.getInt("windowSeconds", 60);

        boolean isDuplicate = velocityStore.isDuplicate(
                transaction.accountId(),
                transaction.amount().toPlainString(),
                transaction.merchantName(),
                transaction.id().toString(),
                windowSeconds
        );

        String description = isDuplicate
                ? String.format("Duplicate detected: same account, amount (%s), and merchant (%s) within %d seconds",
                        transaction.amount(), transaction.merchantName(), windowSeconds)
                : "No duplicate transaction detected";

        return new RuleResult(RULE_NAME, isDuplicate, isDuplicate ? parameters.riskWeight() : 0, description);
    }
}
