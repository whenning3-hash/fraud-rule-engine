package za.co.fraudruleengine.domain.rule;

import java.math.BigDecimal;
import java.util.Map;

public record RuleParameters(int riskWeight, Map<String, String> params) {

    public int getInt(String key, int defaultValue) {
        String val = params.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public BigDecimal getBigDecimal(String key, BigDecimal defaultValue) {
        String val = params.get(key);
        if (val == null) return defaultValue;
        try {
            return new BigDecimal(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
