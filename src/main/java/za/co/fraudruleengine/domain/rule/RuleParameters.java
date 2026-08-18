package za.co.fraudruleengine.domain.rule;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
public record RuleParameters(int riskWeight, Map<String, String> params) {

    public int getInt(String key, int defaultValue) {
        String val = params.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            log.warn("Invalid integer parameter '{}' = '{}'; using default: {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    public BigDecimal getBigDecimal(String key, BigDecimal defaultValue) {
        String val = params.get(key);
        if (val == null) return defaultValue;
        try {
            return new BigDecimal(val);
        } catch (NumberFormatException e) {
            log.warn("Invalid decimal parameter '{}' = '{}'; using default: {}", key, val, defaultValue);
            return defaultValue;
        }
    }
}
