package za.co.fraudruleengine.domain.rule.impl;

import org.springframework.stereotype.Component;
import za.co.fraudruleengine.domain.model.Transaction;
import za.co.fraudruleengine.domain.rule.FraudRule;
import za.co.fraudruleengine.domain.rule.RuleParameters;
import za.co.fraudruleengine.domain.rule.RuleResult;

@Component
public class OffHoursRule implements FraudRule {

    public static final String RULE_NAME = "OFF_HOURS_RULE";

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, RuleParameters parameters) {
        int suspiciousStartHour = parameters.getInt("startHour", 0);
        int suspiciousEndHour = parameters.getInt("endHour", 5);
        int transactionHour = transaction.transactionTime().getHour();

        boolean matched = transactionHour >= suspiciousStartHour && transactionHour < suspiciousEndHour;

        String description = matched
                ? String.format("Transaction occurred at %02d:xx, within off-hours window (%02d:00–%02d:00)",
                        transactionHour, suspiciousStartHour, suspiciousEndHour)
                : String.format("Transaction occurred at %02d:xx, outside off-hours window", transactionHour);

        return new RuleResult(RULE_NAME, matched, matched ? parameters.riskWeight() : 0, description);
    }
}
