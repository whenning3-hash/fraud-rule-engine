package za.co.fraudruleengine.domain.rule;

import za.co.fraudruleengine.domain.model.Transaction;

public interface FraudRule {
    String getRuleName();
    RuleResult evaluate(Transaction transaction, RuleParameters parameters);
}
