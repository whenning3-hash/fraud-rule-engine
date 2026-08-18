package za.co.fraudruleengine.domain.rule;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Immutable value object that bundles a rule's risk weight with its key/value configuration map.
 *
 * <p>An instance is constructed by the {@link RuleEngine} for each enabled rule before each
 * evaluation, sourced directly from the {@code rule_configs} database row. Rule implementations
 * read their tunable parameters through the typed accessor methods ({@link #getInt} and
 * {@link #getBigDecimal}) which provide safe fallbacks and log warnings when a stored value
 * cannot be parsed, preventing a misconfigured parameter from crashing the evaluation pipeline.
 *
 * <p>The {@code riskWeight} is the maximum score contribution this rule can add to a
 * transaction's aggregate risk score when it fires. Rules return this value directly from
 * {@link RuleResult#riskScore()} on a match and {@code 0} on a non-match.
 *
 * @param riskWeight the score units added to the transaction's total risk score when this rule matches
 * @param params     the rule-specific key/value configuration parameters stored in {@code rule_configs.parameters}
 */
@Slf4j
public record RuleParameters(int riskWeight, Map<String, String> params) {

    /**
     * Retrieves a named parameter as an {@code int}, falling back to {@code defaultValue} if the
     * key is absent or the stored string value cannot be parsed as a valid integer.
     *
     * @param key          the parameter name to look up
     * @param defaultValue the fallback value used when the key is missing or unparseable
     * @return the parsed integer value, or {@code defaultValue}
     */
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

    /**
     * Retrieves a named parameter as a {@link BigDecimal}, falling back to {@code defaultValue} if
     * the key is absent or the stored string value cannot be parsed.
     *
     * <p>{@code BigDecimal} is used rather than {@code double} to preserve exact monetary precision
     * when comparing against transaction amounts.
     *
     * @param key          the parameter name to look up
     * @param defaultValue the fallback value used when the key is missing or unparseable
     * @return the parsed {@link BigDecimal} value, or {@code defaultValue}
     */
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
