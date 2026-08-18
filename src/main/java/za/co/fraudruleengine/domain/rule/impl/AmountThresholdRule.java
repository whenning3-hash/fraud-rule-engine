package za.co.fraudruleengine.domain.rule.impl;

import org.springframework.stereotype.Component;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.FraudRule;
import za.co.fraudruleengine.domain.rule.RuleParameters;
import za.co.fraudruleengine.domain.rule.RuleResult;

import java.math.BigDecimal;

@Component
public class AmountThresholdRule implements FraudRule {

    public static final String RULE_NAME = "AMOUNT_THRESHOLD_RULE";

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        BigDecimal maxAmount = parameters.getBigDecimal("maxAmount", new BigDecimal("10000.00"));
        boolean matched = transaction.amount().compareTo(maxAmount) > 0;

        String description = matched
                ? String.format("Transaction amount %s %s exceeds threshold of %s",
                        transaction.amount(), transaction.currency(), maxAmount)
                : String.format("Transaction amount %s %s within threshold of %s",
                        transaction.amount(), transaction.currency(), maxAmount);

        return new RuleResult(RULE_NAME, matched, matched ? parameters.riskWeight() : 0, description);
    }
}
